package com.simrelay.prototype.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun adaptsEightSixteenAndFortyEightKilohertzInBothDirections() {
        val rates = listOf(8_000, 16_000, 48_000)
        rates.forEach { sourceRate ->
            rates.forEach { targetRate ->
                val sourceFormat = PrototypeAudioFormat(sourceRate)
                val targetFormat = PrototypeAudioFormat(targetRate)
                val source = PrototypeAudioFrame(
                    sourceFormat,
                    sequence = sourceRate.toLong(),
                    elapsedNanos = targetRate.toLong(),
                    samples = ShortArray(sourceFormat.samplesPerFrame) { (it % 1_000).toShort() }
                )

                val adapted = AudioFormatAdapter.adapt(source, targetFormat)

                assertEquals(targetFormat.samplesPerFrame, adapted.samples.size)
                assertEquals(source.sequence, adapted.sequence)
                assertEquals(source.elapsedNanos, adapted.elapsedNanos)
            }
        }
    }

    @Test
    fun downmixesChannelsAndRejectsMalformedInterleaving() {
        val mono = AudioFormatAdapter.toMono(shortArrayOf(1_000, -1_000, 2_000, 0), 2)

        assertTrue(mono.contentEquals(shortArrayOf(0, 1_000)))
        assertThrows(IllegalArgumentException::class.java) {
            AudioFormatAdapter.toMono(shortArrayOf(1, 2, 3), 2)
        }
    }

    @Test
    fun rejectsFrameDurationChangesAtRateAdapterBoundary() {
        val source = PrototypeAudioFormat(16_000, frameDurationMillis = 20)
        val target = PrototypeAudioFormat(48_000, frameDurationMillis = 10)

        assertThrows(IllegalArgumentException::class.java) {
            AudioFormatAdapter.adapt(
                PrototypeAudioFrame(source, 0, 0, ShortArray(source.samplesPerFrame)),
                target
            )
        }
    }
}
