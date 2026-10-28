package com.example.edgeaiagent.tutor

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * The quizmaster's tool. No arguments: it reads the last analysis from the file and
 * picks the next question itself (easier after a wrong answer, harder after a correct one).
 */
class NextQuestionTool(private val log: TutorLog) :
    BaseTool(name = "next_question", description = "Get the next quiz question to ask the student") {

    override fun declaration() = FunctionDeclaration(
        name = name,
        description = description,
        parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
        val answered = log.analyses()
        if (answered.size >= QUIZ_LENGTH) return mapOf("done" to true, "answered" to answered.size)

        val last = answered.lastOrNull()
        // A question is still waiting: ask it again instead of skipping it
        log.pending()?.let { return questionResult(it, answered.size + 1, last, repeated = true) }

        val level = when (last?.verdict) {
            null -> 1
            "wrong" -> last.level - 1
            "correct" -> last.level + 1
            else -> last.level
        }.coerceIn(1, 3)

        val askedIds = answered.map { it.id }.toSet()
        val unasked = QUESTION_BANK.filter { it.id !in askedIds }
        val question = unasked.filter { it.level == level }.randomOrNull() ?: unasked.random()
        log.setPending(question)
        return questionResult(question, answered.size + 1, last, repeated = false)
    }

    /** What the model sees: the question to ask and how the last answer went. Never the answer key. */
    private fun questionResult(q: Question, number: Int, last: Analysis?, repeated: Boolean) = mapOf(
        "number" to number,
        "level" to q.level,
        "question" to q.question,
        "repeated" to repeated,
        "last_verdict" to (last?.verdict ?: "none"),
        "last_tip" to (last?.tip ?: "none"),
    )
}
