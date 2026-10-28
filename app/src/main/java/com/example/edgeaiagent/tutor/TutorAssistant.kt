package com.example.edgeaiagent.tutor

import android.content.Context
import android.util.Log
import com.example.edgeaiagent.Assistant
import com.example.edgeaiagent.TAG
import com.example.edgeaiagent.loadEngine
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.ai.edge.litertlm.Engine
import java.io.File

private const val APP_NAME = "tutor"
private const val USER_ID = "student"
private const val SESSION_ID = "quiz"

// Session-state key the student's latest message travels under, so the grader can read it
const val STUDENT_ANSWER = "student_answer"

// WORKSHOP: students edit this
const val TUTOR_PERSONA =
    "You are Tutor, a friendly English teacher running a short quiz. " +
        "You never ask, answer or grade quiz questions yourself; your helpers do that."

// WORKSHOP: students edit this
const val TUTOR_WHEN_WAITING =
    "The student was just asked a quiz question, so their message is their answer: call grader. " +
        "'I don't know', 'not sure', 'no idea', 'skip' or a guess are answers too: call grader. " +
        "Only if they clearly want to end the quiz (stop, quit, enough, I'm done, bye): call performance_summary."

// WORKSHOP: students edit this
const val TUTOR_WHEN_READY =
    "No question is waiting. If the student is ready (yes, ok, sure, next, start, go): call quizmaster. " +
        "If they want to stop (no, stop, quit, enough, I'm done, bye): call performance_summary. " +
        "If they ask about or disagree with their last answer, explain it in one or two kind lines " +
        "using the last result below, then ask if they are ready for the next question. " +
        "Otherwise reply in one short line and ask if they are ready for a question."

// WORKSHOP: students edit this
const val TUTOR_WHEN_DONE =
    "The quiz is over. Call performance_summary."

// WORKSHOP: students edit this
const val TUTOR_AFTER_SUMMARY =
    "Do not greet or introduce yourself. " +
        "Write a short, warm brief of the results: the score, what went well (name the questions in answered_right), " +
        "and what to practise (name each item in to_practise; if it is empty, say there is nothing to fix). " +
        "Say goodbye. Do not call a tool."

// WORKSHOP: students edit this
const val TUTOR_FINISHED =
    "The quiz is over and the student already has their results. Do not call a tool. " +
        "If they ask about their results, answer in one or two lines from the results below, naming the actual items. " +
        "If they say goodbye, say goodbye warmly. Otherwise reply in one short line."

// WORKSHOP: students edit this
// Words that really end the quiz; anything else said while a question is waiting is an answer
val STOP_WORDS = listOf("stop", "quit", "enough", "i'm done", "im done", "bye", "end the quiz", "finish")

// WORKSHOP: students edit this
const val QUIZMASTER_RULES =
    "You are the quizmaster. First call next_question. " +
        "If it returns done, say in one line that the quiz is finished. " +
        "Otherwise ask exactly the question it returned, as 'Q<number>: <question>'. Never give the answer."

// WORKSHOP: students edit this
const val GRADER_RULES =
    "You are the grader. Compare the student's answer with the reference answer; " +
        "small spelling slips are fine, and a word with the same meaning as a reference word is correct. " +
        "If the student says they don't know, is not sure or skips, the verdict is wrong and the tip gives the right answer. " +
        "Call save_analysis now with your verdict and one short tip."

// WORKSHOP: students edit this
const val GRADER_REPLY =
    "You are the grader. The answer is saved. Write one or two short, kind lines to the student, in the present tense."

// WORKSHOP: students edit this
// The grader's reply opens with the phrase for the saved verdict, chosen by code, not by the model
val VERDICT_OPENINGS = mapOf("correct" to "You are right!", "partly" to "Almost!", "wrong" to "Not quite.")

private val GREETING = Regex("""^(hello|hi|hey)\b[^.!?\n]*[.!?]\s*""", RegexOption.IGNORE_CASE)

/**
 * A multi-agent English tutor. Each turn the tutor agent picks ONE helper: the grader when an
 * answer comes in, the quizmaster when the student is ready for a question, or the summary to stop.
 */
class TutorAssistant(private val context: Context) : Assistant {

    private var engine: Engine? = null
    private var model: LiteRtLmModel? = null
    private var runner: InMemoryRunner? = null
    private val reporter = StepReporter()
    private val log = TutorLog(File(context.filesDir, "tutor"))
    private val summary = PerformanceSummaryTool(log)
    private val rescue = GraderRescue(log) { reporter.onStep(it) }

    override suspend fun start(): String {
        val (eng, backendName) = loadEngine(context)
        // ONE model for all three agents: one engine in memory, one conversation open at a time
        val llm = LiteRtLmModel.create(eng)

        val grader = LlmAgent(
            name = "grader",
            description = "Grades the student's answer to the waiting question and saves the result",
            model = llm,
            // Rebuilt for each of the grader's two steps: first judge and save, then reply to the student
            instruction = Instruction { ctx -> text(graderStep(ctx.state[STUDENT_ANSWER]?.toString().orEmpty())) },
            tools = listOf(SaveAnalysisTool(log)),
            // save_analysis is the only tool with arguments, so it gets two safety nets
            afterModelCallbacks = listOf(AfterModelCallback { _, response -> rescue.afterModel(response) }),
            onModelErrorCallbacks = listOf(OnModelErrorCallback { _, _, error -> rescue.onModelError(error) }),
            includeContents = LlmAgent.IncludeContents.NONE,
            maxSteps = 2,
        )

        val quizmaster = LlmAgent(
            name = "quizmaster",
            description = "Asks the student the next quiz question",
            model = llm,
            instruction = Instruction(QUIZMASTER_RULES),
            tools = listOf(NextQuestionTool(log)),
            includeContents = LlmAgent.IncludeContents.NONE,
            maxSteps = 2,
        )

        val tutor = LlmAgent(
            name = "tutor",
            model = llm,
            // The tutor sees no chat history; its instruction names only the moves that fit right now
            instruction = Instruction { ctx -> text("$TUTOR_PERSONA\n\n${tutorRule(ctx.invocationId)}") },
            // Each helper's reply ends the turn (skipSummarization), so the student sees it as is;
            // a helper that does not fit is also hidden from the model
            tools = listOf(
                AskAgentTool(grader, skipSummarization = true, isAvailable = { log.pending() != null && !quizOver() }) { ctx ->
                    ctx.state[STUDENT_ANSWER]?.toString().orEmpty()
                },
                AskAgentTool(
                    quizmaster,
                    skipSummarization = true,
                    isAvailable = { log.pending() == null && log.analyses().size < QUIZ_LENGTH && !quizOver() },
                ) { _ -> "Ask the next question." },
                summary,
            ),
            afterModelCallbacks = listOf(
                AfterModelCallback { ctx, response ->
                    guardTutorReply(ctx.state[STUDENT_ANSWER]?.toString().orEmpty(), response)
                }
            ),
            includeContents = LlmAgent.IncludeContents.NONE,
            // One helper or the summary + brief, plus one spare step to recover from a refused pick
            maxSteps = 3,
        )

        // The reporter is passed down to the grader's and quizmaster's own runs too
        val r = InMemoryRunner(agent = tutor, appName = APP_NAME, plugins = listOf(reporter))
        r.sessionService.createSession(SessionKey(APP_NAME, USER_ID, SESSION_ID))
        log.clear()

        engine = eng
        model = llm
        runner = r
        Log.i(TAG, "Backend in use: $backendName (tutor)")
        return "$backendName · Tutor"
    }

    override suspend fun chat(text: String, onStep: (String) -> Unit): String {
        val r = checkNotNull(runner) { "Model not loaded yet" }
        reporter.reset(onStep)
        onStep("model: thinking…")

        // Only the event that ends the turn is shown: a helper's reply, or the tutor's own words
        var answer = ""
        r.runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = Content.fromText(Role.USER, text),
            stateDelta = mapOf(STUDENT_ANSWER to text),
            runConfig = RunConfig(maxLlmCalls = 4),
        ).collect { event ->
            if (!event.isFinalResponse) return@collect
            val helperReply = event.functionResponses().firstOrNull()?.response?.get("result")?.toString()
            answer = helperReply ?: event.content?.text().orEmpty()
        }
        return answer.ifBlank { "Sorry, I could not finish that step. Try again?" }
    }

    /**
     * Guardrail on every tutor reply, before ADK acts on it. A small model sometimes writes a tool's
     * name as text ("quizmaster") instead of calling it, or chats while an answer is waiting to be
     * graded. Both are turned into the tool call it should have made. It may also end the quiz on an
     * answer like "I don't know"; while a question is waiting, only a real [STOP_WORDS] message may
     * end it. Once the results are out, replies pass through, minus the greeting the model keeps adding.
     */
    private fun guardTutorReply(studentMessage: String, response: LlmResponse): LlmResponse {
        if (response.partial) return response
        if (quizOver()) return withoutGreeting(response)
        val waiting = log.pending() != null
        val calls = response.content?.parts.orEmpty().mapNotNull { it.functionCall?.name }

        if (calls.isNotEmpty()) {
            val wantsToStop = STOP_WORDS.any { studentMessage.lowercase().contains(it) }
            if (!(waiting && "performance_summary" in calls && !wantsToStop)) return response
            reporter.onStep("guard: tutor tried to end the quiz but the student did not ask to stop, calling grader")
            return callTool(response, "grader")
        }

        val said = response.content?.text().orEmpty().trim().trim('`', '.', '"', '\'', '*').trim().lowercase()
        val target = when {
            said == "performance_summary" -> said
            waiting -> "grader"
            // All questions answered: the only thing left is the results
            log.analyses().size >= QUIZ_LENGTH -> "performance_summary"
            // "Quizmaster. Let's start the quiz!" names the helper but never calls it
            said.startsWith("quizmaster") -> "quizmaster"
            else -> return response
        }
        reporter.onStep("guard: tutor wrote \"${said.take(40)}\" instead of a tool call, calling $target")
        return callTool(response, target)
    }

    /** Drops an opening "Hello! …" sentence from a text reply; the brief comes mid-conversation. */
    private fun withoutGreeting(response: LlmResponse): LlmResponse {
        if (response.content?.parts.orEmpty().any { it.functionCall != null }) return response
        val said = response.content?.text().orEmpty()
        val trimmed = GREETING.replaceFirst(said, "")
        if (trimmed == said || trimmed.isBlank()) return response
        reporter.onStep("guard: removed the greeting from the tutor's reply")
        return response.copy(content = Content(role = Role.MODEL, parts = listOf(Part(text = trimmed))))
    }

    /** The same model response, rewritten as a call to the tool [name]. */
    private fun callTool(response: LlmResponse, name: String) =
        response.copy(content = Content(role = Role.MODEL, parts = listOf(Part(functionCall = FunctionCall(name = name)))))

    /** Picks the one rule that fits the quiz right now, read fresh from the file on every step. */
    private fun tutorRule(invocationId: String): String = when {
        // The summary was already fetched in this turn: only the brief is left to write
        summary.lastInvocationId == invocationId -> TUTOR_AFTER_SUMMARY
        // The brief was given in an earlier turn: answer follow-ups from the results, no tools
        quizOver() -> "$TUTOR_FINISHED\nResults: ${summary.results()}"
        log.pending() != null -> TUTOR_WHEN_WAITING
        log.analyses().size >= QUIZ_LENGTH -> TUTOR_WHEN_DONE
        else -> TUTOR_WHEN_READY + lastResult()
    }

    /** The results were fetched at least once, so the quiz is over for good. */
    private fun quizOver() = summary.lastInvocationId != null

    /**
     * The grader's instruction for the step it is on. While the answer is pending it judges and saves,
     * with a code check as a hint; once saved, it replies, opening with the phrase for the saved verdict.
     */
    private fun graderStep(answer: String): String {
        val pending = log.pending()
        if (pending != null) {
            val check = if (pending.accepts(answer)) "\nCode check: this matches the reference answer, so the verdict is correct." else ""
            return "$GRADER_RULES\nQuestion: ${pending.question}\nReference answer: ${pending.answer}\nStudent's answer: $answer$check"
        }
        val saved = log.lastAnalysis()
        val ending = if (log.analyses().size >= QUIZ_LENGTH) "That was the last one! Say anything to see your results."
        else "Ready for the next question?"
        return "$GRADER_REPLY Start with exactly '${VERDICT_OPENINGS[saved?.verdict]}', " +
            "then explain this tip in your own words: \"${saved?.tip}\", and end with exactly '$ending'. Do not call a tool."
    }

    /** The last graded answer, so the tutor can talk about it if the student asks or disagrees. */
    private fun lastResult(): String {
        val last = log.lastAnalysis() ?: return ""
        return "\nLast result: question \"${last.question}\", verdict ${last.verdict}, tip \"${last.tip}\"."
    }

    private fun text(s: String) = Content(parts = listOf(Part(text = s)))

    override fun close() {
        runner?.close()
        model?.close()
        engine?.close()
        runner = null
        model = null
        engine = null
    }
}
