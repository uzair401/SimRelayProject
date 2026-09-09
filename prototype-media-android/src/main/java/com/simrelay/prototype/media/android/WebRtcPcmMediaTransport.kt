package com.simrelay.prototype.media.android

import android.content.Context
import com.simrelay.prototype.media.PcmFrameCodec
import com.simrelay.prototype.media.PcmFrameDecodeResult
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.SignalingTransport
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class WebRtcPcmMediaTransport(
    context: Context,
    private val signaling: SignalingTransport,
    private val iceServerUrls: List<String> = listOf("stun:stun.l.google.com:19302")
) : MediaTransport {
    private val lock = Any()
    private val lifecycleLock = Any()
    private val currentState = AtomicReference(ConnectionState.Disconnected)
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var sessionId: String? = null
    private var frameListener: ((PrototypeAudioFrame) -> Unit)? = null
    private var stateListener: ((ConnectionState, String?) -> Unit)? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var generation = 0L
    private var closed = false

    init {
        initializeWebRtc(context.applicationContext)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override val state: ConnectionState
        get() = currentState.get()

    override fun start(sessionId: String, initiator: Boolean) {
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
            synchronized(lock) {
                this.sessionId = sessionId
                notifyStateLocked(ConnectionState.Connecting, null)
                val connection = createPeerLocked() ?: run {
                    notifyStateLocked(ConnectionState.Failed, "WebRTC PeerConnection creation failed")
                    return
                }
                if (initiator) {
                    registerChannelLocked(connection.createDataChannel("simrelay-pcm", DataChannel.Init().apply {
                        ordered = false
                        maxRetransmits = 0
                    }))
                    connection.createOffer(
                        LocalDescriptionObserver(SignalMessageType.MediaOffer, generation),
                        MediaConstraints()
                    )
                }
            }
        }
    }

    override fun handleSignal(message: SignalMessage) {
        val messageSessionId = message.sessionId ?: return
        synchronized(lock) {
            if (sessionId == null && message.messageType in setOf(
                    SignalMessageType.MediaOffer,
                    SignalMessageType.IceCandidate
                )
            ) {
                sessionId = messageSessionId
                notifyStateLocked(ConnectionState.Connecting, null)
                if (createPeerLocked() == null) {
                    notifyStateLocked(ConnectionState.Failed, "WebRTC PeerConnection creation failed")
                    return
                }
            }
            if (sessionId != messageSessionId) return
            when (message.messageType) {
                SignalMessageType.MediaOffer -> applyRemoteDescriptionLocked(message, SessionDescription.Type.OFFER, true)
                SignalMessageType.MediaAnswer -> applyRemoteDescriptionLocked(message, SessionDescription.Type.ANSWER, false)
                SignalMessageType.IceCandidate -> addCandidateLocked(message)
                else -> Unit
            }
        }
    }

    override fun send(frame: PrototypeAudioFrame): Boolean {
        val activeChannel = synchronized(lock) { channel }
        if (state != ConnectionState.Connected || activeChannel?.state() != DataChannel.State.OPEN) return false
        return activeChannel.send(DataChannel.Buffer(ByteBuffer.wrap(PcmFrameCodec.encode(frame)), true))
    }

    override fun stop(reason: String) {
        synchronized(lifecycleLock) {
            val resources = synchronized(lock) { detachPeerLocked() }
            releasePeer(resources)
            synchronized(lock) { notifyStateLocked(ConnectionState.Disconnected, null) }
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
            factory.dispose()
        }
    }

    private fun createPeerLocked(): PeerConnection? {
        val configuration = PeerConnection.RTCConfiguration(
            iceServerUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return factory.createPeerConnection(configuration, Observer(generation)).also { peer = it }
    }

    private fun applyRemoteDescriptionLocked(
        message: SignalMessage,
        type: SessionDescription.Type,
        createAnswer: Boolean
    ) {
        val description = message.payload["sdp"] ?: return
        val connection = peer ?: return
        val activeGeneration = generation
        connection.setRemoteDescription(object : EmptySdpObserver() {
            override fun onSetSuccess() {
                synchronized(lock) {
                    if (generation != activeGeneration || peer !== connection) return
                    remoteDescriptionSet = true
                    pendingCandidates.forEach(connection::addIceCandidate)
                    pendingCandidates.clear()
                    if (createAnswer) {
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
        }, SessionDescription(type, description))
    }

    private fun addCandidateLocked(message: SignalMessage) {
        val candidate = IceCandidate(
            message.payload["sdp_mid"],
            message.payload["sdp_mline_index"]?.toIntOrNull() ?: return,
            message.payload["candidate"] ?: return
        )
        val connection = peer ?: return
        if (remoteDescriptionSet) connection.addIceCandidate(candidate) else pendingCandidates += candidate
    }

    private fun registerChannelLocked(value: DataChannel?): DataChannel? {
        val previous = channel
        channel = value
        value?.registerObserver(ChannelObserver(value))
        return previous
    }

    private fun detachPeerLocked(): PeerResources {
        val resources = PeerResources(channel, peer)
        channel = null
        peer = null
        sessionId = null
        pendingCandidates.clear()
        remoteDescriptionSet = false
        generation += 1
        return resources
    }

    private fun releasePeer(resources: PeerResources) {
        resources.channel?.let(::releaseChannel)
        runCatching { resources.peer?.close() }
        runCatching { resources.peer?.dispose() }
    }

    private fun releaseChannel(value: DataChannel) {
        runCatching { value.unregisterObserver() }
        runCatching { value.close() }
        runCatching { value.dispose() }
    }

    private fun notifyStateLocked(state: ConnectionState, detail: String?) {
        currentState.set(state)
        stateListener?.invoke(state, detail)
    }

    private inner class LocalDescriptionObserver(
        private val messageType: SignalMessageType,
        private val observerGeneration: Long
    ) : EmptySdpObserver() {
        override fun onCreateSuccess(description: SessionDescription) {
            val connection = synchronized(lock) {
                if (generation != observerGeneration) null else peer
            } ?: return
            connection.setLocalDescription(object : EmptySdpObserver() {
                override fun onSetSuccess() {
                    val activeSession = synchronized(lock) {
                        if (generation != observerGeneration || peer !== connection) null else sessionId
                    } ?: return
                    signaling.send(
                        SignalMessage(
                            messageType = messageType,
                            sessionId = activeSession,
                            payload = mapOf("sdp" to description.description)
                        )
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

        override fun onCreateFailure(error: String) {
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
                    PeerConnection.IceConnectionState.FAILED -> notifyStateLocked(ConnectionState.Failed, "ICE connection failed")
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED -> notifyStateLocked(ConnectionState.Disconnected, null)
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
            signaling.send(
                SignalMessage(
                    messageType = SignalMessageType.IceCandidate,
                    sessionId = activeSession,
                    payload = mapOf(
                        "candidate" to candidate.sdp,
                        "sdp_mid" to (candidate.sdpMid ?: "0"),
                        "sdp_mline_index" to candidate.sdpMLineIndex.toString()
                    )
                )
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(dataChannel: DataChannel) {
            val previous = synchronized(lock) {
                if (generation != observerGeneration) return@synchronized dataChannel
                registerChannelLocked(dataChannel)
            }
            if (previous != null) releaseChannel(previous)
        }

        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
    }

    private inner class ChannelObserver(
        private val observedChannel: DataChannel
    ) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            synchronized(lock) {
                if (channel !== observedChannel) return
                when (observedChannel.state()) {
                    DataChannel.State.OPEN -> notifyStateLocked(ConnectionState.Connected, null)
                    DataChannel.State.CLOSED -> notifyStateLocked(ConnectionState.Disconnected, null)
                    DataChannel.State.CLOSING,
                    DataChannel.State.CONNECTING -> Unit
                }
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (!buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            when (val decoded = PcmFrameCodec.decode(bytes)) {
                is PcmFrameDecodeResult.Success -> synchronized(lock) { frameListener }?.invoke(decoded.frame)
                is PcmFrameDecodeResult.Failure -> Unit
            }
        }
    }

    private open class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private data class PeerResources(
        val channel: DataChannel?,
        val peer: PeerConnection?
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
