package com.simrelay.m0.audio.fake

import com.simrelay.m0.audio.BackendResult
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.PcmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCallAudioBackendTest {
    @Test
    fun producesDeterministicTwentyMillisecondFrames() {
        var time = 1L
        val backend = FakeCallAudioBackend { time++ }
        val opened = backend.openDownlink(PcmConfig(16_000)) as BackendResult.Success
        val session = opened.value
        session.start()
        val first = ShortArray(320)
        val second = ShortArray(320)

        assertEquals(320, (session.read(first) as BackendResult.Success).value)
        assertEquals(320, (session.read(second) as BackendResult.Success).value)

        assertTrue(first.any { it.toInt() != 0 })
        assertTrue(first.contentEquals(second))
        assertEquals(2, backend.metrics().rxFrames)
        assertEquals(1_280, backend.metrics().rxBytes)
    }

    @Test
    fun acceptsRemoteTxAndAllowsRepeatedSessions() {
        val backend = FakeCallAudioBackend()
        repeat(2) {
            val opened = backend.openUplink(PcmConfig(16_000)) as BackendResult.Success
            val session = opened.value
            session.start()
            assertEquals(320, (session.write(ShortArray(320) { 400 }) as BackendResult.Success).value)
            session.stop()
            session.close()
        }

        assertEquals(2, backend.metrics().txFrames)
        assertTrue(backend.metrics().txRms > 0.0)
    }

    @Test
    fun preventsDuplicateSessionsAndCleansUpIdempotently() {
        val backend = FakeCallAudioBackend()
        val first = (backend.openDownlink(PcmConfig(16_000)) as BackendResult.Success).value

        val duplicate = backend.openDownlink(PcmConfig(16_000)) as BackendResult.Failure
        assertEquals(CapabilityState.ResourceBusy, duplicate.capability.state)

        first.close()
        first.close()
        assertTrue(backend.openDownlink(PcmConfig(16_000)) is BackendResult.Success)
    }
}
