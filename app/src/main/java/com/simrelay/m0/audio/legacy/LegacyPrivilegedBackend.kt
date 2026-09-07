package com.simrelay.m0.audio.legacy

import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.AudioOperation
import com.simrelay.m0.audio.BackendResult
import com.simrelay.m0.audio.BackendRequirements
import com.simrelay.m0.audio.CallAudioBackend
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.DownlinkSession
import com.simrelay.m0.audio.PcmConfig
import com.simrelay.m0.audio.SessionReadinessState
import com.simrelay.m0.audio.UplinkSession

class LegacyPrivilegedBackend : CallAudioBackend {
    override val id = AudioBackendId.LegacyPrivileged
    override val requirements = BackendRequirements.None

    override fun probe() = unavailable()

    override fun readiness(capability: CallAudioCapability) = CallAudioReadiness(
        id,
        SessionReadinessState.BackendUnavailable,
        capability.message
    )

    override fun openDownlink(config: PcmConfig): BackendResult<DownlinkSession> =
        BackendResult.Failure(unavailable(AudioOperation.OpenDownlink))

    override fun openUplink(config: PcmConfig): BackendResult<UplinkSession> =
        BackendResult.Failure(unavailable(AudioOperation.OpenUplink))

    override fun close() = Unit

    private fun unavailable(operation: AudioOperation = AudioOperation.CapabilityProbe) = CallAudioCapability(
        id,
        CapabilityState.NotImplemented,
        "Legacy privileged backend is not implemented in Phase 0A",
        operation = operation
    )
}
