package com.simrelay.m0.diagnostics

import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.FrameworkMethodAccess
import com.simrelay.m0.call.CallState
import com.simrelay.m0.pcm.PcmMetricSummary
import com.simrelay.m0.util.JsonText

data class DiagnosticReport(
    val runId: String,
    val capturedAtEpochMillis: Long,
    val device: DeviceSnapshot,
    val application: ApplicationSnapshot,
    val permissions: PermissionSnapshot,
    val audioSystem: AudioSystemSnapshot,
    val backendReports: List<CallAudioCapability>,
    val selectedBackend: AudioBackendId,
    val readiness: CallAudioReadiness,
    val callState: CallState,
    val qualification: DeviceQualificationProfile,
    val provisioning: ProvisioningPathSnapshot,
    val metrics: PcmMetricSummary? = null,
    val events: List<DiagnosticEvent> = emptyList()
) {
    fun probeJson(): String = JsonText.obj(
        "runId" to JsonText.string(runId),
        "capturedAtEpochMillis" to capturedAtEpochMillis.toString(),
        "selectedBackend" to JsonText.string(selectedBackend.value),
        "readiness" to JsonText.obj(
            "state" to JsonText.string(readiness.state.name),
            "message" to JsonText.string(readiness.message),
            "audioMode" to (readiness.audioMode?.toString() ?: "null")
        ),
        "callState" to JsonText.string(callState.name),
        "provisioning" to provisioning.toJson(),
        "backends" to JsonText.array(backendReports.map(::capabilityJson)),
        "events" to JsonText.array(events.map(DiagnosticEvent::toJson))
    )

    fun metricsJson(): String = metrics?.let { summary ->
        JsonText.obj(
            "sampleCount" to summary.sampleCount.toString(),
            "sampleRateHz" to summary.sampleRateHz.toString(),
            "durationMillis" to summary.durationMillis.toString(),
            "rms" to summary.rms.toString(),
            "peakAbsolute" to summary.peakAbsolute.toString(),
            "isExactZero" to summary.isExactZero.toString(),
            "isBelowSilenceThreshold" to summary.isBelowSilenceThreshold.toString()
        )
    } ?: JsonText.obj("available" to "false")

    private fun capabilityJson(capability: CallAudioCapability): String = JsonText.obj(
        "backend" to JsonText.string(capability.backendId.value),
        "state" to JsonText.string(capability.state.name),
        "message" to JsonText.string(capability.message),
        "exceptionClass" to JsonText.string(capability.exceptionClass),
        "operation" to JsonText.string(capability.operation.name),
        "pstnInterceptable" to (capability.pstnInterceptable?.toString() ?: "null"),
        "apiPresence" to JsonText.obj(
            "interceptability" to methodAccessJson(capability.apiPresence.interceptability),
            "downlinkExtraction" to methodAccessJson(capability.apiPresence.downlinkExtraction),
            "uplinkInjection" to methodAccessJson(capability.apiPresence.uplinkInjection)
        )
    )

    private fun methodAccessJson(access: FrameworkMethodAccess): String = JsonText.obj(
        "access" to JsonText.string(access.access.name),
        "exceptionClass" to JsonText.string(access.exceptionClass),
        "message" to JsonText.string(access.message)
    )
}
