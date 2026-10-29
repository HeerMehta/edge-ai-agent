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

class TimeTool : BaseTool(name = "time", description = "Get the current date and time on this device") {
    override fun declaration() = FunctionDeclaration(
        name = name,
        description = description,
        parameters = Schema(type = Type.OBJECT, properties = emptyMap()),
    )

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = currentTime()

    private fun currentTime(): Map<String, Any> {
        val zoneId = TimeZone.getDefault().id
        val format = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone(zoneId)
        return mapOf("timezone" to zoneId, "datetime" to format.format(Date()))
    }
}
