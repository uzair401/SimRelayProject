package com.simrelay.m0.prototype

import com.simrelay.m0.audio.fake.FakeCallAudioBackend
import com.simrelay.prototype.call.FakeCallControlBackend
import com.simrelay.prototype.call.PrototypeCallState
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.protocol.SignalMessageType
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.SignalingTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeSessionCoordinatorTest {
    @Test
    fun incomingCallRoutesBidirectionalFramesAndAllowsSecondCall() {
        val signaling = FakeSignalingTransport()
        val media = FakeMediaTransport()
        val audio = FakeCallAudioBackend()
        val ids = ArrayDeque(listOf("first", "second"))
        val coordinator = PrototypeSessionCoordinator(
            FakeCallControlBackend(),
            audio,
            signaling,
            media,
            idFactory = { ids.removeFirst() }
        )
        coordinator.connect()
        signaling.emit(SignalMessage(messageType = SignalMessageType.PairSuccess))

        coordinator.simulateIncomingCall()
        assertEquals(PrototypeCallState.Ringing, coordinator.snapshot().callState)
        assertTrue(signaling.sent.any { it.messageType == SignalMessageType.IncomingCall && it.sessionId == "first" })
        signaling.emit(SignalMessage(messageType = SignalMessageType.Answer, sessionId = "first"))
        media.connectCurrent()
        waitUntil { coordinator.snapshot().rxFrames > 0 }
        media.emitFrame()
        waitUntil { coordinator.snapshot().txFrames > 0 }
        signaling.emit(SignalMessage(messageType = SignalMessageType.Hangup, sessionId = "first"))

        assertEquals(PrototypeCallState.Idle, coordinator.snapshot().callState)
        assertNull(coordinator.snapshot().sessionId)
        assertTrue(media.stopCount > 0)

        coordinator.simulateIncomingCall()
        assertEquals("second", coordinator.snapshot().sessionId)
        assertEquals(PrototypeCallState.Ringing, coordinator.snapshot().callState)
        coordinator.close()
    }

    @Test
    fun rejectAndDisconnectTearDownWithoutMediaLeak() {
        val signaling = FakeSignalingTransport()
        val media = FakeMediaTransport()
        val coordinator = PrototypeSessionCoordinator(
            FakeCallControlBackend(),
            FakeCallAudioBackend(),
            signaling,
            media,
            idFactory = { "call" }
        )
        coordinator.connect()
        signaling.emit(SignalMessage(messageType = SignalMessageType.PairSuccess))
        coordinator.simulateIncomingCall()
        signaling.emit(SignalMessage(messageType = SignalMessageType.Reject, sessionId = "call"))

        assertEquals(PrototypeCallState.Idle, coordinator.snapshot().callState)
        assertEquals(0, media.startCount)

        signaling.emit(
            SignalMessage(
                messageType = SignalMessageType.OutgoingCall,
                sessionId = "outgoing",
                payload = mapOf("display_identity" to "Prototype destination")
            )
        )
        media.connectCurrent()
        signaling.disconnect()

        assertEquals(PrototypeCallState.Idle, coordinator.snapshot().callState)
        assertNull(coordinator.snapshot().sessionId)
        coordinator.close()
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue(condition())
    }

    private class FakeSignalingTransport : SignalingTransport {
        override var state = ConnectionState.Disconnected
        var messageCallback: ((SignalMessage) -> Unit)? = null
        var stateCallback: ((ConnectionState, String?) -> Unit)? = null
        val sent = mutableListOf<SignalMessage>()

        override fun connect() {
            state = ConnectionState.Connected
            stateCallback?.invoke(state, null)
        }

        override fun send(message: SignalMessage): Boolean {
            if (state != ConnectionState.Connected) return false
            sent += message
            return true
        }

        override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) {
            messageCallback = listener
        }

        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) {
            stateCallback = listener
        }

        fun emit(message: SignalMessage) = messageCallback?.invoke(message)

        fun disconnect() {
            state = ConnectionState.Disconnected
            stateCallback?.invoke(state, null)
        }

        override fun close() = disconnect()
    }

    private class FakeMediaTransport : MediaTransport {
        override var state = ConnectionState.Disconnected
        var frameCallback: ((PrototypeAudioFrame) -> Unit)? = null
        var stateCallback: ((ConnectionState, String?) -> Unit)? = null
        var startCount = 0
        var stopCount = 0

        override fun start(sessionId: String, initiator: Boolean) {
            startCount += 1
            state = ConnectionState.Connecting
            stateCallback?.invoke(state, null)
        }

        override fun handleSignal(message: SignalMessage) = Unit

        override fun send(frame: PrototypeAudioFrame): Boolean = state == ConnectionState.Connected

        override fun stop(reason: String) {
            stopCount += 1
            state = ConnectionState.Disconnected
            stateCallback?.invoke(state, null)
        }

        override fun setFrameListener(listener: ((PrototypeAudioFrame) -> Unit)?) {
            frameCallback = listener
        }

        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) {
            stateCallback = listener
        }

        fun connectCurrent() {
            state = ConnectionState.Connected
            stateCallback?.invoke(state, null)
        }

        fun emitFrame() {
            val format = PrototypeAudioFormat(16_000)
            frameCallback?.invoke(
                PrototypeAudioFrame(format, 1, 1, ShortArray(format.samplesPerFrame) { 500 })
            )
        }

        override fun close() = stop("closed")
    }
}
