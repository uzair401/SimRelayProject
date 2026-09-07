package com.simrelay.m0.diagnostics

object DiagnosticEventFormatter {
    fun forLogcat(event: DiagnosticEvent): String = buildString {
        append("event=")
        append(event.name)
        append(" elapsed_ns=")
        append(event.elapsedRealtimeNanos)
        if (event.detail.isNotBlank()) {
            append(" detail=\"")
            append(event.detail.replace("\\", "\\\\").replace("\"", "\\\""))
            append('"')
        }
    }
}
