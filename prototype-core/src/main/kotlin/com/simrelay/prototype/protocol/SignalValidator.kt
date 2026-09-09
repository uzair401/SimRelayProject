package com.simrelay.prototype.protocol

object SignalValidator {
    private val sessionMessages = setOf(
        SignalMessageType.IncomingCall,
        SignalMessageType.OutgoingCall,
        SignalMessageType.Answer,
        SignalMessageType.Reject,
        SignalMessageType.Hangup,
        SignalMessageType.CallState,
        SignalMessageType.MediaOffer,
        SignalMessageType.MediaAnswer,
        SignalMessageType.IceCandidate,
        SignalMessageType.SessionError
    )

    fun validate(message: SignalMessage): String? {
        if (message.protocolVersion != PrototypeProtocolVersion) return "Unsupported protocol version"
        if (message.messageType in sessionMessages && message.sessionId.isNullOrBlank()) {
            return "session_id is required for ${message.messageType.name}"
        }
        if (message.sessionId?.length?.let { it !in 1..128 } == true) return "Invalid session_id length"
        return when (message.messageType) {
            SignalMessageType.HostOnline -> required(message, "host_id", "pairing_code", "expires_at_ms")
            SignalMessageType.ClientOnline -> required(message, "client_id")
            SignalMessageType.PairRequest -> required(message, "pairing_code")
            SignalMessageType.IncomingCall,
            SignalMessageType.OutgoingCall -> required(message, "display_identity")
            SignalMessageType.CallState -> required(message, "state")
            SignalMessageType.MediaOffer,
            SignalMessageType.MediaAnswer -> required(message, "sdp")
            SignalMessageType.IceCandidate -> required(message, "candidate", "sdp_mid", "sdp_mline_index")
            SignalMessageType.PairSuccess,
            SignalMessageType.PairFailed,
            SignalMessageType.Answer,
            SignalMessageType.Reject,
            SignalMessageType.Hangup,
            SignalMessageType.SessionError -> null
        }
    }

    fun requireValid(message: SignalMessage) {
        val failure = validate(message)
        require(failure == null) { failure ?: "Invalid signal message" }
    }

    private fun required(message: SignalMessage, vararg keys: String): String? {
        val missing = keys.firstOrNull { message.payload[it].isNullOrBlank() }
        return missing?.let { "$it is required for ${message.messageType.name}" }
    }
}
