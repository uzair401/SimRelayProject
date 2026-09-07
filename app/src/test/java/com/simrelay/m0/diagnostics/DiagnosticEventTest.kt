package com.simrelay.m0.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventTest {
    @Test
    fun retainsMonotonicTimestampInJsonAndLogText() {
        val event = DiagnosticEvent(4_321_000L, "first_rx_sample", "attempt=one")

        assertTrue(event.toJson().contains("\"elapsedRealtimeNanos\": 4321000"))
        assertTrue(event.toLogLine().startsWith("4321000 "))
    }
}
