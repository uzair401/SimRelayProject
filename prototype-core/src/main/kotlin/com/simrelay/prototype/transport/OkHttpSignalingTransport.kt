package com.simrelay.prototype.transport

import com.simrelay.prototype.protocol.SignalCodec
import com.simrelay.prototype.protocol.SignalDecodeResult
import com.simrelay.prototype.protocol.SignalMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicReference

class OkHttpSignalingTransport(
    private val serverUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : SignalingTransport {
    private val currentState = AtomicReference(ConnectionState.Disconnected)
    private val socket = AtomicReference<WebSocket?>(null)
    private val lock = Any()
    private var messageListener: ((SignalMessage) -> Unit)? = null
    private var stateListener: ((ConnectionState, String?) -> Unit)? = null

    override val state: ConnectionState
        get() = currentState.get()

    override fun connect() {
        if (!currentState.compareAndSet(ConnectionState.Disconnected, ConnectionState.Connecting) &&
            !currentState.compareAndSet(ConnectionState.Failed, ConnectionState.Connecting)
        ) return
        notifyState(ConnectionState.Connecting, null)
        val request = try {
            Request.Builder().url(serverUrl).build()
        } catch (exception: IllegalArgumentException) {
            notifyState(ConnectionState.Failed, "Invalid signaling URL")
            return
        }
        socket.set(client.newWebSocket(request, Listener()))
    }

    override fun send(message: SignalMessage): Boolean {
        if (state != ConnectionState.Connected) return false
        return try {
            socket.get()?.send(SignalCodec.encode(message)) == true
        } catch (exception: IllegalArgumentException) {
            false
        }
    }

    override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) {
        synchronized(lock) { messageListener = listener }
    }

    override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) {
        synchronized(lock) { stateListener = listener }
    }

    override fun close() {
        socket.getAndSet(null)?.close(1000, "closed")
        notifyState(ConnectionState.Disconnected, null)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun notifyState(state: ConnectionState, detail: String?) {
        currentState.set(state)
        synchronized(lock) { stateListener }?.invoke(state, detail)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket.set(webSocket)
            notifyState(ConnectionState.Connected, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val decoded = SignalCodec.decode(text)) {
                is SignalDecodeResult.Success -> synchronized(lock) { messageListener }?.invoke(decoded.message)
                is SignalDecodeResult.Failure -> synchronized(lock) { stateListener }
                    ?.invoke(ConnectionState.Connected, decoded.reason)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket.compareAndSet(webSocket, null)
            notifyState(ConnectionState.Disconnected, null)
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            socket.compareAndSet(webSocket, null)
            notifyState(ConnectionState.Failed, throwable.message ?: "Signaling connection failed")
        }
    }
}
