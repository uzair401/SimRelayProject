package com.simrelay.prototype.transport

import com.simrelay.prototype.pairing.DirectPairingPayload
import com.simrelay.prototype.protocol.SignalCodec
import com.simrelay.prototype.protocol.SignalDecodeResult
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.protocol.SignalValidator
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface DirectPeerConfiguration {
    data class Host(
        val port: Int,
        val pairingToken: String,
        val expiresAtMillis: Long
    ) : DirectPeerConfiguration {
        init {
            require(port in 1..65_535)
            require(pairingToken.length in 6..128 && pairingToken.none(Char::isWhitespace))
            require(expiresAtMillis > 0)
        }
    }

    data class Client(
        val payload: DirectPairingPayload
    ) : DirectPeerConfiguration
}

class DirectPeerSignalingTransport(
    private val configuration: DirectPeerConfiguration,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newCachedThreadPool()
) : SignalingTransport {
    private val currentState = AtomicReference(ConnectionState.Disconnected)
    private val closed = AtomicBoolean(false)
    private val paired = AtomicBoolean(false)
    private val credentialUsed = AtomicBoolean(false)
    private val clientRegistered = AtomicBoolean(false)
    private val serverSocket = AtomicReference<ServerSocket?>(null)
    private val peerSocket = AtomicReference<Socket?>(null)
    private val writer = AtomicReference<BufferedWriter?>(null)
    private val listenerLock = Any()
    private val writeLock = Any()
    private var messageListener: ((SignalMessage) -> Unit)? = null
    private var stateListener: ((ConnectionState, String?) -> Unit)? = null

    override val state: ConnectionState
        get() = currentState.get()

    override fun connect() {
        if (!currentState.compareAndSet(ConnectionState.Disconnected, ConnectionState.Connecting) &&
            !currentState.compareAndSet(ConnectionState.Failed, ConnectionState.Connecting)
        ) return
        closed.set(false)
        notifyState(ConnectionState.Connecting, null)
        runBackground {
            when (val value = configuration) {
                is DirectPeerConfiguration.Host -> listen(value)
                is DirectPeerConfiguration.Client -> connectToHost(value)
            }
        }
    }

    override fun send(message: SignalMessage): Boolean {
        if (SignalValidator.validate(message) != null || state != ConnectionState.Connected) return false
        if (configuration is DirectPeerConfiguration.Host && message.messageType == SignalMessageType.HostOnline) {
            return message.payload["pairing_code"] == configuration.pairingToken &&
                message.payload["expires_at_ms"]?.toLongOrNull() == configuration.expiresAtMillis
        }
        val permitted = when (configuration) {
            is DirectPeerConfiguration.Host -> paired.get()
            is DirectPeerConfiguration.Client -> paired.get() || message.messageType in setOf(
                SignalMessageType.ClientOnline,
                SignalMessageType.PairRequest
            )
        }
        return permitted && writeMessage(message)
    }

    override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) {
        synchronized(listenerLock) { messageListener = listener }
    }

    override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) {
        synchronized(listenerLock) { stateListener = listener }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (paired.getAndSet(false)) {
            writeMessageUnchecked(
                SignalMessage(
                    messageType = SignalMessageType.PeerDisconnected,
                    payload = mapOf("reason" to "peer_disconnected")
                )
            )
        }
        closePeer()
        runCatching { serverSocket.getAndSet(null)?.close() }
        executor.shutdownNow()
        notifyState(ConnectionState.Disconnected, null)
    }

    private fun listen(host: DirectPeerConfiguration.Host) {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host.port))
            }
        } catch (throwable: Throwable) {
            fail("Direct signaling listen failed", throwable)
            return
        }
        serverSocket.set(server)
        notifyState(ConnectionState.Connecting, "listening")
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (exception: SocketException) {
                if (!closed.get()) fail("Direct signaling accept failed", exception)
                return
            } catch (throwable: Throwable) {
                fail("Direct signaling accept failed", throwable)
                return
            }
            if (closed.get()) {
                runCatching { socket.close() }
                return
            }
            preparePeer(socket)
            notifyState(ConnectionState.Connected, null)
            readPeer(socket)
            closePeer(socket)
            if (!closed.get() && state != ConnectionState.Failed) notifyState(ConnectionState.Connecting, null)
        }
    }

    private fun connectToHost(client: DirectPeerConfiguration.Client) {
        if (client.payload.expiresAtMillis <= nowMillis()) {
            fail("Direct pairing payload is expired", null)
            return
        }
        val socket = try {
            Socket().apply {
                connect(InetSocketAddress(client.payload.host, client.payload.port), ConnectTimeoutMillis)
            }
        } catch (throwable: Throwable) {
            fail("Direct signaling connection failed", throwable)
            return
        }
        if (closed.get()) {
            runCatching { socket.close() }
            return
        }
        preparePeer(socket)
        notifyState(ConnectionState.Connected, null)
        readPeer(socket)
        closePeer(socket)
        if (!closed.get() && state != ConnectionState.Failed) notifyState(ConnectionState.Disconnected, null)
    }

    private fun preparePeer(socket: Socket) {
        socket.tcpNoDelay = true
        peerSocket.set(socket)
        writer.set(BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)))
        paired.set(false)
        clientRegistered.set(false)
    }

    private fun readPeer(socket: Socket) {
        val reader = try {
            BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        } catch (throwable: Throwable) {
            fail("Direct signaling input failed", throwable)
            return
        }
        try {
            while (!closed.get() && peerSocket.get() === socket) {
                val line = reader.readLine() ?: break
                if (line.length > MaximumMessageCharacters) {
                    notifyDetail("Direct signaling message is too large")
                    continue
                }
                when (val decoded = SignalCodec.decode(line)) {
                    is SignalDecodeResult.Success -> receive(decoded.message)
                    is SignalDecodeResult.Failure -> notifyDetail(decoded.reason)
                }
            }
        } catch (exception: SocketException) {
            if (!closed.get()) notifyDetail("Direct signaling connection closed")
        } catch (throwable: Throwable) {
            if (!closed.get()) fail("Direct signaling receive failed", throwable)
        } finally {
            if (!closed.get() && paired.getAndSet(false)) {
                notifyMessage(
                    SignalMessage(
                        messageType = SignalMessageType.PeerDisconnected,
                        payload = mapOf("reason" to "peer_disconnected")
                    )
                )
            }
        }
    }

    private fun receive(message: SignalMessage) {
        when (configuration) {
            is DirectPeerConfiguration.Host -> receiveAsHost(message, configuration)
            is DirectPeerConfiguration.Client -> receiveAsClient(message)
        }
    }

    private fun receiveAsHost(message: SignalMessage, host: DirectPeerConfiguration.Host) {
        when (message.messageType) {
            SignalMessageType.ClientOnline -> clientRegistered.set(true)
            SignalMessageType.PairRequest -> pairClient(message, host)
            SignalMessageType.PeerDisconnected -> {
                paired.set(false)
                notifyMessage(message)
            }
            else -> {
                if (paired.get()) notifyMessage(message) else sendPairFailure("Direct peer is not paired")
            }
        }
    }

    private fun receiveAsClient(message: SignalMessage) {
        when (message.messageType) {
            SignalMessageType.PairSuccess -> {
                paired.set(true)
                notifyMessage(message)
            }
            SignalMessageType.PairFailed -> notifyMessage(message)
            SignalMessageType.PeerDisconnected -> {
                paired.set(false)
                notifyMessage(message)
            }
            else -> {
                if (paired.get()) notifyMessage(message) else notifyDetail("Direct peer is not paired")
            }
        }
    }

    private fun pairClient(message: SignalMessage, host: DirectPeerConfiguration.Host) {
        val supplied = message.payload["pairing_code"].orEmpty()
        val valid = clientRegistered.get() &&
            !credentialUsed.get() &&
            nowMillis() < host.expiresAtMillis &&
            MessageDigest.isEqual(
                supplied.toByteArray(StandardCharsets.UTF_8),
                host.pairingToken.toByteArray(StandardCharsets.UTF_8)
            )
        if (!valid) {
            sendPairFailure("Direct pairing token is invalid or expired")
            return
        }
        credentialUsed.set(true)
        paired.set(true)
        val success = SignalMessage(messageType = SignalMessageType.PairSuccess)
        if (writeMessageUnchecked(success)) notifyMessage(success)
    }

    private fun sendPairFailure(reason: String) {
        writeMessageUnchecked(
            SignalMessage(
                messageType = SignalMessageType.PairFailed,
                payload = mapOf("reason" to reason)
            )
        )
    }

    private fun writeMessage(message: SignalMessage): Boolean {
        val result = writeMessageUnchecked(message)
        if (!result && !closed.get()) fail("Direct signaling send failed", null)
        return result
    }

    private fun writeMessageUnchecked(message: SignalMessage): Boolean = synchronized(writeLock) {
        val output = writer.get() ?: return@synchronized false
        runCatching {
            output.write(SignalCodec.encode(message))
            output.newLine()
            output.flush()
        }.isSuccess
    }

    private fun closePeer(expected: Socket? = null) {
        val socket = if (expected == null) {
            peerSocket.getAndSet(null)
        } else if (peerSocket.compareAndSet(expected, null)) {
            expected
        } else {
            null
        }
        if (socket != null) {
            writer.set(null)
            runCatching { socket.close() }
        }
    }

    private fun fail(reason: String, throwable: Throwable?) {
        if (closed.get()) return
        if (currentState.getAndSet(ConnectionState.Failed) == ConnectionState.Failed) return
        closePeer()
        runCatching { serverSocket.getAndSet(null)?.close() }
        val detail = throwable?.javaClass?.simpleName?.let { "$reason: $it" } ?: reason
        notifyState(ConnectionState.Failed, detail)
    }

    private fun notifyMessage(message: SignalMessage) {
        val callback = synchronized(listenerLock) { messageListener }
        runCatching { callback?.invoke(message) }
            .onFailure { notifyDetail("Direct signaling listener failed: ${it.javaClass.simpleName}") }
    }

    private fun notifyDetail(detail: String) {
        val callback = synchronized(listenerLock) { stateListener }
        runCatching { callback?.invoke(state, detail) }
    }

    private fun notifyState(state: ConnectionState, detail: String?) {
        currentState.set(state)
        val callback = synchronized(listenerLock) { stateListener }
        runCatching { callback?.invoke(state, detail) }
    }

    private fun runBackground(block: () -> Unit) {
        runCatching { executor.execute { runCatching(block).onFailure { fail("Direct signaling failed", it) } } }
            .onFailure { fail("Direct signaling executor failed", it) }
    }

    companion object {
        private const val ConnectTimeoutMillis = 5_000
        private const val MaximumMessageCharacters = 256 * 1024
    }
}
