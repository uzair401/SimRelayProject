package com.simrelay.prototype.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PrototypeProtocolVersion = 1

@Serializable
enum class SignalMessageType {
    @SerialName("host_online")
    HostOnline,
    @SerialName("client_online")
    ClientOnline,
    @SerialName("pair_request")
    PairRequest,
    @SerialName("pair_success")
    PairSuccess,
    @SerialName("pair_failed")
    PairFailed,
    @SerialName("peer_disconnected")
    PeerDisconnected,
    @SerialName("incoming_call")
    IncomingCall,
    @SerialName("outgoing_call")
    OutgoingCall,
    @SerialName("answer")
    Answer,
    @SerialName("reject")
    Reject,
    @SerialName("hangup")
    Hangup,
    @SerialName("call_state")
    CallState,
    @SerialName("media_offer")
    MediaOffer,
    @SerialName("media_answer")
    MediaAnswer,
    @SerialName("ice_candidate")
    IceCandidate,
    @SerialName("session_error")
    SessionError
}

@Serializable
data class SignalMessage(
    @SerialName("protocol_version")
    val protocolVersion: Int = PrototypeProtocolVersion,
    @SerialName("message_type")
    val messageType: SignalMessageType,
    @SerialName("session_id")
    val sessionId: String? = null,
    val payload: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms")
    val timestampMillis: Long? = null
)
