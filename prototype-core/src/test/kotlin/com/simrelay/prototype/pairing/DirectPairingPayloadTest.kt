package com.simrelay.prototype.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPairingPayloadTest {
    @Test
    fun roundTripsIpv4AndIpv6Endpoints() {
        listOf("192.168.1.24", "2001:db8::24").forEach { host ->
            val payload = DirectPairingPayload(host, 38_475, "123456789", 2_000)

            assertEquals(payload, DirectPairingPayloadCodec.decode(DirectPairingPayloadCodec.encode(payload)).getOrThrow())
        }
    }

    @Test
    fun rejectsUnexpectedSchemesFieldsAndInvalidPorts() {
        assertTrue(DirectPairingPayloadCodec.decode("https://192.168.1.24:38475/pair?token=123456&expires=2000").isFailure)
        assertTrue(DirectPairingPayloadCodec.decode("simrelay-direct://192.168.1.24/pair?token=123456&expires=2000").isFailure)
        assertTrue(
            DirectPairingPayloadCodec.decode(
                "simrelay-direct://192.168.1.24:38475/pair?token=123456&expires=2000&extra=value"
            ).isFailure
        )
        assertTrue(
            DirectPairingPayloadCodec.decode(
                "simrelay-direct://192.168.1.24:38475/pair?token=123456&token=654321&expires=2000"
            ).isFailure
        )
    }
}
