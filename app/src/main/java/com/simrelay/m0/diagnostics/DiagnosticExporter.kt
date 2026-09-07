package com.simrelay.m0.diagnostics

import android.content.Context
import java.io.File

class DiagnosticExporter(private val context: Context) {
    val rootDirectory: File
        get() = File(context.filesDir, "diagnostics")

    fun export(report: DiagnosticReport, existingRunDirectory: File? = null): File {
        val runDirectory = existingRunDirectory ?: File(
            rootDirectory,
            report.runId
        )
        check(runDirectory.exists() || runDirectory.mkdirs()) {
            "Unable to create diagnostics directory"
        }
        File(runDirectory, "device.json").writeText(report.device.toJson())
        File(runDirectory, "application.json").writeText(report.application.toJson())
        File(runDirectory, "permissions.json").writeText(report.permissions.toJson())
        File(runDirectory, "audio-devices.json").writeText(report.audioSystem.toJson())
        File(runDirectory, "probe.json").writeText(report.probeJson())
        File(runDirectory, "metrics.json").writeText(report.metricsJson())
        File(runDirectory, "qualification.json").writeText(report.qualification.toJson())
        File(runDirectory, "app.log").writeText(
            report.events.joinToString(separator = "\n", postfix = "\n", transform = DiagnosticEvent::toLogLine)
        )
        return runDirectory
    }
}
