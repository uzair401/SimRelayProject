package com.simrelay.prototype.media

import kotlin.math.roundToInt

object AudioFormatAdapter {
    fun adapt(frame: PrototypeAudioFrame, target: PrototypeAudioFormat): PrototypeAudioFrame {
        require(frame.format.channelCount == target.channelCount)
        require(frame.format.bitsPerSample == target.bitsPerSample)
        require(frame.format.frameDurationMillis == target.frameDurationMillis)
        if (frame.format == target) return frame
        val output = resampleMono(frame.samples, frame.format.sampleRateHz, target.sampleRateHz)
        return PrototypeAudioFrame(target, frame.sequence, frame.elapsedNanos, output)
    }

    fun resampleMono(samples: ShortArray, sourceSampleRateHz: Int, targetSampleRateHz: Int): ShortArray {
        require(sourceSampleRateHz in 8_000..48_000)
        require(targetSampleRateHz in 8_000..48_000)
        if (samples.isEmpty() || sourceSampleRateHz == targetSampleRateHz) return samples.copyOf()
        val outputSize = (samples.size.toLong() * targetSampleRateHz / sourceSampleRateHz).toInt()
        require(outputSize > 0)
        val output = ShortArray(outputSize)
        val scale = sourceSampleRateHz.toDouble() / targetSampleRateHz
        output.indices.forEach { outputIndex ->
            val sourcePosition = outputIndex * scale
            val leftIndex = sourcePosition.toInt().coerceAtMost(samples.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(samples.lastIndex)
            val fraction = sourcePosition - leftIndex
            val interpolated = samples[leftIndex] * (1.0 - fraction) + samples[rightIndex] * fraction
            output[outputIndex] = interpolated.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    fun toMono(interleavedSamples: ShortArray, channelCount: Int): ShortArray {
        require(channelCount > 0)
        require(interleavedSamples.size % channelCount == 0)
        if (channelCount == 1) return interleavedSamples.copyOf()
        return ShortArray(interleavedSamples.size / channelCount) { frameIndex ->
            var sum = 0L
            repeat(channelCount) { channelIndex ->
                sum += interleavedSamples[frameIndex * channelCount + channelIndex]
            }
            (sum / channelCount).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
        }
    }
}
