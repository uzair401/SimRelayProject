package com.simrelay.prototype.media

class PcmFrameAssembler(
    private val targetFormat: PrototypeAudioFormat
) {
    private var pending = ShortArray(0)
    private var sequence = 0L
    private var firstElapsedNanos = 0L

    @Synchronized
    fun append(
        samples: ShortArray,
        sampleRateHz: Int,
        channelCount: Int,
        elapsedNanos: Long
    ): List<PrototypeAudioFrame> {
        require(channelCount == targetFormat.channelCount)
        require(samples.size % channelCount == 0)
        if (samples.isEmpty()) return emptyList()
        val converted = AudioFormatAdapter.resampleMono(samples, sampleRateHz, targetFormat.sampleRateHz)
        if (pending.isEmpty()) firstElapsedNanos = elapsedNanos
        pending += converted
        val frames = mutableListOf<PrototypeAudioFrame>()
        while (pending.size >= targetFormat.samplesPerFrame) {
            frames += PrototypeAudioFrame(
                targetFormat,
                sequence++,
                firstElapsedNanos,
                pending.copyOfRange(0, targetFormat.samplesPerFrame)
            )
            pending = pending.copyOfRange(targetFormat.samplesPerFrame, pending.size)
            firstElapsedNanos = elapsedNanos
        }
        return frames
    }

    @Synchronized
    fun reset() {
        pending = ShortArray(0)
        sequence = 0
        firstElapsedNanos = 0
    }
}
