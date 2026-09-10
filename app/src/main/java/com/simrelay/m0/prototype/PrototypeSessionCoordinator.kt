package com.simrelay.m0.prototype

import com.simrelay.m0.audio.BackendResult
import com.simrelay.m0.audio.CallAudioBackend
import com.simrelay.m0.audio.DownlinkSession
import com.simrelay.m0.audio.PcmConfig
import com.simrelay.m0.audio.UplinkSession
import com.simrelay.prototype.call.CallControlBackend
import com.simrelay.prototype.call.CallControlResult
import com.simrelay.prototype.call.CallControlSnapshot
import com.simrelay.prototype.call.CallDirection
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.AudioFormatAdapter
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.MediaTransportStats
import com.simrelay.prototype.transport.SignalingTransport
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PrototypeSessionSnapshot(
    val signalingState: ConnectionState = ConnectionState.Disconnected,
    val mediaState: ConnectionState = ConnectionState.Disconnected,
    val paired: Boolean = false,
    val callState: PrototypeCallState = PrototypeCallState.Idle,
    val sessionId: String? = null,
    val rxFrames: Long = 0,
    val txFrames: Long = 0,
    val droppedFrames: Long = 0,
    val mediaStats: MediaTransportStats = MediaTransportStats(),
    val lastError: String? = null
)

fun interface PrototypeSessionListener {
    fun onSnapshotChanged(snapshot: PrototypeSessionSnapshot)
}

fun interface PrototypeEventLogger {
    fun log(event: String, fields: Map<String, String>)
}

class PrototypeSessionCoordinator(
    private val callControl: CallControlBackend,
    private val audioBackend: CallAudioBackend,
    private val signaling: SignalingTransport,
    private val media: MediaTransport,
    private val logger: PrototypeEventLogger = PrototypeEventLogger { _, _ -> },
    private val audioConfig: PcmConfig = PcmConfig(16_000),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {
    private val lock = Any()
    private val mediaRunning = AtomicBoolean(false)
    private var listener: PrototypeSessionListener? = null
    private var current = PrototypeSessionSnapshot()
    private var downlink: DownlinkSession? = null
    private var uplink: UplinkSession? = null
    private var rxFuture: Future<*>? = null
    private var firstRx = false
    private var firstTx = false
    private var incomingAnnouncedFor: String? = null
    private var closed = false

    init {
        callControl.setListener(::onCallStateChanged)
        signaling.setMessageListener(::onSignal)
        signaling.setStateListener(::onSignalingStateChanged)
        media.setFrameListener(::onRemoteFrame)
        media.setStateListener(::onMediaStateChanged)
    }

    fun snapshot(): PrototypeSessionSnapshot = synchronized(lock) { current }

    fun setListener(listener: PrototypeSessionListener?) {
        val snapshot = synchronized(lock) {
            this.listener = listener
            current
        }
        listener?.onSnapshotChanged(snapshot)
    }

    fun connect() {
        if (synchronized(lock) { closed }) return
        signaling.connect()
    }

    fun registerHost(hostId: String, pairingCode: String, expiresAtMillis: Long): Boolean {
        if (hostId.isBlank() || pairingCode.isBlank()) return false
        return signaling.send(
            SignalMessage(
                messageType = SignalMessageType.HostOnline,
                payload = mapOf(
                    "host_id" to hostId,
                    "pairing_code" to pairingCode,
                    "expires_at_ms" to expiresAtMillis.toString()
                )
            )
        )
    }

    fun simulateIncomingCall(): CallControlResult {
        if (!snapshot().paired) return CallControlResult.Failure("A paired client is required")
        return callControl.simulateIncomingCall(idFactory(), "Prototype caller")
    }

    fun hangup(): CallControlResult {
        val sessionId = snapshot().sessionId ?: return CallControlResult.Failure("No active call")
        signaling.send(SignalMessage(messageType = SignalMessageType.Hangup, sessionId = sessionId))
        return callControl.hangup(sessionId)
    }

    override fun close() {
        val sessionId = synchronized(lock) {
            if (closed) return
            closed = true
            current.sessionId
        }
        sessionId?.let { callControl.hangup(it) }
        stopMedia("coordinator_closed")
        signaling.close()
        media.close()
        callControl.close()
        audioBackend.close()
        executor.shutdownNow()
        update { it.copy(signalingState = ConnectionState.Disconnected, mediaState = ConnectionState.Disconnected) }
    }

    private fun onSignal(message: SignalMessage) {
        when (message.messageType) {
            SignalMessageType.PairSuccess -> {
                logger.log("pair_success", emptyMap())
                update { it.copy(paired = true, lastError = null) }
            }
            SignalMessageType.PairFailed -> fail(message.payload["reason"] ?: "Pairing failed")
            SignalMessageType.PeerDisconnected -> onPeerDisconnected(message)
            SignalMessageType.Answer -> withSession(message) { callControl.answer(it) }
            SignalMessageType.Reject -> withSession(message) { callControl.reject(it) }
            SignalMessageType.Hangup -> withSession(message) { callControl.hangup(it) }
            SignalMessageType.OutgoingCall -> {
                val sessionId = message.sessionId ?: return
                if (!snapshot().paired) {
                    sendSessionError(sessionId, "Client is not paired")
                    return
                }
                val identity = message.payload["display_identity"] ?: "Prototype destination"
                val result = callControl.dial(sessionId, identity)
                if (result is CallControlResult.Failure) sendSessionError(sessionId, result.reason)
            }
            SignalMessageType.MediaOffer,
            SignalMessageType.MediaAnswer,
            SignalMessageType.IceCandidate -> media.handleSignal(message)
            SignalMessageType.SessionError -> fail(message.payload["reason"] ?: "Remote session error")
            SignalMessageType.HostOnline,
            SignalMessageType.ClientOnline,
            SignalMessageType.PairRequest,
            SignalMessageType.IncomingCall,
            SignalMessageType.CallState -> Unit
        }
    }

    private fun onPeerDisconnected(message: SignalMessage) {
        val sessionId = snapshot().sessionId
        if (sessionId != null && (message.sessionId == null || message.sessionId == sessionId)) {
            callControl.hangup(sessionId)
        } else {
            stopMedia("peer_disconnected")
        }
        update { it.copy(paired = false, lastError = null) }
        logger.log("peer_disconnected", emptyMap())
    }

    private fun withSession(message: SignalMessage, action: (String) -> CallControlResult) {
        val sessionId = message.sessionId ?: return
        if (snapshot().sessionId != sessionId) {
            sendSessionError(sessionId, "Session does not match active call")
            return
        }
        val result = action(sessionId)
        if (result is CallControlResult.Failure) sendSessionError(sessionId, result.reason)
    }

    private fun onCallStateChanged(snapshot: CallControlSnapshot) {
        val call = snapshot.call
        update {
            it.copy(
                callState = snapshot.state,
                sessionId = call?.sessionId,
                lastError = snapshot.error
            )
        }
        call?.let {
            logger.log(
                "call_state_changed",
                mapOf("session_id" to it.sessionId, "state" to snapshot.state.name)
            )
            if (it.direction == CallDirection.Incoming && snapshot.state == PrototypeCallState.Incoming) {
                announceIncoming(it.sessionId, it.displayIdentity)
            }
            signaling.send(
                SignalMessage(
                    messageType = SignalMessageType.CallState,
                    sessionId = it.sessionId,
                    payload = mapOf("state" to snapshot.state.name.lowercase())
                )
            )
            if (snapshot.state == PrototypeCallState.Active) media.start(it.sessionId, initiator = true)
        }
        if (snapshot.state in setOf(
                PrototypeCallState.Rejected,
                PrototypeCallState.Ended,
                PrototypeCallState.Idle,
                PrototypeCallState.Failure
            )
        ) {
            stopMedia("call_${snapshot.state.name.lowercase()}")
            if (snapshot.state == PrototypeCallState.Idle) {
                incomingAnnouncedFor = null
                update { it.copy(sessionId = null) }
            }
        }
    }

    private fun announceIncoming(sessionId: String, identity: String) {
        synchronized(lock) {
            if (incomingAnnouncedFor == sessionId) return
            incomingAnnouncedFor = sessionId
        }
        signaling.send(
            SignalMessage(
                messageType = SignalMessageType.IncomingCall,
                sessionId = sessionId,
                payload = mapOf("display_identity" to identity)
            )
        )
    }

    private fun onSignalingStateChanged(state: ConnectionState, detail: String?) {
        logger.log("signaling_state", mapOf("state" to state.name))
        update { it.copy(signalingState = state, lastError = detail.takeIf { state == ConnectionState.Failed }) }
        if (state == ConnectionState.Disconnected) {
            val sessionId = snapshot().sessionId
            if (sessionId != null) callControl.hangup(sessionId)
            update { it.copy(paired = false) }
        }
    }

    private fun onMediaStateChanged(state: ConnectionState, detail: String?) {
        logger.log(
            "media_state",
            buildMap {
                snapshot().sessionId?.let { put("session_id", it) }
                put("state", state.name)
            }
        )
        update { it.copy(mediaState = state, lastError = detail.takeIf { state == ConnectionState.Failed }) }
        when (state) {
            ConnectionState.Connected -> startAudio()
            ConnectionState.Disconnected,
            ConnectionState.Failed -> stopAudio()
            ConnectionState.Connecting -> Unit
        }
    }

    private fun startAudio() {
        if (!mediaRunning.compareAndSet(false, true)) return
        runCatching { startAudioInternal() }.onFailure { throwable ->
            mediaRunning.set(false)
            stopAudioResources()
            fail("Audio start failed: ${throwable.javaClass.simpleName}")
        }
    }

    private fun startAudioInternal() {
        val capability = audioBackend.probe()
        if (!capability.isSupported) {
            mediaRunning.set(false)
            fail(capability.message)
            return
        }
        val config = audioConfig
        val openedDownlink = audioBackend.openDownlink(config)
        if (openedDownlink !is BackendResult.Success) {
            mediaRunning.set(false)
            fail((openedDownlink as BackendResult.Failure).capability.message)
            return
        }
        val downlinkValue = openedDownlink.value
        val openedUplink = runCatching { audioBackend.openUplink(config) }.getOrElse { throwable ->
            closePendingAudio(downlinkValue, null)
            throw throwable
        }
        if (openedUplink !is BackendResult.Success) {
            closePendingAudio(downlinkValue, null)
            mediaRunning.set(false)
            fail((openedUplink as BackendResult.Failure).capability.message)
            return
        }
        val uplinkValue = openedUplink.value
        val downlinkStart = runCatching { downlinkValue.start() }.getOrElse { throwable ->
            closePendingAudio(downlinkValue, uplinkValue)
            throw throwable
        }
        if (downlinkStart is BackendResult.Failure) {
            closePendingAudio(downlinkValue, uplinkValue)
            mediaRunning.set(false)
            fail(downlinkStart.capability.message)
            return
        }
        val uplinkStart = runCatching { uplinkValue.start() }.getOrElse { throwable ->
            closePendingAudio(downlinkValue, uplinkValue)
            throw throwable
        }
        if (uplinkStart is BackendResult.Failure) {
            closePendingAudio(downlinkValue, uplinkValue)
            mediaRunning.set(false)
            fail(uplinkStart.capability.message)
            return
        }
        val future = runCatching { executor.submit { rxLoop(downlinkValue) } }.getOrElse { throwable ->
            closePendingAudio(downlinkValue, uplinkValue)
            mediaRunning.set(false)
            throw throwable
        }
        synchronized(lock) {
            downlink = downlinkValue
            uplink = uplinkValue
            firstRx = false
            firstTx = false
            rxFuture = future
        }
        logger.log("audio_started", mapOf("session_id" to (snapshot().sessionId ?: "unknown")))
    }

    private fun rxLoop(session: DownlinkSession) {
        var sequence = 0L
        val buffer = ShortArray(session.config.frameSampleCount())
        try {
            while (mediaRunning.get()) {
                when (val result = session.read(buffer)) {
                    is BackendResult.Success -> {
                        if (result.value <= 0) continue
                        val sourceFormat = PrototypeAudioFormat(session.config.sampleRateHz)
                        val sourceSamples = buffer.copyOf(result.value)
                        if (sourceSamples.size != sourceFormat.samplesPerFrame) {
                            incrementDropped()
                            continue
                        }
                        val source = PrototypeAudioFrame(sourceFormat, sequence++, nanoTime(), sourceSamples)
                        val wireFrame = AudioFormatAdapter.adapt(source, media.pcmFormat)
                        if (media.send(wireFrame)) {
                            val shouldLog = synchronized(lock) {
                                val value = !firstRx
                                firstRx = true
                                value
                            }
                            if (shouldLog) {
                                logger.log("first_rx", mapOf("session_id" to (snapshot().sessionId ?: "unknown")))
                                refreshMediaStats()
                            }
                            update { it.copy(rxFrames = it.rxFrames + 1) }
                            if (sequence % 50L == 0L) refreshMediaStats()
                        } else {
                            incrementDropped()
                        }
                    }
                    is BackendResult.Failure -> {
                        if (mediaRunning.get()) fail(result.capability.message)
                        return
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (mediaRunning.get()) fail(throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun onRemoteFrame(frame: PrototypeAudioFrame) {
        if (!mediaRunning.get()) return
        runCatching { writeRemoteFrame(frame) }.onFailure { throwable ->
            if (mediaRunning.get()) fail("Remote audio write failed: ${throwable.javaClass.simpleName}")
        }
    }

    private fun writeRemoteFrame(frame: PrototypeAudioFrame) {
        val session = synchronized(lock) { uplink } ?: return
        val adapted = AudioFormatAdapter.adapt(frame, PrototypeAudioFormat(session.config.sampleRateHz))
        var offset = 0
        while (offset < adapted.samples.size && mediaRunning.get()) {
            when (val result = session.write(adapted.samples, offset, adapted.samples.size - offset)) {
                is BackendResult.Success -> {
                    if (result.value <= 0) {
                        incrementDropped()
                        return
                    }
                    offset += result.value
                }
                is BackendResult.Failure -> {
                    fail(result.capability.message)
                    return
                }
            }
        }
        val shouldLog = synchronized(lock) {
            val value = !firstTx
            firstTx = true
            value
        }
        if (shouldLog) {
            logger.log("first_tx", mapOf("session_id" to (snapshot().sessionId ?: "unknown")))
            refreshMediaStats()
        }
        update { it.copy(txFrames = it.txFrames + 1) }
    }

    private fun stopMedia(reason: String) {
        media.stop(reason)
        stopAudio()
        logger.log(
            "session_cleanup",
            buildMap {
                snapshot().sessionId?.let { put("session_id", it) }
                put("reason", reason)
            }
        )
    }

    private fun stopAudio() {
        if (!mediaRunning.compareAndSet(true, false)) return
        stopAudioResources()
    }

    private fun stopAudioResources() {
        val resources = synchronized(lock) {
            val value = Triple(downlink, uplink, rxFuture)
            downlink = null
            uplink = null
            rxFuture = null
            value
        }
        runCatching { resources.first?.stop() }.onFailure { logCleanupFailure("downlink_stop", it) }
        runCatching { resources.second?.stop() }.onFailure { logCleanupFailure("uplink_stop", it) }
        try {
            resources.third?.get(500, TimeUnit.MILLISECONDS)
        } catch (exception: Exception) {
            resources.third?.cancel(true)
        }
        runCatching { resources.first?.close() }.onFailure { logCleanupFailure("downlink_close", it) }
        runCatching { resources.second?.close() }.onFailure { logCleanupFailure("uplink_close", it) }
    }

    private fun logCleanupFailure(operation: String, throwable: Throwable) {
        logger.log(
            "cleanup_failure",
            mapOf("operation" to operation, "error" to throwable.javaClass.simpleName)
        )
    }

    private fun closePendingAudio(downlink: DownlinkSession?, uplink: UplinkSession?) {
        runCatching { downlink?.stop() }.onFailure { logCleanupFailure("pending_downlink_stop", it) }
        runCatching { uplink?.stop() }.onFailure { logCleanupFailure("pending_uplink_stop", it) }
        runCatching { downlink?.close() }.onFailure { logCleanupFailure("pending_downlink_close", it) }
        runCatching { uplink?.close() }.onFailure { logCleanupFailure("pending_uplink_close", it) }
    }

    private fun sendSessionError(sessionId: String, reason: String) {
        signaling.send(
            SignalMessage(
                messageType = SignalMessageType.SessionError,
                sessionId = sessionId,
                payload = mapOf("reason" to reason)
            )
        )
        fail(reason)
    }

    private fun fail(reason: String) {
        logger.log("failure", mapOf("reason" to reason))
        update { it.copy(lastError = reason) }
    }

    private fun incrementDropped() {
        update { it.copy(droppedFrames = it.droppedFrames + 1) }
    }

    private fun refreshMediaStats() {
        val requestedSession = snapshot().sessionId ?: return
        media.requestStats { stats ->
            if (snapshot().sessionId != requestedSession) return@requestStats
            update { it.copy(mediaStats = stats) }
            logger.log(
                "media_stats",
                mapOf(
                    "session_id" to requestedSession,
                    "codec" to (stats.codec ?: "unknown"),
                    "outbound_packets" to stats.outboundPackets.toString(),
                    "inbound_packets" to stats.inboundPackets.toString(),
                    "outbound_bytes" to stats.outboundBytes.toString(),
                    "inbound_bytes" to stats.inboundBytes.toString()
                )
            )
        }
    }

    private fun update(transform: (PrototypeSessionSnapshot) -> PrototypeSessionSnapshot) {
        val changed = synchronized(lock) {
            current = transform(current)
            Pair(current, listener)
        }
        changed.second?.onSnapshotChanged(changed.first)
    }
}
