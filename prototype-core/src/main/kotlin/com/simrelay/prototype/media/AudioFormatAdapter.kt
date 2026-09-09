package com.simrelay.prototype.media

import kotlin.math.roundToInt

object AudioFormatAdapter {
    fun adapt(frame: PrototypeAudioFrame, target: PrototypeAudioFormat): PrototypeAudioFrame {
        require(frame.format.channelCount == target.channelCount)
        require(frame.format.bitsPerSample == target.bitsPerSample)
        if (frame.format == target) return frame
        val output = ShortArray(target.samplesPerFrame)
        if (frame.samples.isNotEmpty()) {
            val scale = frame.samples.size.toDouble() / output.size
            output.indices.forEach { outputIndex ->
                val sourcePosition = outputIndex * scale
                val leftIndex = sourcePosition.toInt().coerceAtMost(frame.samples.lastIndex)
                val rightIndex = (leftIndex + 1).coerceAtMost(frame.samples.lastIndex)
                val fraction = sourcePosition - leftIndex
                val interpolated = frame.samples[leftIndex] * (1.0 - fraction) + frame.samples[rightIndex] * fraction
                output[outputIndex] = interpolated.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return PrototypeAudioFrame(target, frame.sequence, frame.elapsedNanos, output)
    }
}
