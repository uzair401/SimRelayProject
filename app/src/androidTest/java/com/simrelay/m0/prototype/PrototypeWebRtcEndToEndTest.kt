package com.simrelay.m0.prototype

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simrelay.m0.audio.fake.FakeCallAudioBackend
import com.simrelay.prototype.call.FakeCallControlBackend
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.media.android.WebRtcAudioMediaTransport
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.MediaTransportStats
import com.simrelay.prototype.transport.SignalingTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeWebRtcEndToEndTest {
    @Test
    fun fakeHostAudioTraversesWebRtcAndReturnsRemoteAudioAcrossTwoCalls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hostSignal = HostSignal()
        val remoteSignal = RemoteSignal(hostSignal)
        val hostMedia = WebRtcAudioMediaTransport(context, hostSignal, emptyList())
        val remoteMedia: MediaTransport = WebRtcAudioMediaTransport(context, remoteSignal, emptyList())
        hostSignal.remote = remoteMedia
        val audio = FakeCallAudioBackend()
        val ids = ArrayDeque(listOf("first", "second"))
        val coordinator = PrototypeSessionCoordinator(
            FakeCallControlBackend(),
            audio,
            hostSignal,
            hostMedia,
            logger = PrototypeEventLogger { event, fields ->
                Log.i("PrototypeE2ETest", "event=$event fields=$fields")
            },
            idFactory = { ids.removeFirst() }
        )
        try {
            coordinator.connect()
            hostSignal.emit(SignalMessage(messageType = SignalMessageType.PairSuccess))

            repeat(2) { index ->
                Log.i("PrototypeE2ETest", "step=call_start index=$index")
                val received = CountDownLatch(1)
                val connected = CountDownLatch(1)
                remoteMedia.setFrameListener { frame ->
                    if (frame.samples.any { abs(it.toInt()) > 250 }) received.countDown()
                }
                remoteMedia.setStateListener { state, detail ->
                    Log.i("PrototypeE2ETest", "remote_state=$state detail=$detail index=$index")
                    if (state == ConnectionState.Connected) connected.countDown()
                }
                coordinator.simulateIncomingCall()
                val sessionId = coordinator.snapshot().sessionId ?: error("missing session")
                hostSignal.emit(SignalMessage(messageType = SignalMessageType.Answer, sessionId = sessionId))
                assertTrue("remote connection did not open for call $index", connected.await(15, TimeUnit.SECONDS))
                assertTrue("fake downlink did not reach remote for call $index", received.await(5, TimeUnit.SECONDS))

                val format = remoteMedia.pcmFormat
                repeat(10) { frameIndex ->
                    assertTrue(
                        "remote media send failed for call $index",
                        remoteMedia.send(
                            PrototypeAudioFrame(
                                format,
                                (index * 10L) + frameIndex,
                                System.nanoTime(),
                                ShortArray(format.samplesPerFrame) { sampleIndex ->
                                    if ((sampleIndex / 24) % 2 == 0) 4_000 else -4_000
                                }
                            )
                        )
                    )
                    Thread.sleep(20)
                }
                waitUntil { audio.metrics().txPeak > 500 }
                assertRtpStats(waitForRtpStats(hostMedia))
                assertRtpStats(waitForRtpStats(remoteMedia))
                Log.i("PrototypeE2ETest", "step=tx_received index=$index")
                coordinator.hangup()
                waitUntil { coordinator.snapshot().callState == PrototypeCallState.Idle }
                Log.i("PrototypeE2ETest", "step=call_ended index=$index")
            }

            assertTrue("expected fake RX frames across both calls", coordinator.snapshot().rxFrames > 1)
            assertTrue("expected fake TX frames across both calls", audio.metrics().txFrames >= 2)
        } finally {
            remoteMedia.close()
            coordinator.close()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun requestStats(media: MediaTransport): MediaTransportStats {
        val ready = CountDownLatch(1)
        val value = AtomicReference<MediaTransportStats>()
        media.requestStats {
            value.set(it)
            ready.countDown()
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return value.get()
    }

    private fun waitForRtpStats(media: MediaTransport): MediaTransportStats {
        var latest = MediaTransportStats()
        repeat(50) {
            latest = requestStats(media)
            if (latest.outboundPackets > 0 && latest.inboundPackets > 0) return latest
            Thread.sleep(50)
        }
        return latest
    }

    private fun assertRtpStats(stats: MediaTransportStats) {
        assertEquals("audio/opus", stats.codec?.lowercase())
        assertTrue(stats.outboundPackets > 0)
        assertTrue(stats.inboundPackets > 0)
        assertTrue(stats.outboundBytes > 0)
        assertTrue(stats.inboundBytes > 0)
    }

    private class HostSignal : SignalingTransport {
        override var state = ConnectionState.Disconnected
        private val handler = Handler(Looper.getMainLooper())
        var remote: MediaTransport? = null
        private var messageCallback: ((SignalMessage) -> Unit)? = null
        private var stateCallback: ((ConnectionState, String?) -> Unit)? = null

        override fun connect() {
            state = ConnectionState.Connected
            stateCallback?.invoke(state, null)
        }

        override fun send(message: SignalMessage): Boolean {
            Log.i("PrototypeE2ETest", "host_signal=${message.messageType} session=${message.sessionId}")
            if (message.messageType in setOf(
                    SignalMessageType.MediaOffer,
                    SignalMessageType.MediaAnswer,
                    SignalMessageType.IceCandidate
                )
            ) handler.post { remote?.handleSignal(message) }
            if (message.messageType == SignalMessageType.Hangup) remote?.stop("hangup")
            return true
        }

        override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) {
            messageCallback = listener
        }

        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) {
            stateCallback = listener
        }

        fun emit(message: SignalMessage) = messageCallback?.invoke(message)

        override fun close() {
            state = ConnectionState.Disconnected
            stateCallback?.invoke(state, null)
        }
    }

    private class RemoteSignal(
        private val host: HostSignal
    ) : SignalingTransport {
        override val state = ConnectionState.Connected
        private val handler = Handler(Looper.getMainLooper())
        override fun connect() = Unit
        override fun send(message: SignalMessage): Boolean {
            Log.i("PrototypeE2ETest", "remote_signal=${message.messageType} session=${message.sessionId}")
            handler.post { host.emit(message) }
            return true
        }
        override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) = Unit
        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) = Unit
        override fun close() = Unit
    }
}
