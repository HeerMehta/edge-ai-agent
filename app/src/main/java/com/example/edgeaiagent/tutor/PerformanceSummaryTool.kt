package com.example.edgeaiagent.tutor

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/** The tutor's own tool: plain Kotlin counts over the whole analysis file, so the model only has to phrase the brief. */
class PerformanceSummaryTool(private val log: TutorLog) :
    BaseTool(name = "performance_summary", description = "Get the student's overall quiz results") {

    override fun declaration() = FunctionDeclaration(
        name = name,
        description = description,
        parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )

    /** The turn (ADK invocation) this tool last ran in, so the tutor knows the brief is all that is left. */
    @Volatile var lastInvocationId: String? = null
        private set

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
        lastInvocationId = context.invocationId
        return results()
    }

    /** The counts themselves; also read directly once the quiz is over, so follow-up questions need no tool. */
    fun results(): Map<String, Any> {
        val all = log.analyses()
        val correct = all.count { it.verdict == "correct" }
        val partly = all.count { it.verdict == "partly" }
        // Partly-correct answers count as half a point
        val score = if (all.isEmpty()) 0 else ((correct + partly * 0.5) * 100 / all.size).toInt()
        return mapOf(
            "answered" to all.size,
            "correct" to correct,
            "partly" to partly,
            "wrong" to all.count { it.verdict == "wrong" },
            "score_percent" to score,
            "highest_level" to (all.maxOfOrNull { it.level } ?: 0),
            // Kept apart so the brief never turns a correct answer's tip into something to practise
            "answered_right" to all.filter { it.verdict == "correct" }.map { it.question },
            "to_practise" to all.filter { it.verdict != "correct" }.map { it.tip },
        )
    }
}
