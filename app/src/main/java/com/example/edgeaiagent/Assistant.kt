package com.example.edgeaiagent

const val TAG = "HelloAgent"

// WORKSHOP: students edit this
const val SYSTEM_PROMPT =
    "You are Buddy, a warm and easygoing friend to chat with. " +
        "Keep replies short and natural, like texting a friend. " +
        "You can also tell people the current time."

// WORKSHOP: students edit this
const val TOOL_RULE =
    "You cannot know the current date or time. For ANY question about the date or time, " +
        "you must call the time tool; never guess."

/** Everything the chat screen needs from "the brain" behind it. */
interface Assistant {
    /** Slow (it loads the model), so call it from a background dispatcher. Returns a label for the status line. */
    suspend fun start(): String

    /** Returns the final answer; every step along the way is reported through [onStep]. */
    suspend fun chat(text: String, onStep: (String) -> Unit): String

    fun close()
}

/** Thrown by [Assistant.start] when the model file is not on the phone yet; carries the command that fixes it. */
class ModelMissingException(val pushCommand: String) : Exception()

/** The starter: no AI at all, it just repeats what the user typed. */
class EchoAssistant : Assistant {
    override suspend fun start(): String = "Echo"

    override suspend fun chat(text: String, onStep: (String) -> Unit): String = text

    override fun close() {}
}
