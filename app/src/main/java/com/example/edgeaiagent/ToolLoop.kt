package com.example.edgeaiagent

import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall

// WORKSHOP: students edit this
const val TOOL_RULE =
    "You cannot know the current date or time. For ANY question about the date or time, " +
        "you must call the time tool; never guess."

// Hard cap on model calls per user message (a normal tool turn uses 2)
const val MAX_MODEL_CALLS = 3

/**
 * The hand-written tool loop. Blocking: call it from a background dispatcher.
 * Returns the final answer; every step is reported through [onStep].
 */
fun runToolLoop(conv: Conversation, text: String, onStep: (String) -> Unit): String {
    var input = Message.user(text)
    for (call in 1..MAX_MODEL_CALLS) {
        // 1. Send the user text (or, from the 2nd round, the tool results) and block for the reply
        onStep("[$call/$MAX_MODEL_CALLS] model: thinking…")
        val t0 = SystemClock.elapsedRealtime()
        val reply = conv.sendMessage(input)
        onStep("[$call/$MAX_MODEL_CALLS] model replied in ${SystemClock.elapsedRealtime() - t0} ms, ${reply.toolCalls.size} tool call(s)")

        // 2. No tool calls: this is the final answer, we are done
        if (reply.toolCalls.isEmpty()) return reply.text()
        if (call == MAX_MODEL_CALLS) break // cap reached: no point running tools we can't report back

        // 3. Validate and run EACH tool call, collecting one ToolResponse per call
        val responses = reply.toolCalls.map { toolCall ->
            val t1 = SystemClock.elapsedRealtime()
            val result = runTool(toolCall)
            onStep("[$call] tool ${toolCall.name}(${describe(toolCall.arguments)}) -> $result in ${SystemClock.elapsedRealtime() - t1} ms")
            Content.ToolResponse(toolCall.name, result)
        }

        // 4. Next round: send the results back as a tool message, then go to step 2
        input = Message.tool(Contents.of(responses))
    }
    return "Sorry, I could not finish: the model was still asking for tools after $MAX_MODEL_CALLS calls."
}

/** Checks one tool call and runs it. Bad input becomes an error map, never an exception. */
private fun runTool(toolCall: ToolCall): Map<String, Any> {
    if (toolCall.name != "time") return mapOf("error" to "unknown tool '${toolCall.name}'")
    return currentTime() // the tool takes no arguments, so there is nothing else to validate
}

/** Shows each argument with its real runtime type, e.g. a=17 (Double). */
private fun describe(arguments: Map<String, Any?>): String =
    arguments.entries.joinToString { (k, v) -> "$k=$v (${v?.javaClass?.simpleName})" }
