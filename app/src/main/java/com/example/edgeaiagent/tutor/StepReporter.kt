package com.example.edgeaiagent.tutor

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.types.Role

class StepReporter : Plugin {
    override val name = "step_reporter"

    @Volatile var onStep: (String) -> Unit = {}

    @Volatile private var lastAuthor = ""

    override suspend fun onEvent(invocationContext: InvocationContext, event: Event): Event {
        val author = event.author
        if (author != Role.USER && author != lastAuthor) {
            lastAuthor = author
            onStep("agent: $author")
        }
        event.content?.text()?.takeIf { it.isNotBlank() }?.let { onStep("$author says: ${it.take(200)}") }
        event.functionCalls().forEach { onStep("$author → ${it.name}(${it.args})") }
        event.functionResponses().forEach { onStep("${it.name} -> ${it.response}") }
        event.errorMessage?.let { onStep("error: $it") }
        return event
    }

    fun reset(onStep: (String) -> Unit) {
        this.onStep = onStep
        lastAuthor = ""
    }
}
