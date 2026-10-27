package com.example.edgeaiagent.adk

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The tool the model sees, written out by hand (no @Tool annotation, so no KSP code generator needed).
 * It takes NO arguments on purpose: a small model can garble argument syntax, and there is nothing to garble here.
 */
class TimeTool : BaseTool(name = "time", description = "Get the current date and time on this device") {

    /** What the model is told about the tool: its name, what it does, and that it takes no arguments. */
    override fun declaration() = FunctionDeclaration(
        name = name,
        description = description,
        parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )

    /** ADK calls this when the model asks for the tool; the returned map goes back to the model. */
    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = currentTime()

    /** Plain Kotlin clock in the phone's own timezone. */
    private fun currentTime(): Map<String, Any> {
        val zoneId = TimeZone.getDefault().id
        val format = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone(zoneId)
        return mapOf("timezone" to zoneId, "datetime" to format.format(Date()))
    }
}
