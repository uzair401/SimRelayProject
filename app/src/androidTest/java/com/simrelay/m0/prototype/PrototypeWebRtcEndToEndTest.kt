package com.simrelay.m0.prototype

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simrelay.m0.audio.fake.FakeCallAudioBackend
import com.simrelay.prototype.call.FakeCallControlBackend
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.media.android.WebRtcPcmMediaTransport
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.SignalingTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val hostMedia = WebRtcPcmMediaTransport(context, hostSignal, emptyList())
        val remoteMedia: MediaTransport = WebRtcPcmMediaTransport(context, remoteSignal, emptyList())
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
        coordinator.connect()
        hostSignal.emit(SignalMessage(messageType = SignalMessageType.PairSuccess))

        repeat(2) { index ->
            Log.i("PrototypeE2ETest", "step=call_start index=$index")
            val received = CountDownLatch(1)
            val connected = CountDownLatch(1)
            remoteMedia.setFrameListener { received.countDown() }
            remoteMedia.setStateListener { state, detail ->
                Log.i("PrototypeE2ETest", "remote_state=$state detail=$detail index=$index")
                if (state == ConnectionState.Connected) connected.countDown()
            }
            coordinator.simulateIncomingCall()
            val sessionId = coordinator.snapshot().sessionId ?: error("missing session")
            hostSignal.emit(SignalMessage(messageType = SignalMessageType.Answer, sessionId = sessionId))
            assertTrue("remote connection did not open for call $index", connected.await(15, TimeUnit.SECONDS))
            assertTrue("fake downlink did not reach remote for call $index", received.await(5, TimeUnit.SECONDS))

            val format = PrototypeAudioFormat(16_000)
            assertTrue(
                "remote media send failed for call $index",
                remoteMedia.send(
                    PrototypeAudioFrame(
                        format,
                        index.toLong(),
                        System.nanoTime(),
                        ShortArray(format.samplesPerFrame) { 700 }
                    )
                )
            )
            waitUntil { coordinator.snapshot().txFrames > index }
            Log.i("PrototypeE2ETest", "step=tx_received index=$index")
            coordinator.hangup()
            waitUntil { coordinator.snapshot().callState == PrototypeCallState.Idle }
            Log.i("PrototypeE2ETest", "step=call_ended index=$index")
        }

        assertTrue("expected fake RX frames across both calls", coordinator.snapshot().rxFrames > 1)
        assertTrue("expected fake TX frames across both calls", audio.metrics().txFrames >= 2)
        remoteMedia.close()
        coordinator.close()
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private class HostSignal : SignalingTransport {
        override var state = ConnectionState.Disconnected
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
            ) remote?.handleSignal(message)
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
        override fun connect() = Unit
        override fun send(message: SignalMessage): Boolean {
            Log.i("PrototypeE2ETest", "remote_signal=${message.messageType} session=${message.sessionId}")
            host.emit(message)
            return true
        }
        override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) = Unit
        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) = Unit
        override fun close() = Unit
    }
}
