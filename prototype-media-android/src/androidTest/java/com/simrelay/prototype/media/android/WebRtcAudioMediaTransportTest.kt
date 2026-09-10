package com.simrelay.prototype.media.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransportStats
import com.simrelay.prototype.transport.SignalingTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRtcAudioMediaTransportTest {
    @Test
    fun exchangesPcmThroughOpusRtpAcrossRepeatedSessions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val leftSignal = BridgeSignalingTransport()
        val rightSignal = BridgeSignalingTransport()
        val left = WebRtcAudioMediaTransport(context, leftSignal, emptyList())
        val right = WebRtcAudioMediaTransport(context, rightSignal, emptyList())
        leftSignal.receiver = right::handleSignal
        rightSignal.receiver = left::handleSignal

        try {
            repeat(2) { sessionIndex ->
                val connected = CountDownLatch(2)
                val leftReceived = CountDownLatch(1)
                val rightReceived = CountDownLatch(1)
                val leftReceivedNanos = AtomicLong()
                val rightReceivedNanos = AtomicLong()
                val sendStartedNanos = AtomicLong(Long.MAX_VALUE)
                left.setStateListener { state, _ -> if (state == ConnectionState.Connected) connected.countDown() }
                right.setStateListener { state, _ -> if (state == ConnectionState.Connected) connected.countDown() }
                left.setFrameListener { frame ->
                    recordNonSilentFrame(frame, sendStartedNanos, leftReceivedNanos, leftReceived)
                }
                right.setFrameListener { frame ->
                    recordNonSilentFrame(frame, sendStartedNanos, rightReceivedNanos, rightReceived)
                }

                left.start("loopback-$sessionIndex", true)

                assertTrue(connected.await(15, TimeUnit.SECONDS))
                val format = left.pcmFormat
                sendStartedNanos.set(System.nanoTime())
                repeat(50) { frameIndex ->
                    assertTrue(
                        left.send(
                            PrototypeAudioFrame(
                                format,
                                frameIndex.toLong(),
                                System.nanoTime(),
                                tone(format.samplesPerFrame, 6_000)
                            )
                        )
                    )
                    assertTrue(
                        right.send(
                            PrototypeAudioFrame(
                                format,
                                frameIndex.toLong(),
                                System.nanoTime(),
                                tone(format.samplesPerFrame, -6_000)
                            )
                        )
                    )
                    Thread.sleep(20)
                }
                assertTrue(leftReceived.await(5, TimeUnit.SECONDS))
                assertTrue(rightReceived.await(5, TimeUnit.SECONDS))
                Log.i(
                    "SimRelayWebRtcTest",
                    "event=loopback_latency session=$sessionIndex left_ms=${(leftReceivedNanos.get() - sendStartedNanos.get()) / 1_000_000} right_ms=${(rightReceivedNanos.get() - sendStartedNanos.get()) / 1_000_000}"
                )
                val statsReady = CountDownLatch(2)
                val statsValues = listOf(AtomicReference<MediaTransportStats>(), AtomicReference<MediaTransportStats>())
                listOf(left, right).forEachIndexed { index, transport ->
                    transport.requestStats { stats ->
                        statsValues[index].set(stats)
                        statsReady.countDown()
                    }
                }
                assertTrue(statsReady.await(5, TimeUnit.SECONDS))
                statsValues.forEach { value ->
                    val stats = value.get()
                    assertEquals("audio/opus", stats.codec?.lowercase())
                    assertTrue(stats.outboundPackets > 0)
                    assertTrue(stats.inboundPackets > 0)
                    assertTrue(stats.outboundBytes > 0)
                    assertTrue(stats.inboundBytes > 0)
                }
                left.stop("session_complete")
                right.stop("session_complete")
            }
        } finally {
            left.close()
            right.close()
        }
    }

    private fun recordNonSilentFrame(
        frame: PrototypeAudioFrame,
        sendStartedNanos: AtomicLong,
        receivedNanos: AtomicLong,
        latch: CountDownLatch
    ) {
        val now = System.nanoTime()
        if (now >= sendStartedNanos.get() && frame.samples.any { abs(it.toInt()) > 250 }) {
            receivedNanos.compareAndSet(0, now)
            latch.countDown()
        }
    }

    private fun tone(sampleCount: Int, amplitude: Int): ShortArray = ShortArray(sampleCount) { index ->
        (sin(2.0 * PI * 1_000.0 * index / 48_000.0) * amplitude).toInt().toShort()
    }

    private class BridgeSignalingTransport : SignalingTransport {
        override val state = ConnectionState.Connected
        private val handler = Handler(Looper.getMainLooper())
        var receiver: ((SignalMessage) -> Unit)? = null

        override fun connect() = Unit

        override fun send(message: SignalMessage): Boolean {
            val target = receiver ?: return false
            handler.post { target(message) }
            return true
        }

        override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) = Unit
        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) = Unit
        override fun close() = Unit
    }
}
