package com.simrelay.prototype.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmBufferingTest {
    @Test
    fun assemblerCombinesTenMillisecondChunksIntoTwentyMillisecondFrames() {
        val format = PrototypeAudioFormat(48_000)
        val assembler = PcmFrameAssembler(format)
        val first = assembler.append(ShortArray(480) { 10 }, 48_000, 1, 100)
        val second = assembler.append(ShortArray(480) { 20 }, 48_000, 1, 200)

        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        assertEquals(960, second.single().samples.size)
        assertEquals(10, second.single().samples.first().toInt())
        assertEquals(20, second.single().samples.last().toInt())
    }

    @Test
    fun assemblerResamplesEightAndSixteenKilohertzChunks() {
        listOf(8_000 to 80, 16_000 to 160).forEach { (rate, samples) ->
            val assembler = PcmFrameAssembler(PrototypeAudioFormat(48_000))
            assembler.append(ShortArray(samples) { 100 }, rate, 1, 1)
            val frames = assembler.append(ShortArray(samples) { 200 }, rate, 1, 2)

            assertEquals(1, frames.size)
            assertEquals(960, frames.single().samples.size)
        }
    }

    @Test
    fun assemblerResetSupportsRepeatedSessions() {
        val assembler = PcmFrameAssembler(PrototypeAudioFormat(16_000))
        assembler.append(ShortArray(160), 16_000, 1, 1)
        assembler.reset()

        val first = assembler.append(ShortArray(160), 16_000, 1, 2)
        val second = assembler.append(ShortArray(160), 16_000, 1, 3)

        assertTrue(first.isEmpty())
        assertEquals(0, second.single().sequence)
    }

    @Test
    fun sampleBufferDropsOldestDataAndReadsWithoutPadding() {
        val buffer = PcmSampleBuffer(4)
        assertEquals(2, buffer.write(shortArrayOf(1, 2, 3, 4, 5, 6)))
        val output = ShortArray(4)

        assertEquals(4, buffer.read(output))
        assertTrue(output.contentEquals(shortArrayOf(3, 4, 5, 6)))
        assertEquals(0, buffer.availableSamples())
    }

    @Test
    fun rejectsMalformedFramesAndUnsupportedChunkRates() {
        val format = PrototypeAudioFormat(16_000)
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeAudioFrame(format, 0, 0, ShortArray(format.samplesPerFrame - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmFrameAssembler(format).append(ShortArray(160), 7_999, 1, 0)
        }
    }
}
