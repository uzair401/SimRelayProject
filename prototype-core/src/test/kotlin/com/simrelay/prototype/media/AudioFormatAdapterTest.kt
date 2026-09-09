package com.simrelay.prototype.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatAdapterTest {
    @Test
    fun adaptsTwentyMillisecondFramesBetweenSupportedRates() {
        val inputFormat = PrototypeAudioFormat(8_000)
        val outputFormat = PrototypeAudioFormat(16_000)
        val input = PrototypeAudioFrame(
            inputFormat,
            sequence = 4,
            elapsedNanos = 8,
            samples = ShortArray(inputFormat.samplesPerFrame) { (it * 10).toShort() }
        )

        val output = AudioFormatAdapter.adapt(input, outputFormat)

        assertEquals(320, output.samples.size)
        assertEquals(4, output.sequence)
        assertTrue(output.samples.any { it.toInt() != 0 })
    }

    @Test
    fun frameCodecRoundTripsAndRejectsMalformedInput() {
        val format = PrototypeAudioFormat(16_000)
        val frame = PrototypeAudioFrame(
            format,
            sequence = 12,
            elapsedNanos = 34,
            samples = ShortArray(format.samplesPerFrame) { it.toShort() }
        )

        val decoded = PcmFrameCodec.decode(PcmFrameCodec.encode(frame))

        assertTrue(decoded is PcmFrameDecodeResult.Success)
        decoded as PcmFrameDecodeResult.Success
        assertEquals(frame.sequence, decoded.frame.sequence)
        assertTrue(frame.samples.contentEquals(decoded.frame.samples))
        assertTrue(PcmFrameCodec.decode(byteArrayOf(1, 2)) is PcmFrameDecodeResult.Failure)
    }
}
