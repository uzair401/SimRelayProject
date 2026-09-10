package com.simrelay.prototype.media.android

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.MediaTransportStats
import com.simrelay.prototype.transport.SignalingTransport
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

class WebRtcAudioMediaTransport(
    context: Context,
    private val signaling: SignalingTransport,
    private val iceServerUrls: List<String> = listOf("stun:stun.l.google.com:19302")
) : MediaTransport {
    override val pcmFormat = PrototypeAudioFormat(48_000)
    private val lock = Any()
    private val lifecycleLock = Any()
    private val currentState = AtomicReference(ConnectionState.Disconnected)
    private var currentStateDetail: String? = null
    private val audioBridge = ExternalPcmAudioBridge(pcmFormat, onFrame = ::deliverFrame)
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var localSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var localTrackOwnedByPeer = false
    private var sessionId: String? = null
    private var frameListener: ((PrototypeAudioFrame) -> Unit)? = null
    private var stateListener: ((ConnectionState, String?) -> Unit)? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val remoteSinks = IdentityHashMap<AudioTrack, AudioTrackSink>()
    private var remoteDescriptionSet = false
    @Volatile
    private var generation = 0L
    private var closed = false

    init {
        initializeWebRtc(context.applicationContext)
        val audioDevice = JavaAudioDeviceModule.builder(context.applicationContext)
            .setInputSampleRate(pcmFormat.sampleRateHz)
            .setOutputSampleRate(pcmFormat.sampleRateHz)
            .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback { buffer, audioFormat, channelCount, sampleRate, _, captureTime ->
                runCatching {
                    audioBridge.onCaptureBuffer(buffer, audioFormat, channelCount, sampleRate, captureTime)
                }.getOrElse { throwable ->
                    failCurrent("Capture bridge failed: ${throwable.javaClass.simpleName}")
                    0L
                }
            }
            .createAudioDeviceModule()
        factory = try {
            audioDevice.setAudioRecordEnabled(false)
            audioDevice.setSpeakerMute(true)
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDevice)
                .createPeerConnectionFactory()
        } finally {
            audioDevice.release()
        }
    }

    override val state: ConnectionState
        get() = currentState.get()

    override fun start(sessionId: String, initiator: Boolean) {
        runCatching { startInternal(sessionId, initiator) }.onFailure { throwable ->
            failCurrent("WebRTC start failed: ${throwable.javaClass.simpleName}")
        }
    }

    private fun startInternal(sessionId: String, initiator: Boolean) {
        synchronized(lifecycleLock) {
            val previous = synchronized(lock) {
                if (closed) {
                    notifyStateLocked(ConnectionState.Failed, "WebRTC transport is closed")
                    return
                }
                if (this.sessionId == sessionId && peer != null) return
                detachPeerLocked()
            }
            releasePeer(previous)
            val offer = synchronized(lock) {
                this.sessionId = sessionId
                notifyStateLocked(ConnectionState.Connecting, null)
                val connection = createPeerLocked(initiator) ?: run {
                    notifyStateLocked(ConnectionState.Failed, "WebRTC audio PeerConnection creation failed")
                    return
                }
                if (initiator) connection to generation else null
            }
            offer?.let { (connection, activeGeneration) ->
                runPeerOperation(activeGeneration, connection, "Offer creation failed") {
                    connection.createOffer(
                        LocalDescriptionObserver(SignalMessageType.MediaOffer, activeGeneration),
                        MediaConstraints()
                    )
                }
            }
        }
    }

    override fun handleSignal(message: SignalMessage) {
        runCatching { handleSignalInternal(message) }.onFailure { throwable ->
            failCurrent("WebRTC signal handling failed: ${throwable.javaClass.simpleName}")
        }
    }

    private fun handleSignalInternal(message: SignalMessage) {
        val messageSessionId = message.sessionId ?: return
        Log.i("SimRelayWebRtc", "event=signal_received type=${message.messageType.name.lowercase()}")
        var remoteDescription: RemoteDescriptionRequest? = null
        var candidate: CandidateRequest? = null
        synchronized(lock) {
            if (sessionId == null && message.messageType in setOf(
                    SignalMessageType.MediaOffer,
                    SignalMessageType.IceCandidate
                )
            ) {
                sessionId = messageSessionId
                notifyStateLocked(ConnectionState.Connecting, null)
                if (createPeerLocked(false) == null) {
                    notifyStateLocked(ConnectionState.Failed, "WebRTC audio PeerConnection creation failed")
                    return
                }
            }
            if (sessionId != messageSessionId) return
            when (message.messageType) {
                SignalMessageType.MediaOffer -> remoteDescription = remoteDescriptionRequestLocked(
                    message,
                    SessionDescription.Type.OFFER,
                    true
                )
                SignalMessageType.MediaAnswer -> remoteDescription = remoteDescriptionRequestLocked(
                    message,
                    SessionDescription.Type.ANSWER,
                    false
                )
                SignalMessageType.IceCandidate -> {
                    val parsed = parseCandidate(message) ?: return
                    val connection = peer ?: return
                    if (remoteDescriptionSet) {
                        candidate = CandidateRequest(connection, generation, parsed)
                    } else {
                        pendingCandidates += parsed
                    }
                }
                else -> Unit
            }
        }
        remoteDescription?.let(::applyRemoteDescription)
        candidate?.let(::addCandidate)
    }

    override fun send(frame: PrototypeAudioFrame): Boolean {
        if (state != ConnectionState.Connected) return false
        return runCatching { audioBridge.submit(frame) }
            .onFailure { throwable ->
                failCurrent("PCM submission failed: ${throwable.javaClass.simpleName}")
            }
            .getOrDefault(false)
    }

    override fun stop(reason: String) {
        synchronized(lifecycleLock) {
            val resources = synchronized(lock) { detachPeerLocked() }
            releasePeer(resources)
            synchronized(lock) { notifyStateLocked(ConnectionState.Disconnected, null) }
        }
    }

    override fun requestStats(callback: (MediaTransportStats) -> Unit) {
        val connection = synchronized(lock) { peer }
        if (connection == null) {
            runCatching { callback(mergeBridgeStats(MediaTransportStats())) }.onFailure { throwable ->
                Log.e("SimRelayWebRtc", "event=stats_callback_failed detail=${throwable.javaClass.simpleName}")
            }
            return
        }
        runCatching {
            connection.getStats { report ->
                val stats = runCatching { mergeBridgeStats(parseStats(report)) }
                    .getOrElse { throwable ->
                        Log.e("SimRelayWebRtc", "event=stats_failed detail=${throwable.javaClass.simpleName}")
                        mergeBridgeStats(MediaTransportStats())
                    }
                runCatching { callback(stats) }.onFailure { throwable ->
                    Log.e("SimRelayWebRtc", "event=stats_callback_failed detail=${throwable.javaClass.simpleName}")
                }
            }
        }.onFailure { throwable ->
            Log.e("SimRelayWebRtc", "event=stats_request_failed detail=${throwable.javaClass.simpleName}")
            runCatching { callback(mergeBridgeStats(MediaTransportStats())) }.onFailure { callbackError ->
                Log.e("SimRelayWebRtc", "event=stats_callback_failed detail=${callbackError.javaClass.simpleName}")
            }
        }
    }

    override fun setFrameListener(listener: ((PrototypeAudioFrame) -> Unit)?) {
        synchronized(lock) { frameListener = listener }
    }

    override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) {
        synchronized(lock) { stateListener = listener }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            val resources = synchronized(lock) {
                if (closed) return
                closed = true
                detachPeerLocked()
            }
            releasePeer(resources)
            synchronized(lock) { notifyStateLocked(ConnectionState.Disconnected, null) }
            release("peer_factory") { factory.dispose() }
        }
    }

    private fun createPeerLocked(initiator: Boolean): PeerConnection? {
        val configuration = PeerConnection.RTCConfiguration(
            iceServerUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        var connection: PeerConnection? = null
        var source: AudioSource? = null
        var track: AudioTrack? = null
        var trackOwnedByPeer = false
        return runCatching {
            connection = factory.createPeerConnection(configuration, Observer(generation))
                ?: error("PeerConnection creation returned null")
            source = factory.createAudioSource(MediaConstraints())
            track = factory.createAudioTrack("simrelay-audio-$generation", source)
            val opus = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
                .codecs
                .filter { it.mimeType.equals("audio/opus", ignoreCase = true) }
            if (opus.isEmpty()) error("Opus capability is unavailable")
            if (initiator) {
                val transceiver = connection!!.addTransceiver(
                    track,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                        listOf("simrelay-audio")
                    )
                )
                trackOwnedByPeer = true
                if (transceiver.setCodecPreferences(opus).isError) {
                    error("Opus codec preference was rejected")
                }
            }
            peer = connection
            localSource = source
            localTrack = track
            localTrackOwnedByPeer = trackOwnedByPeer
            audioBridge.start()
            connection
        }.onFailure { throwable ->
            Log.e("SimRelayWebRtc", "event=peer_creation_failed detail=${throwable.javaClass.simpleName}")
            release("partial_peer") { connection?.dispose() }
            if (!trackOwnedByPeer) release("partial_local_track") { track?.dispose() }
            release("partial_local_source") { source?.dispose() }
        }.getOrNull()
    }

    private fun remoteDescriptionRequestLocked(
        message: SignalMessage,
        type: SessionDescription.Type,
        createAnswer: Boolean
    ): RemoteDescriptionRequest? {
        val description = message.payload["sdp"] ?: return null
        val connection = peer ?: return null
        return RemoteDescriptionRequest(
            connection,
            generation,
            SessionDescription(type, description),
            createAnswer
        )
    }

    private fun applyRemoteDescription(request: RemoteDescriptionRequest) {
        val connection = request.connection
        val activeGeneration = request.generation
        runPeerOperation(activeGeneration, connection, "Remote SDP invocation failed") {
            connection.setRemoteDescription(object : EmptySdpObserver() {
            override fun onSetSuccess() {
                Log.i(
                    "SimRelayWebRtc",
                    "event=remote_description_set type=${request.description.type.canonicalForm()}"
                )
                val result = synchronized(lock) {
                    if (generation != activeGeneration || peer !== connection) return
                    remoteDescriptionSet = true
                    val candidates = pendingCandidates.toList()
                    pendingCandidates.clear()
                    candidates to request.createAnswer
                }
                result.first.forEach { addCandidate(CandidateRequest(connection, activeGeneration, it)) }
                val shouldCreateAnswer = result.second
                if (shouldCreateAnswer) {
                    if (!prepareAnswererTrack(connection, activeGeneration)) return
                    Log.i("SimRelayWebRtc", "event=answer_creation_started")
                    runPeerOperation(activeGeneration, connection, "Answer creation failed") {
                        connection.createAnswer(
                            LocalDescriptionObserver(SignalMessageType.MediaAnswer, activeGeneration),
                            MediaConstraints()
                        )
                    }
                }
            }

            override fun onSetFailure(error: String) {
                synchronized(lock) {
                    if (generation == activeGeneration && peer === connection) {
                        notifyStateLocked(ConnectionState.Failed, "Remote SDP failed: $error")
                    }
                }
            }
            }, request.description)
        }
    }

    private fun prepareAnswererTrack(connection: PeerConnection, observerGeneration: Long): Boolean {
        val track = synchronized(lock) {
            if (generation == observerGeneration && peer === connection) localTrack else null
        } ?: return false
        val configured = runCatching {
            val transceiver = connection.transceivers.firstOrNull {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && !it.isStopped
            } ?: error("Remote offer has no active audio transceiver")
            val opus = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
                .codecs
                .filter { it.mimeType.equals("audio/opus", ignoreCase = true) }
            if (opus.isEmpty()) error("Opus capability is unavailable")
            if (transceiver.setCodecPreferences(opus).isError) error("Opus codec preference was rejected")
            if (!transceiver.sender.setTrack(track, true)) error("Local audio track was rejected")
            synchronized(lock) {
                if (generation == observerGeneration && peer === connection) localTrackOwnedByPeer = true
            }
            if (!transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)) {
                error("Audio transceiver direction was rejected")
            }
        }.exceptionOrNull()
        if (configured != null) {
            failGeneration(
                observerGeneration,
                "Answer audio configuration failed: ${configured.javaClass.simpleName}"
            )
            return false
        }
        return true
    }

    private fun parseCandidate(message: SignalMessage): IceCandidate? {
        return IceCandidate(
            message.payload["sdp_mid"],
            message.payload["sdp_mline_index"]?.toIntOrNull() ?: return null,
            message.payload["candidate"] ?: return null
        )
    }

    private fun addCandidate(request: CandidateRequest) {
        runPeerOperation(request.generation, request.connection, "ICE candidate failed") {
            val result = request.connection.addIceCandidate(request.candidate)
            if (!result) error("ICE candidate was rejected")
        }
    }

    private fun attachRemoteTrack(track: MediaStreamTrack?, observerGeneration: Long) {
        val audioTrack = track as? AudioTrack ?: return
        val sink = AudioTrackSink { data, bits, sampleRate, channels, frames, _ ->
            if (generation == observerGeneration) {
                runCatching { audioBridge.onRemoteData(data, bits, sampleRate, channels, frames) }
                    .onFailure { throwable ->
                        failGeneration(
                            observerGeneration,
                            "Decoded audio bridge failed: ${throwable.javaClass.simpleName}"
                        )
                    }
            }
        }
        val shouldAttach = synchronized(lock) {
            generation == observerGeneration && !remoteSinks.containsKey(audioTrack)
        }
        if (!shouldAttach) return
        val attached = runCatching { audioTrack.addSink(sink) }.isSuccess
        if (!attached) {
            failGeneration(observerGeneration, "Remote audio sink attachment failed")
            return
        }
        val retained = synchronized(lock) {
            if (generation == observerGeneration && peer != null) {
                remoteSinks[audioTrack] = sink
                true
            } else {
                false
            }
        }
        if (!retained) release("unretained_remote_sink") { audioTrack.removeSink(sink) }
    }

    private fun detachPeerLocked(): PeerResources {
        audioBridge.stop()
        val resources = PeerResources(
            peer = peer,
            localSource = localSource,
            localTrack = localTrack,
            localTrackOwnedByPeer = localTrackOwnedByPeer,
            remoteSinks = remoteSinks.entries.map { it.key to it.value }
        )
        peer = null
        localSource = null
        localTrack = null
        localTrackOwnedByPeer = false
        sessionId = null
        remoteSinks.clear()
        pendingCandidates.clear()
        remoteDescriptionSet = false
        generation += 1
        return resources
    }

    private fun releasePeer(resources: PeerResources) {
        resources.remoteSinks.forEach { (track, sink) -> release("remote_sink") { track.removeSink(sink) } }
        release("peer_close") { resources.peer?.close() }
        release("peer_dispose") { resources.peer?.dispose() }
        if (!resources.localTrackOwnedByPeer) release("local_track") { resources.localTrack?.dispose() }
        release("local_source") { resources.localSource?.dispose() }
    }

    private fun deliverFrame(frame: PrototypeAudioFrame) {
        val callback = synchronized(lock) { frameListener } ?: return
        runCatching { callback(frame) }.onFailure { throwable ->
            failCurrent("Media frame listener failed: ${throwable.javaClass.simpleName}")
        }
    }

    private fun notifyStateLocked(state: ConnectionState, detail: String?) {
        if (currentState.get() == state && currentStateDetail == detail) return
        currentStateDetail = detail
        currentState.set(state)
        Log.i(
            "SimRelayWebRtc",
            "event=media_state state=${state.name.lowercase()} detail=${detail ?: "none"}"
        )
        runCatching { stateListener?.invoke(state, detail) }.onFailure { throwable ->
            Log.e("SimRelayWebRtc", "event=state_callback_failed detail=${throwable.javaClass.simpleName}")
        }
    }

    private fun runPeerOperation(
        observerGeneration: Long,
        connection: PeerConnection,
        failure: String,
        operation: () -> Unit
    ) {
        val active = synchronized(lock) {
            generation == observerGeneration && peer === connection
        }
        if (!active) return
        runCatching(operation).onFailure { error ->
            failGeneration(observerGeneration, "$failure: ${error.javaClass.simpleName}")
        }
    }

    private fun failGeneration(observerGeneration: Long, detail: String) {
        synchronized(lock) {
            if (generation == observerGeneration) notifyStateLocked(ConnectionState.Failed, detail)
        }
    }

    private fun failCurrent(detail: String) {
        val activeGeneration = synchronized(lock) { generation }
        failGeneration(activeGeneration, detail)
    }

    private fun sendSignal(message: SignalMessage, observerGeneration: Long) {
        val sent = runCatching { signaling.send(message) }.getOrDefault(false)
        if (!sent) failGeneration(observerGeneration, "WebRTC signaling send failed")
    }

    private fun release(resource: String, operation: () -> Unit) {
        runCatching(operation).onFailure { throwable ->
            Log.e(
                "SimRelayWebRtc",
                "event=release_failed resource=$resource detail=${throwable.javaClass.simpleName}"
            )
        }
    }

    private fun mergeBridgeStats(rtp: MediaTransportStats): MediaTransportStats {
        val bridge = audioBridge.stats()
        return rtp.copy(
            submittedFrames = bridge.submittedFrames,
            deliveredFrames = bridge.deliveredFrames,
            droppedFrames = bridge.droppedFrames
        )
    }

    private fun parseStats(report: RTCStatsReport): MediaTransportStats {
        val stats = report.statsMap.values
        val audioOutbound = stats.firstOrNull {
            it.type == "outbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
        }
        val audioInbound = stats.firstOrNull {
            it.type == "inbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
        }
        val codecId = audioOutbound?.members?.get("codecId") ?: audioInbound?.members?.get("codecId")
        val codec = stats.firstOrNull { it.type == "codec" && it.id == codecId }?.members?.get("mimeType")?.toString()
        return MediaTransportStats(
            codec = codec,
            outboundPackets = audioOutbound.number("packetsSent"),
            outboundBytes = audioOutbound.number("bytesSent"),
            inboundPackets = audioInbound.number("packetsReceived"),
            inboundBytes = audioInbound.number("bytesReceived")
        )
    }

    private fun org.webrtc.RTCStats?.number(name: String): Long =
        (this?.members?.get(name) as? Number)?.toLong() ?: 0

    private inner class LocalDescriptionObserver(
        private val messageType: SignalMessageType,
        private val observerGeneration: Long
    ) : EmptySdpObserver() {
        override fun onCreateSuccess(description: SessionDescription) {
            Log.i("SimRelayWebRtc", "event=description_created type=${description.type.canonicalForm()}")
            val connection = synchronized(lock) {
                if (generation != observerGeneration) null else peer
            } ?: return
            runPeerOperation(observerGeneration, connection, "Local SDP invocation failed") {
                connection.setLocalDescription(object : EmptySdpObserver() {
                    override fun onSetSuccess() {
                        val activeSession = synchronized(lock) {
                            if (generation != observerGeneration || peer !== connection) null else sessionId
                        } ?: return
                        Log.i("SimRelayWebRtc", "event=signal_sent type=${messageType.name.lowercase()}")
                        sendSignal(
                            SignalMessage(
                                messageType = messageType,
                                sessionId = activeSession,
                                payload = mapOf("sdp" to description.description)
                            ),
                            observerGeneration
                        )
                    }

                    override fun onSetFailure(error: String) {
                        synchronized(lock) {
                            if (generation == observerGeneration && peer === connection) {
                                notifyStateLocked(ConnectionState.Failed, "Local SDP failed: $error")
                            }
                        }
                    }
                }, description)
            }
        }

        override fun onCreateFailure(error: String) {
            Log.i("SimRelayWebRtc", "event=description_creation_failed detail=$error")
            synchronized(lock) {
                if (generation == observerGeneration) {
                    notifyStateLocked(ConnectionState.Failed, "SDP creation failed: $error")
                }
            }
        }
    }

    private inner class Observer(
        private val observerGeneration: Long
    ) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            synchronized(lock) {
                if (generation != observerGeneration) return
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> notifyStateLocked(ConnectionState.Connected, null)
                    PeerConnection.IceConnectionState.FAILED -> notifyStateLocked(ConnectionState.Failed, "ICE connection failed")
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED -> notifyStateLocked(ConnectionState.Disconnected, null)
                    else -> Unit
                }
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            synchronized(lock) {
                if (generation != observerGeneration) return
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> notifyStateLocked(ConnectionState.Connected, null)
                    PeerConnection.PeerConnectionState.FAILED -> notifyStateLocked(ConnectionState.Failed, "Peer connection failed")
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.CLOSED -> notifyStateLocked(ConnectionState.Disconnected, null)
                    else -> Unit
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            val activeSession = synchronized(lock) {
                if (generation != observerGeneration) null else sessionId
            } ?: return
            sendSignal(
                SignalMessage(
                    messageType = SignalMessageType.IceCandidate,
                    sessionId = activeSession,
                    payload = mapOf(
                        "candidate" to candidate.sdp,
                        "sdp_mid" to (candidate.sdpMid ?: "0"),
                        "sdp_mline_index" to candidate.sdpMLineIndex.toString()
                    )
                ),
                observerGeneration
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) {
            release("unexpected_data_channel_close") { dataChannel.close() }
            release("unexpected_data_channel_dispose") { dataChannel.dispose() }
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            attachRemoteTrack(receiver.track(), observerGeneration)
        }
    }

    private open class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private data class PeerResources(
        val peer: PeerConnection?,
        val localSource: AudioSource?,
        val localTrack: AudioTrack?,
        val localTrackOwnedByPeer: Boolean,
        val remoteSinks: List<Pair<AudioTrack, AudioTrackSink>>
    )

    private data class RemoteDescriptionRequest(
        val connection: PeerConnection,
        val generation: Long,
        val description: SessionDescription,
        val createAnswer: Boolean
    )

    private data class CandidateRequest(
        val connection: PeerConnection,
        val generation: Long,
        val candidate: IceCandidate
    )

    companion object {
        private val initializationLock = Any()
        @Volatile
        private var initialized = false

        private fun initializeWebRtc(context: Context) {
            if (initialized) return
            synchronized(initializationLock) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }
}
