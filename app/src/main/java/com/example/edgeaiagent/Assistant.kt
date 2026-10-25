package com.example.edgeaiagent

const val TAG = "HelloAgent"

/** Everything the chat screen needs from "the brain" behind it. */
interface Assistant {
    /** Slow (it loads the model), so call it from a background thread. Returns a label for the status line. */
    fun start(): String

    /** Blocking. Returns the final answer; every step along the way is reported through [onStep]. */
    fun chat(text: String, onStep: (String) -> Unit): String

    fun close()
}

/** Thrown by [Assistant.start] when the model file is not on the phone yet; carries the command that fixes it. */
class ModelMissingException(val pushCommand: String) : Exception()

/** The starter: no AI at all, it just repeats what the user typed. */
class EchoAssistant : Assistant {
    override fun start(): String = "Echo"

    override fun chat(text: String, onStep: (String) -> Unit): String = text

    override fun close() {}
}
