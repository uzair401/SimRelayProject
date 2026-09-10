package com.simrelay.m0.prototype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import com.simrelay.prototype.protocol.SignalMessage
import com.simrelay.prototype.transport.ConnectionState
import com.simrelay.prototype.transport.MediaTransport
import com.simrelay.prototype.transport.MediaTransportStats
import com.simrelay.prototype.transport.SignalingTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeBackendWiringTest {
    @Test
    fun coordinatorAcceptsFakeAndFrameworkAudioWithoutStructuralChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PrototypeHostBackendFactory(context)
        val expected = listOf(
            PrototypeAudioBackendChoice.Fake to AudioBackendId.FakeDevelopment,
            PrototypeAudioBackendChoice.FrameworkInterception to AudioBackendId.FrameworkInterception
        )

        expected.forEach { (choice, backendId) ->
            val backends = factory.create(PrototypeHostConfiguration(audioBackend = choice))
            val coordinator = PrototypeSessionCoordinator(
                backends.callControl,
                backends.audio,
                NoopSignalingTransport(),
                NoopMediaTransport(),
                audioConfig = backends.audioConfig
            )

            assertEquals(backendId, backends.audio.id)
            assertNotNull(backends.audio.probe())
            assertNull(coordinator.snapshot().sessionId)
            coordinator.close()
        }
    }

    private class NoopSignalingTransport : SignalingTransport {
        override val state = ConnectionState.Disconnected
        override fun connect() = Unit
        override fun send(message: SignalMessage) = false
        override fun setMessageListener(listener: ((SignalMessage) -> Unit)?) = Unit
        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) = Unit
        override fun close() = Unit
    }

    private class NoopMediaTransport : MediaTransport {
        override val state = ConnectionState.Disconnected
        override val pcmFormat = PrototypeAudioFormat(48_000)
        override fun start(sessionId: String, initiator: Boolean) = Unit
        override fun handleSignal(message: SignalMessage) = Unit
        override fun send(frame: PrototypeAudioFrame) = false
        override fun stop(reason: String) = Unit
        override fun requestStats(callback: (MediaTransportStats) -> Unit) = callback(MediaTransportStats())
        override fun setFrameListener(listener: ((PrototypeAudioFrame) -> Unit)?) = Unit
        override fun setStateListener(listener: ((ConnectionState, String?) -> Unit)?) = Unit
        override fun close() = Unit
    }
}
