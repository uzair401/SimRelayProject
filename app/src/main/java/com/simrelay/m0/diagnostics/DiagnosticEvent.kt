package com.simrelay.m0.diagnostics

import com.simrelay.m0.util.JsonText

data class DiagnosticEvent(
    val elapsedRealtimeNanos: Long,
    val name: String,
    val detail: String
) {
    fun toJson(): String = JsonText.obj(
        "elapsedRealtimeNanos" to elapsedRealtimeNanos.toString(),
        "name" to JsonText.string(name),
        "detail" to JsonText.string(detail)
    )

    fun toLogLine(): String = "$elapsedRealtimeNanos $name $detail"
}

fun interface MonotonicClock {
    fun elapsedRealtimeNanos(): Long
}
