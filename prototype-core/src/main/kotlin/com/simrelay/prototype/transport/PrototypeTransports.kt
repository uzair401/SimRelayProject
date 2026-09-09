package com.simrelay.prototype.transport

import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Failed
}

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
    fun start(sessionId: String, initiator: Boolean)
    fun handleSignal(message: SignalMessage)
    fun send(frame: PrototypeAudioFrame): Boolean
    fun stop(reason: String)
    fun setFrameListener(listener: ((PrototypeAudioFrame) -> Unit)?)
    fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?)
    override fun close()
}
