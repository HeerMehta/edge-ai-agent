package com.example.edgeaiagent.tutor

import org.json.JSONObject
import java.io.File

/** One graded answer, as stored in the analysis file. */
data class Analysis(val n: Int, val id: Int, val level: Int, val question: String, val verdict: String, val tip: String)

/**
 * The tutor's whole memory, kept in two small files instead of the chat history:
 * `analysis.jsonl` (one line per graded answer) and `pending.json` (the question waiting for an answer).
 */
class TutorLog(private val dir: File) {

    private val analysisFile = File(dir, "analysis.jsonl")
    private val pendingFile = File(dir, "pending.json")

    /** Starts a fresh quiz: forgets every answer and the pending question. */
    fun clear() {
        dir.mkdirs()
        analysisFile.delete()
        pendingFile.delete()
    }

    fun appendAnalysis(a: Analysis) {
        val line = JSONObject()
            .put("n", a.n).put("id", a.id).put("level", a.level)
            .put("question", a.question).put("verdict", a.verdict).put("tip", a.tip)
        analysisFile.appendText(line.toString() + "\n")
    }

    fun analyses(): List<Analysis> {
        if (!analysisFile.exists()) return emptyList()
        return analysisFile.readLines().filter { it.isNotBlank() }.map {
            val o = JSONObject(it)
            Analysis(o.getInt("n"), o.getInt("id"), o.getInt("level"), o.getString("question"), o.getString("verdict"), o.getString("tip"))
        }
    }

    /** Reads only the last line, which is all the quizmaster needs to pick the next level. */
    fun lastAnalysis(): Analysis? = analyses().lastOrNull()

    fun pending(): Question? {
        if (!pendingFile.exists()) return null
        val o = JSONObject(pendingFile.readText())
        return Question(o.getInt("id"), o.getInt("level"), o.getString("question"), o.getString("answer"))
    }

    fun setPending(q: Question) {
        val o = JSONObject().put("id", q.id).put("level", q.level).put("question", q.question).put("answer", q.answer)
        pendingFile.writeText(o.toString())
    }

    fun clearPending() {
        pendingFile.delete()
    }
}
