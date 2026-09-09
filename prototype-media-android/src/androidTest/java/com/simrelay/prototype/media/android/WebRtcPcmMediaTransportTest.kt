package com.simrelay.prototype.media.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.SignalingTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRtcPcmMediaTransportTest {
    @Test
    fun exchangesPcmFramesBidirectionallyOverRealPeerConnections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val leftSignal = BridgeSignalingTransport()
        val rightSignal = BridgeSignalingTransport()
        val left = WebRtcPcmMediaTransport(context, leftSignal, emptyList())
        val right = WebRtcPcmMediaTransport(context, rightSignal, emptyList())
        leftSignal.receiver = right::handleSignal
        rightSignal.receiver = left::handleSignal
        val connected = CountDownLatch(2)
        val leftReceived = CountDownLatch(1)
        val rightReceived = CountDownLatch(1)
        left.setStateListener { state, _ -> if (state == ConnectionState.Connected) connected.countDown() }
        right.setStateListener { state, _ -> if (state == ConnectionState.Connected) connected.countDown() }
        left.setFrameListener { leftReceived.countDown() }
        right.setFrameListener { rightReceived.countDown() }

        left.start("loopback", true)

        assertTrue(connected.await(15, TimeUnit.SECONDS))
        val format = PrototypeAudioFormat(16_000)
        assertTrue(left.send(PrototypeAudioFrame(format, 1, 1, ShortArray(format.samplesPerFrame) { 400 })))
        assertTrue(right.send(PrototypeAudioFrame(format, 2, 2, ShortArray(format.samplesPerFrame) { -400 })))
        assertTrue(leftReceived.await(5, TimeUnit.SECONDS))
        assertTrue(rightReceived.await(5, TimeUnit.SECONDS))
        left.close()
        right.close()
    }

    private class BridgeSignalingTransport : SignalingTransport {
        override val state = ConnectionState.Connected
        var receiver: ((SignalMessage) -> Unit)? = null

        override fun connect() = Unit

        override fun send(message: SignalMessage): Boolean {
            receiver?.invoke(message)
            return receiver != null
        }

        override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) = Unit
        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) = Unit
        override fun close() = Unit
    }
}
