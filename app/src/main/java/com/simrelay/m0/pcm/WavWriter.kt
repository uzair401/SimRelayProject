package com.simrelay.m0.pcm

import com.simrelay.m0.audio.PcmConfig
import java.io.File
import java.io.RandomAccessFile

class WavWriter(
    file: File,
    private val config: PcmConfig
) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var dataSize = 0L
    private var closed = false

    init {
        output.setLength(0)
        writeHeader(0)
        output.fd.sync()
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        check(!closed)
        require(count in 0..samples.size)
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value ushr 8).toByte()
        }
        output.write(bytes)
        dataSize += bytes.size
        updateSizeFields()
    }

    fun checkpoint() {
        check(!closed)
        updateSizeFields()
        output.fd.sync()
    }

    override fun close() {
        if (closed) return
        updateSizeFields()
        output.fd.sync()
        output.close()
        closed = true
    }

    private fun updateSizeFields() {
        require(dataSize <= UInt.MAX_VALUE.toLong() - 36L)
        val end = output.filePointer
        output.seek(4)
        writeIntLittleEndian(36L + dataSize)
        output.seek(40)
        writeIntLittleEndian(dataSize)
        output.seek(end)
    }

    private fun writeHeader(pcmDataSize: Long) {
        val byteRate = config.sampleRateHz * config.channelCount * config.bitsPerSample / 8
        val blockAlign = config.channelCount * config.bitsPerSample / 8
        output.writeBytes("RIFF")
        writeIntLittleEndian(36L + pcmDataSize)
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeIntLittleEndian(16)
        writeShortLittleEndian(1)
        writeShortLittleEndian(config.channelCount)
        writeIntLittleEndian(config.sampleRateHz.toLong())
        writeIntLittleEndian(byteRate.toLong())
        writeShortLittleEndian(blockAlign)
        writeShortLittleEndian(config.bitsPerSample)
        output.writeBytes("data")
        writeIntLittleEndian(pcmDataSize)
    }

    private fun writeIntLittleEndian(value: Long) {
        output.writeByte(value.toInt())
        output.writeByte((value ushr 8).toInt())
        output.writeByte((value ushr 16).toInt())
        output.writeByte((value ushr 24).toInt())
    }

    private fun writeShortLittleEndian(value: Int) {
        output.writeByte(value)
        output.writeByte(value ushr 8)
    }

    companion object {
        fun recover(file: File): Boolean {
            if (!file.isFile || file.length() < HeaderSize) return false
            RandomAccessFile(file, "rw").use { output ->
                if (!hasExpectedHeader(output)) return false
                val dataSize = output.length() - HeaderSize
                if (dataSize > UInt.MAX_VALUE.toLong() - 36L) return false
                output.seek(4)
                writeIntLittleEndian(output, 36L + dataSize)
                output.seek(40)
                writeIntLittleEndian(output, dataSize)
                output.fd.sync()
            }
            return true
        }

        private fun hasExpectedHeader(output: RandomAccessFile): Boolean {
            output.seek(0)
            val header = ByteArray(HeaderSize.toInt())
            output.readFully(header)
            return header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE" &&
                header.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "fmt " &&
                header.copyOfRange(36, 40).toString(Charsets.US_ASCII) == "data"
        }

        private fun writeIntLittleEndian(output: RandomAccessFile, value: Long) {
            output.writeByte(value.toInt())
            output.writeByte((value ushr 8).toInt())
            output.writeByte((value ushr 16).toInt())
            output.writeByte((value ushr 24).toInt())
        }

        private const val HeaderSize = 44L
    }
}
