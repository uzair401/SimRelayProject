package com.simrelay.m0.pcm

import kotlin.math.abs
import kotlin.math.sqrt

data class PcmMetricSummary(
    val sampleCount: Long,
    val sampleRateHz: Int,
    val rms: Double,
    val peakAbsolute: Int,
    val isExactZero: Boolean,
    val isBelowSilenceThreshold: Boolean
) {
    val durationMillis: Long
        get() = if (sampleRateHz == 0) 0 else sampleCount * 1_000L / sampleRateHz
}

data class PcmSilenceThreshold(
    val maximumRms: Double = 0.0,
    val maximumPeakAbsolute: Int = 0
) {
    init {
        require(maximumRms >= 0.0)
        require(maximumPeakAbsolute >= 0)
    }
}

object PcmMetrics {
    fun calculate(
        samples: ShortArray,
        sampleRateHz: Int,
        silenceThreshold: PcmSilenceThreshold = PcmSilenceThreshold()
    ): PcmMetricSummary {
        require(sampleRateHz > 0)
        if (samples.isEmpty()) return PcmMetricSummary(0, sampleRateHz, 0.0, 0, true, true)
        var sumSquares = 0.0
        var peak = 0
        samples.forEach { sample ->
            val value = sample.toInt()
            sumSquares += value.toDouble() * value
            peak = maxOf(peak, abs(value))
        }
        val rms = sqrt(sumSquares / samples.size)
        return PcmMetricSummary(
            sampleCount = samples.size.toLong(),
            sampleRateHz = sampleRateHz,
            rms = rms,
            peakAbsolute = peak,
            isExactZero = peak == 0,
            isBelowSilenceThreshold = rms <= silenceThreshold.maximumRms &&
                peak <= silenceThreshold.maximumPeakAbsolute
        )
    }
}

class StreamingPcmMetrics(
    private val sampleRateHz: Int,
    private val silenceThreshold: PcmSilenceThreshold = PcmSilenceThreshold()
) {
    private var sampleCount = 0L
    private var sumSquares = 0.0
    private var peak = 0

    fun add(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            sumSquares += value.toDouble() * value
            peak = maxOf(peak, abs(value))
        }
        sampleCount += count
    }

    fun snapshot(): PcmMetricSummary {
        val rms = if (sampleCount == 0L) 0.0 else sqrt(sumSquares / sampleCount)
        return PcmMetricSummary(
            sampleCount = sampleCount,
            sampleRateHz = sampleRateHz,
            rms = rms,
            peakAbsolute = peak,
            isExactZero = peak == 0,
            isBelowSilenceThreshold = rms <= silenceThreshold.maximumRms &&
                peak <= silenceThreshold.maximumPeakAbsolute
        )
    }
}
