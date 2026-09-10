package com.simrelay.prototype.media.android

import android.media.AudioFormat
import com.simrelay.prototype.media.AudioFormatAdapter
import com.simrelay.prototype.media.PcmFrameAssembler
import com.simrelay.prototype.media.PcmSampleBuffer
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

internal data class ExternalPcmBridgeStats(
    val submittedFrames: Long,
    val deliveredFrames: Long,
    val droppedFrames: Long
)

internal class ExternalPcmAudioBridge(
    val format: PrototypeAudioFormat = PrototypeAudioFormat(48_000),
    private val nanoTime: () -> Long = System::nanoTime,
    private val onFrame: (PrototypeAudioFrame) -> Unit
) {
    private val active = AtomicBoolean(false)
    private val submittedFrames = AtomicLong()
    private val deliveredFrames = AtomicLong()
    private val droppedFrames = AtomicLong()
    private val captureSamples = PcmSampleBuffer(format.sampleRateHz / 2)
    private val playoutAssembler = PcmFrameAssembler(format)
    private val pacingLock = Any()
    private var nextCaptureNanos = 0L

    fun start() {
        captureSamples.clear()
        playoutAssembler.reset()
        submittedFrames.set(0)
        deliveredFrames.set(0)
        droppedFrames.set(0)
        synchronized(pacingLock) { nextCaptureNanos = 0 }
        active.set(true)
    }

    fun stop() {
        active.set(false)
        captureSamples.clear()
        playoutAssembler.reset()
        synchronized(pacingLock) { nextCaptureNanos = 0 }
    }

    fun submit(frame: PrototypeAudioFrame): Boolean {
        if (!active.get()) return false
        val adapted = AudioFormatAdapter.adapt(frame, format)
        if (captureSamples.write(adapted.samples) > 0) droppedFrames.incrementAndGet()
        submittedFrames.incrementAndGet()
        return true
    }

    fun onCaptureBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRateHz: Int,
        captureTimeNanos: Long
    ): Long {
        val bytesPerSample = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 2 else 0
        val valid = bytesPerSample > 0 && channelCount == 1 && sampleRateHz == format.sampleRateHz
        val requestedSamples = if (valid) buffer.capacity() / bytesPerSample else 0
        pace(requestedSamples, sampleRateHz)
        val output = ShortArray(requestedSamples)
        if (active.get() && valid) {
            val count = captureSamples.read(output)
            if (count < output.size) droppedFrames.incrementAndGet()
        } else if (!valid) {
            droppedFrames.incrementAndGet()
        }
        val target = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        target.clear()
        output.forEach { target.putShort(it) }
        while (target.remaining() >= 2) target.putShort(0)
        return captureTimeNanos.takeIf { it > 0 } ?: nanoTime()
    }

    fun onRemoteData(
        buffer: ByteBuffer,
        bitsPerSample: Int,
        sampleRateHz: Int,
        channelCount: Int,
        numberOfFrames: Int,
        elapsedNanos: Long = nanoTime()
    ) {
        if (!active.get()) return
        if (bitsPerSample != 16 || sampleRateHz !in 8_000..48_000 || channelCount <= 0 || numberOfFrames <= 0) {
            droppedFrames.incrementAndGet()
            return
        }
        val requiredSamples = numberOfFrames * channelCount
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.rewind()
        if (source.remaining() < requiredSamples * 2) {
            droppedFrames.incrementAndGet()
            return
        }
        val interleaved = ShortArray(requiredSamples) { source.short }
        val mono = AudioFormatAdapter.toMono(interleaved, channelCount)
        val frames = playoutAssembler.append(mono, sampleRateHz, 1, elapsedNanos)
        deliveredFrames.addAndGet(frames.size.toLong())
        frames.forEach(onFrame)
    }

    fun stats(): ExternalPcmBridgeStats = ExternalPcmBridgeStats(
        submittedFrames = submittedFrames.get(),
        deliveredFrames = deliveredFrames.get(),
        droppedFrames = droppedFrames.get()
    )

    private fun pace(sampleCount: Int, sampleRateHz: Int) {
        if (!active.get() || sampleCount <= 0 || sampleRateHz <= 0) return
        val durationNanos = sampleCount * 1_000_000_000L / sampleRateHz
        val waitNanos = synchronized(pacingLock) {
            val now = nanoTime()
            if (nextCaptureNanos == 0L || now - nextCaptureNanos > durationNanos * 2) {
                nextCaptureNanos = now
            }
            val wait = (nextCaptureNanos - now).coerceAtLeast(0)
            nextCaptureNanos += durationNanos
            wait
        }
        if (waitNanos > 0) LockSupport.parkNanos(waitNanos)
    }
}
