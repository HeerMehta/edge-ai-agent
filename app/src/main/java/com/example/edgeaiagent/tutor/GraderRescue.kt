package com.example.edgeaiagent.tutor

import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role

private val VERDICT = Regex("""verdict\s*[:=]\s*["']?(correct|partly|wrong)""", RegexOption.IGNORE_CASE)
// tip="…" as in a broken call, or tip: … to the end of the line as in plain text
private val TIP = Regex("""tip\s*[:=]\s*(?:"([^"]*)"|([^\n]+))""", RegexOption.IGNORE_CASE)

/**
 * Safety nets for save_analysis(verdict, tip), the one tool the model must write arguments for.
 * A small model sometimes breaks it in two ways; both are turned back into a proper tool call,
 * so the tool still does the saving and the trace still shows it.
 */
class GraderRescue(private val log: TutorLog, private val report: (String) -> Unit) {

    /**
     * The model wrote the call in a syntax LiteRT-LM cannot parse, so the model call itself fails.
     * The error message still carries the model's full output; rebuild the call from it.
     */
    fun onModelError(error: Throwable): CallbackChoice<Unit, LlmResponse> {
        val message = error.message.orEmpty()
        if ("save_analysis" !in message) return CallbackChoice.Continue(Unit)
        val verdict = VERDICT.find(message)?.groupValues?.get(1)?.lowercase() ?: return CallbackChoice.Continue(Unit)
        val tip = findTip(message).orEmpty()
        report("rescue: grader's save_analysis call could not be parsed, rebuilding it")
        return CallbackChoice.Break(saveCall(verdict, tip))
    }

    /** The model wrote its verdict as plain text instead of calling the tool; read it and make the call. */
    fun afterModel(response: LlmResponse): LlmResponse {
        if (response.partial || log.pending() == null) return response
        if (response.content?.parts.orEmpty().any { it.functionCall != null }) return response

        val text = response.content?.text().orEmpty().trim()
        val verdict = VERDICT.find(text)?.groupValues?.get(1)?.lowercase() ?: verdictFromOpening(text) ?: return response
        // No "tip:" in the text: the reference answer is a safer tip than the model's whole reply
        val tip = findTip(text) ?: "The answer is: ${log.pending()?.answer}"
        report("rescue: grader wrote its verdict as text, calling save_analysis")
        return saveCall(verdict, tip)
    }

    private fun findTip(text: String): String? =
        TIP.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()

    /** The grader is told to open with one of three phrases; map them back to a verdict. */
    private fun verdictFromOpening(text: String): String? {
        val t = text.lowercase()
        return when {
            t.startsWith("you are right") || t.startsWith("you're right") || t.startsWith("correct") -> "correct"
            t.startsWith("almost") || "partly" in t -> "partly"
            t.startsWith("not quite") || "incorrect" in t || "wrong" in t -> "wrong"
            else -> null
        }
    }

    private fun saveCall(verdict: String, tip: String) = LlmResponse(
        content = Content(
            role = Role.MODEL,
            parts = listOf(Part(functionCall = FunctionCall(name = "save_analysis", args = mapOf("verdict" to verdict, "tip" to tip)))),
        )
    )
}
