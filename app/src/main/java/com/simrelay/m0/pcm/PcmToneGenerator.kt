package com.simrelay.m0.pcm

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

object PcmToneGenerator {
    fun generate(
        sampleRateHz: Int,
        durationMillis: Long,
        frequencyHz: Double = 1_000.0,
        amplitude: Double = 0.2
    ): ShortArray {
        require(sampleRateHz > 0)
        require(durationMillis >= 0)
        require(frequencyHz > 0.0)
        require(amplitude in 0.0..1.0)
        val sampleCount = ((sampleRateHz.toLong() * durationMillis) / 1_000L).toInt()
        val scale = Short.MAX_VALUE * amplitude
        return ShortArray(sampleCount) { index ->
            (sin(2.0 * PI * frequencyHz * index / sampleRateHz) * scale).roundToInt().toShort()
        }
    }
}
