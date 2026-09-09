package com.simrelay.prototype.media

data class PrototypeAudioFormat(
    val sampleRateHz: Int,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
    val frameDurationMillis: Int = 20
) {
    init {
        require(sampleRateHz in 8_000..48_000)
        require(channelCount == 1)
        require(bitsPerSample == 16)
        require(frameDurationMillis in 10..60)
    }

    val samplesPerFrame: Int
        get() = sampleRateHz * frameDurationMillis / 1_000
}

data class PrototypeAudioFrame(
    val format: PrototypeAudioFormat,
    val sequence: Long,
    val elapsedNanos: Long,
    val samples: ShortArray
) {
    init {
        require(samples.size == format.samplesPerFrame)
    }
}
