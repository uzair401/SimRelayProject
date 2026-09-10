package com.simrelay.m0.prototype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simrelay.m0.audio.fake.FakeCallAudioBackend
import com.simrelay.prototype.call.FakeCallControlBackend
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.media.android.WebRtcAudioMediaTransport
import com.simrelay.prototype.pairing.DirectPairingPayload
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.DirectPeerConfiguration
import com.simrelay.prototype.transport.DirectPeerSignalingTransport
import com.simrelay.prototype.transport.MediaTransportStats
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectPeerWebRtcEndToEndTest {
    @Test
    fun backendlessIncomingOutgoingRejectDisconnectReconnectAndSecondCallUseOpusRtp() {
        val first = Harness("123456789")
        try {
            first.connect()
            first.runIncomingCall()
            first.runReject()
            first.runOutgoingCall()
            first.runActiveDisconnect()
        } finally {
            first.close()
        }

        val second = Harness("987654321")
        try {
            second.connect()
            second.runIncomingCall()
        } finally {
            second.close()
        }
    }

    private class Harness(token: String) : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val port = ServerSocket(0).use { it.localPort }
        private val expiresAt = System.currentTimeMillis() + 60_000
        private val hostSignal = DirectPeerSignalingTransport(
            DirectPeerConfiguration.Host(port, token, expiresAt)
        )
        private val clientSignal = DirectPeerSignalingTransport(
            DirectPeerConfiguration.Client(DirectPairingPayload("127.0.0.1", port, token, expiresAt))
        )
        private val hostMedia = WebRtcAudioMediaTransport(context, hostSignal)
        private val clientMedia = WebRtcAudioMediaTransport(context, clientSignal)
        private val audio = FakeCallAudioBackend()
        private val coordinator = PrototypeSessionCoordinator(
            FakeCallControlBackend(),
            audio,
            hostSignal,
            hostMedia
        )
        private val clientMessages = CopyOnWriteArrayList<SignalMessage>()
        private val receivedPeak = AtomicLong()
        private val token = token
        @Volatile
        private var clientPaired = false

        init {
            clientSignal.setStateListener { state, _ ->
                if (state == ConnectionState.Connected) {
                    clientSignal.send(
                        SignalMessage(
                            messageType = SignalMessageType.ClientOnline,
                            payload = mapOf("client_id" to "direct-test-client")
                        )
                    )
                    clientSignal.send(
                        SignalMessage(
                            messageType = SignalMessageType.PairRequest,
                            payload = mapOf("pairing_code" to token)
                        )
                    )
                }
            }
            clientSignal.setMessageListener(::onClientSignal)
            clientMedia.setFrameListener { frame ->
                receivedPeak.accumulateAndGet(
                    frame.samples.maxOfOrNull { abs(it.toInt()).toLong() } ?: 0,
                    ::maxOf
                )
            }
        }

        fun connect() {
            coordinator.connect()
            Thread.sleep(100)
            clientSignal.connect()
            waitUntil { coordinator.snapshot().paired && clientPaired }
        }

        fun runIncomingCall() {
            receivedPeak.set(0)
            assertTrue(coordinator.simulateIncomingCall() is com.simrelay.prototype.call.CallControlResult.Success)
            val sessionId = waitForMessage(SignalMessageType.IncomingCall).sessionId ?: error("missing session")
            assertTrue(clientSignal.send(SignalMessage(messageType = SignalMessageType.Answer, sessionId = sessionId)))
            waitUntil { coordinator.snapshot().callState == PrototypeCallState.Active }
            waitUntil { hostMedia.state == ConnectionState.Connected && clientMedia.state == ConnectionState.Connected }
            sendClientTone()
            waitUntil { audio.metrics().txPeak > 500 && receivedPeak.get() > 250 }
            assertOpusRtp(waitForRtpStats(hostMedia))
            assertOpusRtp(waitForRtpStats(clientMedia))
            assertTrue(clientSignal.send(SignalMessage(messageType = SignalMessageType.Hangup, sessionId = sessionId)))
            clientMedia.stop("hangup")
            waitUntil { coordinator.snapshot().callState == PrototypeCallState.Idle }
        }

        fun runReject() {
            val previousCount = clientMessages.size
            assertTrue(coordinator.simulateIncomingCall() is com.simrelay.prototype.call.CallControlResult.Success)
            val incoming = waitForNewMessage(SignalMessageType.IncomingCall, previousCount)
            assertTrue(
                clientSignal.send(
                    SignalMessage(messageType = SignalMessageType.Reject, sessionId = incoming.sessionId)
                )
            )
            waitUntil { coordinator.snapshot().callState == PrototypeCallState.Idle }
            assertFalse(hostMedia.state == ConnectionState.Connected)
        }

        fun runOutgoingCall() {
            receivedPeak.set(0)
            val sessionId = "direct-outgoing-${System.nanoTime()}"
            assertTrue(
                clientSignal.send(
                    SignalMessage(
                        messageType = SignalMessageType.OutgoingCall,
                        sessionId = sessionId,
                        payload = mapOf("display_identity" to "Prototype destination")
                    )
                )
            )
            waitUntil { coordinator.snapshot().callState == PrototypeCallState.Active }
            waitUntil { hostMedia.state == ConnectionState.Connected && clientMedia.state == ConnectionState.Connected }
            sendClientTone()
            waitUntil { audio.metrics().txPeak > 500 && receivedPeak.get() > 250 }
            assertOpusRtp(waitForRtpStats(hostMedia))
            assertTrue(clientSignal.send(SignalMessage(messageType = SignalMessageType.Hangup, sessionId = sessionId)))
            clientMedia.stop("hangup")
            waitUntil { coordinator.snapshot().callState == PrototypeCallState.Idle }
        }

        fun runActiveDisconnect() {
            val sessionId = "direct-disconnect-${System.nanoTime()}"
            assertTrue(
                clientSignal.send(
                    SignalMessage(
                        messageType = SignalMessageType.OutgoingCall,
                        sessionId = sessionId,
                        payload = mapOf("display_identity" to "Prototype destination")
                    )
                )
            )
            waitUntil { coordinator.snapshot().callState == PrototypeCallState.Active }
            clientMedia.close()
            clientSignal.close()
            waitUntil {
                coordinator.snapshot().callState == PrototypeCallState.Idle &&
                    !coordinator.snapshot().paired &&
                    coordinator.snapshot().mediaState == ConnectionState.Disconnected
            }
            assertEquals(null, coordinator.snapshot().lastError)
        }

        override fun close() {
            clientMedia.close()
            clientSignal.close()
            coordinator.close()
        }

        private fun onClientSignal(message: SignalMessage) {
            clientMessages += message
            when (message.messageType) {
                SignalMessageType.PairSuccess -> clientPaired = true
                SignalMessageType.MediaOffer,
                SignalMessageType.MediaAnswer,
                SignalMessageType.IceCandidate -> clientMedia.handleSignal(message)
                SignalMessageType.Hangup,
                SignalMessageType.Reject,
                SignalMessageType.PeerDisconnected -> clientMedia.stop(message.messageType.name)
                SignalMessageType.CallState -> {
                    if (message.payload["state"] in setOf("idle", "ended", "rejected", "failure")) {
                        clientMedia.stop("call_state")
                    }
                }
                SignalMessageType.PairFailed,
                SignalMessageType.SessionError -> error(message.payload["reason"] ?: message.messageType.name)
                SignalMessageType.HostOnline,
                SignalMessageType.ClientOnline,
                SignalMessageType.PairRequest,
                SignalMessageType.IncomingCall,
                SignalMessageType.OutgoingCall,
                SignalMessageType.Answer -> Unit
            }
        }

        private fun sendClientTone() {
            val format = clientMedia.pcmFormat
            repeat(25) { index ->
                assertTrue(
                    clientMedia.send(
                        PrototypeAudioFrame(
                            format,
                            index.toLong(),
                            System.nanoTime(),
                            ShortArray(format.samplesPerFrame) { sample ->
                                if ((sample / 24) % 2 == 0) 4_000 else -4_000
                            }
                        )
                    )
                )
                Thread.sleep(20)
            }
        }

        private fun waitForMessage(type: SignalMessageType): SignalMessage {
            waitUntil { clientMessages.any { it.messageType == type } }
            return clientMessages.last { it.messageType == type }
        }

        private fun waitForNewMessage(type: SignalMessageType, previousCount: Int): SignalMessage {
            waitUntil { clientMessages.drop(previousCount).any { it.messageType == type } }
            return clientMessages.drop(previousCount).last { it.messageType == type }
        }

        private fun waitForRtpStats(media: WebRtcAudioMediaTransport): MediaTransportStats {
            var latest = MediaTransportStats()
            repeat(50) {
                val ready = CountDownLatch(1)
                val value = AtomicReference<MediaTransportStats>()
                media.requestStats { stats ->
                    value.set(stats)
                    ready.countDown()
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                latest = value.get()
                if (latest.outboundPackets > 0 && latest.inboundPackets > 0) return latest
                Thread.sleep(50)
            }
            return latest
        }

        private fun assertOpusRtp(stats: MediaTransportStats) {
            assertEquals("audio/opus", stats.codec?.lowercase())
            assertTrue(stats.outboundPackets > 0)
            assertTrue(stats.inboundPackets > 0)
            assertTrue(stats.outboundBytes > 0)
            assertTrue(stats.inboundBytes > 0)
        }

        private fun waitUntil(condition: () -> Boolean) {
            repeat(1_500) {
                if (condition()) return
                Thread.sleep(10)
            }
            assertTrue(condition())
        }
    }
}
