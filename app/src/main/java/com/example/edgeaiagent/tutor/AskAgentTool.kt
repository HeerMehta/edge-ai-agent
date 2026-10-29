package com.example.edgeaiagent.tutor

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

class AskAgentTool(
    agent: BaseAgent,
    skipSummarization: Boolean = false,
    private val isAvailable: () -> Boolean = { true },
    private val message: (ToolContext) -> String,
) : AgentTool(agent = agent, skipSummarization = skipSummarization) {
    override fun declaration() = FunctionDeclaration(
        name = name,
        description = description,
        parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )

    override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest =
        if (isAvailable()) super.processLlmRequest(toolContext, llmRequest) else llmRequest

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): String =
        if (isAvailable()) super.run(context, mapOf("request" to message(context)))
        else "$name cannot be used right now. Pick one of your other tools."
}
