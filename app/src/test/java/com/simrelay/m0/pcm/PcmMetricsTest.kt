package com.simrelay.m0.pcm

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMetricsTest {
    @Test
    fun calculatesRmsPeakCountAndDuration() {
        val metrics = PcmMetrics.calculate(shortArrayOf(3, 4, -3, -4), sampleRateHz = 2)

        assertEquals(sqrt(12.5), metrics.rms, 0.0001)
        assertEquals(4, metrics.peakAbsolute)
        assertEquals(4, metrics.sampleCount)
        assertEquals(2_000, metrics.durationMillis)
    }

    @Test
    fun silenceHasZeroRms() {
        val metrics = PcmMetrics.calculate(ShortArray(32), sampleRateHz = 8_000)

        assertEquals(0.0, metrics.rms, 0.0)
        assertEquals(0, metrics.peakAbsolute)
        assertTrue(metrics.isExactZero)
        assertTrue(metrics.isBelowSilenceThreshold)
    }

    @Test
    fun configurableThresholdClassifiesLowLevelNoiseWithoutCallingItExactZero() {
        val metrics = PcmMetrics.calculate(
            shortArrayOf(2, -2, 1, -1),
            sampleRateHz = 8_000,
            silenceThreshold = PcmSilenceThreshold(maximumRms = 2.0, maximumPeakAbsolute = 2)
        )

        assertTrue(!metrics.isExactZero)
        assertTrue(metrics.isBelowSilenceThreshold)
    }
}
