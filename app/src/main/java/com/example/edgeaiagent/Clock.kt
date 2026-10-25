package com.example.edgeaiagent

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Plain Kotlin clock: no framework types, easy to reuse and unit-test. Defaults to the phone's own timezone. */
fun currentTime(zoneId: String = TimeZone.getDefault().id): Map<String, Any> {
    val format = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm:ss", Locale.US)
    format.timeZone = TimeZone.getTimeZone(zoneId)
    return mapOf("timezone" to zoneId, "datetime" to format.format(Date()))
}

/**
 * The tool the model sees. It takes NO arguments on purpose: a small model can garble
 * argument syntax (a string like Asia/Tokyo broke the library's parser), and there is nothing to garble here.
 * The method name is one word on purpose: the library may rename camelCase names to snake_case.
 */
class ClockToolSet : ToolSet {
    @Tool(description = "Get the current date and time on this device")
    fun time(): Map<String, Any> = currentTime()
}
