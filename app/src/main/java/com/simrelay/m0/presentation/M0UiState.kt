package com.simrelay.m0.presentation

import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.FrameworkApiPresence
import com.simrelay.m0.audio.SessionState
import com.simrelay.m0.call.CallState
import com.simrelay.m0.diagnostics.ProvisioningRoute

data class M0UiState(
    val deviceModel: String,
    val androidVersion: String,
    val buildFingerprint: String,
    val selectedBackend: AudioBackendId = AudioBackendId.Unsupported,
    val capabilityState: String = "Unknown",
    val readiness: String = "Unknown",
    val callAudioInterceptionGranted: Boolean = false,
    val provisioningRoute: String = ProvisioningRoute.Unknown.name,
    val provisioningSummary: String? = null,
    val frameworkApiPresence: FrameworkApiPresence = FrameworkApiPresence.Unknown,
    val pstnInterceptable: Boolean? = null,
    val callState: CallState = CallState.Unknown,
    val audioMode: String,
    val sessionState: SessionState = SessionState.Idle,
    val captureState: String = "Stopped",
    val injectionState: String = "Stopped",
    val lastError: String? = null,
    val latestRms: Double? = null,
    val latestPeak: Int? = null,
    val outputPath: String? = null,
    val capabilitySupported: Boolean = false
)
