package com.simrelay.m0.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFormattingTest {
    @Test
    fun formatsStructuredLogcatEvent() {
        val formatted = DiagnosticEventFormatter.forLogcat(
            DiagnosticEvent(123L, "probe_completed", "backend=framework_interception")
        )

        assertTrue(formatted.startsWith("event=probe_completed elapsed_ns=123"))
        assertTrue(formatted.contains("backend=framework_interception"))
    }

    @Test
    fun redactsSensitiveTelephonyAndAccountValues() {
        val original = "phoneNumber=+15551234567 imsi=310260123456789 user@example.com AA:BB:CC:DD:EE:FF"
        val redacted = SensitiveDiagnosticRedactor.redact(original)

        assertFalse(redacted.contains("+15551234567"))
        assertFalse(redacted.contains("310260123456789"))
        assertFalse(redacted.contains("user@example.com"))
        assertFalse(redacted.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(redacted.contains("<redacted>"))
    }
}
