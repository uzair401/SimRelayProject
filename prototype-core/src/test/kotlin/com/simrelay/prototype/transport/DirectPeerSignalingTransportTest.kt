package com.simrelay.prototype.transport

import com.simrelay.prototype.pairing.DirectPairingPayload
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPeerSignalingTransportTest {
    @Test
    fun pairsRoutesCallMessagesAndReportsDisconnectWithoutBackend() {
        val port = availablePort()
        val expiresAt = 2_000L
        val token = "123456789"
        val hostMessages = CopyOnWriteArrayList<SignalMessage>()
        val clientMessages = CopyOnWriteArrayList<SignalMessage>()
        val listening = CountDownLatch(1)
        val hostPaired = CountDownLatch(1)
        val clientPaired = CountDownLatch(1)
        val incomingReceived = CountDownLatch(1)
        val answerReceived = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val host = DirectPeerSignalingTransport(
            DirectPeerConfiguration.Host(port, token, expiresAt),
            nowMillis = { 1_000 }
        )
        val client = DirectPeerSignalingTransport(
            DirectPeerConfiguration.Client(DirectPairingPayload("127.0.0.1", port, token, expiresAt)),
            nowMillis = { 1_000 }
        )
        host.setStateListener { state, detail ->
            if (state == ConnectionState.Connecting && detail == "listening") listening.countDown()
        }
        host.setMessageListener { message ->
            hostMessages += message
            if (message.messageType == SignalMessageType.PairSuccess) hostPaired.countDown()
            if (message.messageType == SignalMessageType.Answer) answerReceived.countDown()
            if (message.messageType == SignalMessageType.PeerDisconnected) disconnected.countDown()
        }
        client.setMessageListener { message ->
            clientMessages += message
            if (message.messageType == SignalMessageType.PairSuccess) clientPaired.countDown()
            if (message.messageType == SignalMessageType.IncomingCall) incomingReceived.countDown()
        }

        try {
            host.connect()
            assertTrue(listening.await(2, TimeUnit.SECONDS))
            client.connect()
            waitUntil { host.state == ConnectionState.Connected && client.state == ConnectionState.Connected }
            assertTrue(host.send(hostOnline(token, expiresAt)))
            assertTrue(
                client.send(
                    SignalMessage(
                        messageType = SignalMessageType.ClientOnline,
                        payload = mapOf("client_id" to "client")
                    )
                )
            )
            assertTrue(
                client.send(
                    SignalMessage(
                        messageType = SignalMessageType.PairRequest,
                        payload = mapOf("pairing_code" to token)
                    )
                )
            )
            assertTrue(hostPaired.await(2, TimeUnit.SECONDS))
            assertTrue(clientPaired.await(2, TimeUnit.SECONDS))

            assertTrue(
                host.send(
                    SignalMessage(
                        messageType = SignalMessageType.IncomingCall,
                        sessionId = "direct-call",
                        payload = mapOf("display_identity" to "Prototype caller")
                    )
                )
            )
            assertTrue(incomingReceived.await(2, TimeUnit.SECONDS))
            assertTrue(
                client.send(
                    SignalMessage(messageType = SignalMessageType.Answer, sessionId = "direct-call")
                )
            )
            assertTrue(answerReceived.await(2, TimeUnit.SECONDS))

            client.close()
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            assertTrue(hostMessages.none { it.messageType == SignalMessageType.PairRequest })
            assertTrue(clientMessages.any { it.messageType == SignalMessageType.PairSuccess })
        } finally {
            client.close()
            host.close()
        }
    }

    @Test
    fun rejectsWrongPairingCredentials() {
        val port = availablePort()
        val listening = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val host = DirectPeerSignalingTransport(
            DirectPeerConfiguration.Host(port, "123456789", 2_000),
            nowMillis = { 1_000 }
        )
        val client = DirectPeerSignalingTransport(
            DirectPeerConfiguration.Client(DirectPairingPayload("127.0.0.1", port, "987654321", 2_000)),
            nowMillis = { 1_000 }
        )
        host.setStateListener { state, detail ->
            if (state == ConnectionState.Connecting && detail == "listening") listening.countDown()
        }
        client.setMessageListener { if (it.messageType == SignalMessageType.PairFailed) rejected.countDown() }

        try {
            host.connect()
            assertTrue(listening.await(2, TimeUnit.SECONDS))
            client.connect()
            waitUntil { client.state == ConnectionState.Connected }
            assertTrue(
                client.send(
                    SignalMessage(
                        messageType = SignalMessageType.ClientOnline,
                        payload = mapOf("client_id" to "client")
                    )
                )
            )
            assertTrue(
                client.send(
                    SignalMessage(
                        messageType = SignalMessageType.PairRequest,
                        payload = mapOf("pairing_code" to "987654321")
                    )
                )
            )

            assertTrue(rejected.await(2, TimeUnit.SECONDS))
            assertFalse(
                host.send(
                    SignalMessage(messageType = SignalMessageType.Answer, sessionId = "not-paired")
                )
            )
        } finally {
            client.close()
            host.close()
        }
    }

    @Test
    fun rejectsExpiredClientPayloadBeforeConnecting() {
        val client = DirectPeerSignalingTransport(
            DirectPeerConfiguration.Client(
                DirectPairingPayload("127.0.0.1", availablePort(), "123456789", 999)
            ),
            nowMillis = { 1_000 }
        )
        val failed = CountDownLatch(1)
        client.setStateListener { state, detail ->
            if (state == ConnectionState.Failed && detail == "Direct pairing payload is expired") failed.countDown()
        }

        try {
            client.connect()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
        } finally {
            client.close()
        }
    }

    private fun hostOnline(token: String, expiresAt: Long) = SignalMessage(
        messageType = SignalMessageType.HostOnline,
        payload = mapOf(
            "host_id" to "host",
            "pairing_code" to token,
            "expires_at_ms" to expiresAt.toString()
        )
    )

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
