package com.simrelay.m0.diagnostics

import android.content.Context
import android.media.AudioManager
import com.simrelay.m0.audio.BackendSelection
import com.simrelay.m0.audio.CallAudioBackendFactory
import com.simrelay.m0.call.CallState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class CapabilityProbe(private val context: Context) {
    fun run(callState: CallState): Pair<DiagnosticReport, BackendSelection> {
        val initialSelection = CallAudioBackendFactory(context).probeAndSelect()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val now = System.currentTimeMillis()
        val device = DeviceSnapshot.capture()
        val permissions = PermissionSnapshot.capture(context)
        val readiness = CallSessionReadinessEvaluator.evaluate(
            initialSelection.capability,
            initialSelection.readiness,
            callState
        )
        val selection = initialSelection.copy(readiness = readiness)
        val application = ApplicationSnapshot.capture(context, permissions)
        val report = DiagnosticReport(
            runId = runId(now),
            capturedAtEpochMillis = now,
            device = device,
            application = application,
            permissions = permissions,
            audioSystem = AudioSystemSnapshot.capture(audioManager),
            backendReports = selection.reports,
            selectedBackend = selection.backend.id,
            readiness = selection.readiness,
            callState = callState,
            qualification = DeviceQualificationProfileFactory.create(
                device,
                permissions,
                selection.capability,
                selection.readiness,
                selection.backend.id
            ),
            provisioning = ProvisioningPathSnapshot.capture(context),
            events = emptyList()
        )
        return report to selection
    }

    private fun runId(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return "${formatter.format(Date(timestamp))}-${UUID.randomUUID()}"
    }
}
