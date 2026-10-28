package com.example.edgeaiagent.tutor

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * ADK's [AgentTool] (an agent the parent can call like a tool), minus its one argument.
 * The plain AgentTool makes the model write a `request` string; a small model can garble it,
 * so here the model just calls the agent by name and we hand it the message ourselves.
 * [isAvailable] hides the tool from the model whenever calling it would make no sense.
 */
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

    /** ADK asks every tool, before every model call, to add itself to the request; skip that when unavailable. */
    override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest): LlmRequest =
        if (isAvailable()) super.processLlmRequest(toolContext, llmRequest) else llmRequest

    /**
     * ADK still runs a hidden tool if the model names it anyway (it read the name in the prompt),
     * so refuse here too. The refusal does not end the turn, so the tutor gets to pick again.
     */
    override suspend fun run(context: ToolContext, args: Map<String, Any?>): String =
        if (isAvailable()) super.run(context, mapOf("request" to message(context)))
        else "$name cannot be used right now. Pick one of your other tools."
}
