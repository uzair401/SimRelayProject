package com.simrelay.m0.audio

class UnsupportedBackend(private val reason: CallAudioCapability) : CallAudioBackend {
    override val id = AudioBackendId.Unsupported
    override val requirements = BackendRequirements.None

    override fun probe() = reason.copy(backendId = id)

    override fun readiness(capability: CallAudioCapability) = CallAudioReadiness(
        id,
        SessionReadinessState.BackendUnavailable,
        capability.message
    )

    override fun openDownlink(config: PcmConfig): BackendResult<DownlinkSession> =
        BackendResult.Failure(probe().copy(operation = AudioOperation.OpenDownlink))

    override fun openUplink(config: PcmConfig): BackendResult<UplinkSession> =
        BackendResult.Failure(probe().copy(operation = AudioOperation.OpenUplink))

    override fun close() = Unit
}
