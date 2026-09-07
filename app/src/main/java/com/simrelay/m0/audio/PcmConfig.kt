package com.simrelay.m0.audio

data class PcmConfig(
    val sampleRateHz: Int,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16
) {
    init {
        require(sampleRateHz in 8_000..48_000)
        require(channelCount == 1)
        require(bitsPerSample == 16)
    }

    fun frameSampleCount(durationMillis: Int = 20): Int {
        require(durationMillis > 0)
        return (sampleRateHz * durationMillis) / 1_000
    }

    companion object {
        val Candidates = listOf(
            PcmConfig(48_000),
            PcmConfig(16_000),
            PcmConfig(8_000)
        )
    }
}
