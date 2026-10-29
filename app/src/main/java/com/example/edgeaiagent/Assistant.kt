package com.example.edgeaiagent

const val TAG = "HelloAgent"

const val SYSTEM_PROMPT =
    "You are Buddy, a warm and easygoing friend to chat with. " +
        "Keep replies short and natural, like texting a friend. " +
        "You can also tell people the current time."

const val TOOL_RULE =
    "You cannot know the current date or time. For ANY question about the date or time, " +
        "you must call the time tool; never guess."

interface Assistant {
    suspend fun start(): String

    suspend fun chat(text: String, onStep: (String) -> Unit): String

    fun close()
}

class ModelMissingException(val pushCommand: String) : Exception()

class EchoAssistant : Assistant {
    override suspend fun start(): String = "Echo"

    override suspend fun chat(text: String, onStep: (String) -> Unit): String = text

    override fun close() {}
}
