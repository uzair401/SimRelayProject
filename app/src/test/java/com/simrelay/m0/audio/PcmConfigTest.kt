package com.simrelay.m0.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmConfigTest {
    @Test
    fun createsTwentyMillisecondFramesForEveryCandidateRate() {
        assertEquals(listOf(960, 320, 160), PcmConfig.Candidates.map { it.frameSampleCount() })
    }
}
