package com.example.edgeaiagent.adk

import android.content.Context
import android.util.Log
import com.example.edgeaiagent.Assistant
import com.example.edgeaiagent.SYSTEM_PROMPT
import com.example.edgeaiagent.TAG
import com.example.edgeaiagent.TOOL_RULE
import com.example.edgeaiagent.loadEngine
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.ai.edge.litertlm.Engine

private const val APP_NAME = "buddy"
private const val USER_ID = "user"
private const val SESSION_ID = "chat"

// Hard cap on model calls per user message (a normal tool turn uses 2)
private const val MAX_STEPS = 3

/**
 * The same on-device model, but ADK runs the agent loop for us:
 * it calls the model, runs the tools it asks for, and sends the results back until there is an answer.
 */
class AdkAssistant(private val context: Context) : Assistant {

    private var engine: Engine? = null
    private var model: LiteRtLmModel? = null
    private var runner: InMemoryRunner? = null

    /** Loads the model and builds the agent. Returns the backend in use ("GPU" or "CPU"). */
    override suspend fun start(): String {
        val (eng, backendName) = loadEngine(context)
        val llm = LiteRtLmModel.create(eng)

        val agent = LlmAgent(
            name = "buddy",
            model = llm,
            instruction = Instruction("$SYSTEM_PROMPT $TOOL_RULE"),
            tools = listOf(TimeTool()),
            maxSteps = MAX_STEPS,
        )
        val r = InMemoryRunner(agent = agent, appName = APP_NAME)
        // ONE session for the whole app run: ADK keeps the chat history in it
        r.sessionService.createSession(SessionKey(APP_NAME, USER_ID, SESSION_ID))

        engine = eng
        model = llm
        runner = r
        Log.i(TAG, "Backend in use: $backendName (via ADK)")
        return "$backendName · ADK"
    }

    override suspend fun chat(text: String, onStep: (String) -> Unit): String {
        val r = checkNotNull(runner) { "Model not loaded yet" }
        onStep("model: thinking…")

        var answer = ""
        // ADK reports every step of its loop as an Event: tool calls, tool results, then the final answer
        r.runAsync(userId = USER_ID, sessionId = SESSION_ID, newMessage = Content.fromText(Role.USER, text))
            .collect { event ->
                event.functionCalls().forEach { onStep("tool call: ${it.name}(${it.args})") }
                event.functionResponses().forEach { onStep("tool ${it.name} -> ${it.response}") }
                event.errorMessage?.let { onStep("error: $it") }
                if (event.isFinalResponse) answer = event.content?.text().orEmpty()
            }
        return answer.ifEmpty { "Sorry, I could not finish: no answer after $MAX_STEPS steps." }
    }

    override fun close() {
        runner?.close()
        model?.close() // closes the conversation; the engine is ours to close
        engine?.close()
        runner = null
        model = null
        engine = null
    }
}
