package com.simrelay.m0.diagnostics

import com.simrelay.m0.audio.PcmConfig
import com.simrelay.m0.pcm.WavWriter
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureArtifactStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun givesEveryAttemptUniqueRecoverableArtifacts() {
        val generator = AttemptIdGenerator(wallClockMillis = { 100L }, nonce = { "fixed" })
        val store = CaptureArtifactStore(generator)

        val first = store.create(temporaryFolder.root, CaptureAttemptKind.Downlink)
        val second = store.create(temporaryFolder.root, CaptureAttemptKind.Downlink)
        val duplex = store.create(temporaryFolder.root, CaptureAttemptKind.FullDuplex)

        assertNotEquals(first.attemptId, second.attemptId)
        assertNotEquals(second.attemptId, duplex.attemptId)
        assertTrue(first.partialWav.name.endsWith(".partial.wav"))
        assertTrue(first.directory.isDirectory)
    }

    @Test
    fun completionPreservesAttemptAndPromotesPartialFile() {
        val store = CaptureArtifactStore(
            AttemptIdGenerator(wallClockMillis = { 100L }, nonce = { "fixed" })
        )
        val artifacts = store.create(temporaryFolder.root, CaptureAttemptKind.FullDuplex)
        artifacts.partialWav.writeBytes(byteArrayOf(1, 2, 3))

        val completed = store.complete(artifacts)

        assertEquals(File(artifacts.directory, "rx.wav"), completed)
        assertTrue(completed.exists())
        assertFalse(artifacts.partialWav.exists())
        assertEquals(listOf<Byte>(1, 2, 3), completed.readBytes().toList())
    }

    @Test
    fun findsAndRepairsIncompleteCaptureAfterRestart() {
        val store = CaptureArtifactStore(
            AttemptIdGenerator(wallClockMillis = { 100L }, nonce = { "fixed" })
        )
        val artifacts = store.create(temporaryFolder.root, CaptureAttemptKind.Downlink)
        WavWriter(artifacts.partialWav, PcmConfig(8_000)).use { writer ->
            writer.write(shortArrayOf(10, 20))
        }
        RandomAccessFile(artifacts.partialWav, "rw").use { output ->
            output.seek(40)
            output.writeInt(0)
        }

        val recovered = store.recoverIncomplete(temporaryFolder.root)

        assertEquals(listOf(artifacts.partialWav), recovered)
        assertEquals(4, littleEndianInt(artifacts.partialWav.readBytes(), 40))
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
