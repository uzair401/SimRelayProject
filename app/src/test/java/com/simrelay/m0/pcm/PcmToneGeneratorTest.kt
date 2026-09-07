package com.simrelay.m0.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmToneGeneratorTest {
    @Test
    fun producesExpectedSampleCount() {
        val samples = PcmToneGenerator.generate(sampleRateHz = 16_000, durationMillis = 250)

        assertEquals(4_000, samples.size)
    }

    @Test
    fun peakRemainsBelowClipping() {
        val samples = PcmToneGenerator.generate(sampleRateHz = 48_000, durationMillis = 100)

        assertTrue(samples.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE)
    }
}
