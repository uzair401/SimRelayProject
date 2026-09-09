package com.simrelay.prototype.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface PcmFrameDecodeResult {
    data class Success(val frame: PrototypeAudioFrame) : PcmFrameDecodeResult
    data class Failure(val reason: String) : PcmFrameDecodeResult
}

object PcmFrameCodec {
    private const val Magic = 0x53524d30
    private const val Version: Short = 1
    private const val HeaderBytes = 32

    fun encode(frame: PrototypeAudioFrame): ByteArray {
        val buffer = ByteBuffer.allocate(HeaderBytes + frame.samples.size * 2).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(Magic)
        buffer.putShort(Version)
        buffer.putShort(frame.format.channelCount.toShort())
        buffer.putInt(frame.format.sampleRateHz)
        buffer.putShort(frame.format.bitsPerSample.toShort())
        buffer.putShort(frame.format.frameDurationMillis.toShort())
        buffer.putLong(frame.sequence)
        buffer.putLong(frame.elapsedNanos)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        frame.samples.forEach(buffer::putShort)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): PcmFrameDecodeResult {
        if (bytes.size < HeaderBytes || (bytes.size - HeaderBytes) % 2 != 0) {
            return PcmFrameDecodeResult.Failure("Invalid PCM frame length")
        }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (header.int != Magic) return PcmFrameDecodeResult.Failure("Invalid PCM frame magic")
        if (header.short != Version) return PcmFrameDecodeResult.Failure("Unsupported PCM frame version")
        val channels = header.short.toInt()
        val sampleRate = header.int
        val bits = header.short.toInt()
        val duration = header.short.toInt()
        val sequence = header.long
        val elapsedNanos = header.long
        val format = try {
            PrototypeAudioFormat(sampleRate, channels, bits, duration)
        } catch (exception: IllegalArgumentException) {
            return PcmFrameDecodeResult.Failure("Invalid PCM format")
        }
        if (bytes.size != HeaderBytes + format.samplesPerFrame * 2) {
            return PcmFrameDecodeResult.Failure("PCM sample count does not match format")
        }
        header.order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(format.samplesPerFrame) { header.short }
        return PcmFrameDecodeResult.Success(
            PrototypeAudioFrame(format, sequence, elapsedNanos, samples)
        )
    }
}
