package com.simrelay.prototype.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCodecTest {
    @Test
    fun roundTripsVersionedMessage() {
        val message = SignalMessage(
            messageType = SignalMessageType.IncomingCall,
            sessionId = "session-1",
            payload = mapOf("display_identity" to "Prototype caller")
        )

        val decoded = SignalCodec.decode(SignalCodec.encode(message))

        assertEquals(SignalDecodeResult.Success(message), decoded)
    }

    @Test
    fun rejectsMalformedJsonAndMissingSession() {
        assertTrue(SignalCodec.decode("{") is SignalDecodeResult.Failure)
        val missingSession = """{"protocol_version":1,"message_type":"answer","payload":{}}"""
        assertEquals(
            SignalDecodeResult.Failure("session_id is required for Answer"),
            SignalCodec.decode(missingSession)
        )
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val encoded = """{"protocol_version":9,"message_type":"client_online","payload":{"client_id":"client"}}"""

        assertEquals(
            SignalDecodeResult.Failure("Unsupported protocol version"),
            SignalCodec.decode(encoded)
        )
    }

    @Test
    fun peerDisconnectedDoesNotRequireAnActiveSession() {
        val message = SignalMessage(messageType = SignalMessageType.PeerDisconnected)

        assertEquals(SignalDecodeResult.Success(message), SignalCodec.decode(SignalCodec.encode(message)))
    }
}
