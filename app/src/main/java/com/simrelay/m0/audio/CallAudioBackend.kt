package com.simrelay.m0.audio

interface DownlinkSession : AutoCloseable {
    val config: PcmConfig
    fun start(): BackendResult<Unit>
    fun read(buffer: ShortArray): BackendResult<Int>
    fun stop()
}

interface UplinkSession : AutoCloseable {
    val config: PcmConfig
    fun start(): BackendResult<Unit>
    fun write(buffer: ShortArray, offset: Int = 0, count: Int = buffer.size): BackendResult<Int>
    fun stop()
}

interface CallAudioBackend : AutoCloseable {
    val id: AudioBackendId
    val requirements: BackendRequirements
    fun probe(): CallAudioCapability
    fun readiness(capability: CallAudioCapability): CallAudioReadiness
    fun openDownlink(config: PcmConfig): BackendResult<DownlinkSession>
    fun openUplink(config: PcmConfig): BackendResult<UplinkSession>
    override fun close()
}
