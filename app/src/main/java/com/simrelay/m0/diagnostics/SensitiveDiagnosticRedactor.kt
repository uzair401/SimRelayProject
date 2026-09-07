package com.simrelay.m0.diagnostics

object SensitiveDiagnosticRedactor {
    private val sensitiveAssignment = Regex(
        "(?i)\\b(phone(?:number)?|incoming(?:number)?|subscriber(?:id)?|imsi|iccid|msisdn|line1(?:number)?|account(?:id|handle)?|address)\\s*[:=]\\s*([^\\s,;]+)"
    )
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val macAddress = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val longIdentifier = Regex("(?<![A-Za-z0-9])[0-9]{13,20}(?![A-Za-z0-9])")

    fun redact(value: String): String = value
        .replace(sensitiveAssignment) { match -> "${match.groupValues[1]}=<redacted>" }
        .replace(email, "<redacted-email>")
        .replace(macAddress, "<redacted-mac>")
        .replace(longIdentifier, "<redacted-identifier>")
}
