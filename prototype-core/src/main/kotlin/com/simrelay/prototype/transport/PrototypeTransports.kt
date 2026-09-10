package com.simrelay.prototype.transport

import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.protocol.SignalMessage

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Failed
}

data class MediaTransportStats(
    val codec: String? = null,
    val outboundPackets: Long = 0,
    val outboundBytes: Long = 0,
    val inboundPackets: Long = 0,
    val inboundBytes: Long = 0,
    val submittedFrames: Long = 0,
    val deliveredFrames: Long = 0,
    val droppedFrames: Long = 0
)

interface SignalingTransport : AutoCloseable {
    val state: ConnectionState
    fun connect()
    fun send(message: SignalMessage): Boolean
    fun setMessageListener(listener: ((SignalMessage) -> Unit)?)
    fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?)
    override fun close()
}

interface MediaTransport : AutoCloseable {
    val state: ConnectionState
    val pcmFormat: PrototypeAudioFormat
    fun start(sessionId: String, initiator: Boolean)
    fun handleSignal(message: SignalMessage)
    fun send(frame: PrototypeAudioFrame): Boolean
    fun stop(reason: String)
    fun requestStats(callback: (MediaTransportStats) -> Unit)
    fun setFrameListener(listener: ((PrototypeAudioFrame) -> Unit)?)
    fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?)
    override fun close()
}
