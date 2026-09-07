package com.simrelay.m0.pcm

import com.simrelay.m0.audio.PcmConfig
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesValidPcm16MonoHeaderAndSizes() {
        val file = File(temporaryFolder.root, "audio.wav")
        WavWriter(file, PcmConfig(16_000)).use { writer ->
            writer.write(shortArrayOf(1, -1, Short.MAX_VALUE, Short.MIN_VALUE))
        }

        val bytes = file.readBytes()
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(44 + 8, bytes.size)
        assertEquals(36 + 8, littleEndianInt(bytes, 4))
        assertEquals(8, littleEndianInt(bytes, 40))
        assertTrue(bytes.drop(44).any { it.toInt() != 0 })
    }

    @Test
    fun headerIsRecoverableBeforeNormalClose() {
        val file = File(temporaryFolder.root, "interrupted.partial.wav")
        val writer = WavWriter(file, PcmConfig(8_000))
        writer.write(shortArrayOf(5, 6, 7))

        val bytesBeforeClose = file.readBytes()
        assertEquals(44 + 6, bytesBeforeClose.size)
        assertEquals(36 + 6, littleEndianInt(bytesBeforeClose, 4))
        assertEquals(6, littleEndianInt(bytesBeforeClose, 40))

        writer.close()
    }

    @Test
    fun repairsInterruptedHeaderSizesFromFileLength() {
        val file = File(temporaryFolder.root, "damaged.partial.wav")
        WavWriter(file, PcmConfig(8_000)).use { writer ->
            writer.write(shortArrayOf(1, 2, 3, 4))
        }
        RandomAccessFile(file, "rw").use { output ->
            output.seek(4)
            output.writeInt(0)
            output.seek(40)
            output.writeInt(0)
        }

        assertTrue(WavWriter.recover(file))

        val bytes = file.readBytes()
        assertEquals(36 + 8, littleEndianInt(bytes, 4))
        assertEquals(8, littleEndianInt(bytes, 40))
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
