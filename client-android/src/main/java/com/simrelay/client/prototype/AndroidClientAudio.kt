package com.simrelay.client.prototype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.simrelay.prototype.media.AudioFormatAdapter
import com.simrelay.prototype.media.PrototypeAudioFormat
import com.simrelay.prototype.media.PrototypeAudioFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AndroidClientAudio(
    context: Context,
    private val nanoTime: () -> Long = System::nanoTime
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val playbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val playbackQueue = ArrayBlockingQueue<PrototypeAudioFrame>(10)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureFuture: Future<*>? = null
    private var playbackFuture: Future<*>? = null
    private val format = PrototypeAudioFormat(16_000)

    fun start(
        onFailure: (String) -> Unit = {},
        onCapturedFrame: (PrototypeAudioFrame) -> Unit
    ): String? {
        if (!running.compareAndSet(false, true)) return null
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            return "Microphone permission is required"
        }
        val audioRecord = runCatching { createRecord() }.getOrNull() ?: run {
            running.set(false)
            return "Client AudioRecord could not be initialized"
        }
        val audioTrack = runCatching { createTrack() }.getOrNull() ?: run {
            runCatching { audioRecord.release() }
            running.set(false)
            return "Client AudioTrack could not be initialized"
        }
        return try {
            audioRecord.startRecording()
            audioTrack.play()
            record = audioRecord
            track = audioTrack
            captureFuture = captureExecutor.submit {
                containLoopFailure("Client audio capture failed", onFailure) {
                    captureLoop(audioRecord, onCapturedFrame)
                }
            }
            playbackFuture = playbackExecutor.submit {
                containLoopFailure("Client audio playback failed", onFailure) {
                    playbackLoop(audioTrack)
                }
            }
            null
        } catch (throwable: Throwable) {
            running.set(false)
            runCatching { audioRecord.stop() }
            runCatching { audioTrack.stop() }
            waitFor(captureFuture)
            waitFor(playbackFuture)
            captureFuture = null
            playbackFuture = null
            record = null
            track = null
            runCatching { audioRecord.release() }
            runCatching { audioTrack.release() }
            throwable.message ?: "Client audio start failed"
        }
    }

    fun play(frame: PrototypeAudioFrame): Boolean {
        if (!running.get()) return false
        val adapted = AudioFormatAdapter.adapt(frame, format)
        if (playbackQueue.offer(adapted)) return true
        playbackQueue.poll()
        return playbackQueue.offer(adapted)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val currentRecord = record
        val currentTrack = track
        record = null
        track = null
        runCatching { currentRecord?.stop() }
        runCatching { currentTrack?.stop() }
        waitFor(captureFuture)
        waitFor(playbackFuture)
        captureFuture = null
        playbackFuture = null
        playbackQueue.clear()
        runCatching { currentRecord?.release() }
        runCatching {
            currentTrack?.flush()
            currentTrack?.release()
        }
    }

    override fun close() {
        stop()
        captureExecutor.shutdownNow()
        playbackExecutor.shutdownNow()
    }

    private fun captureLoop(audioRecord: AudioRecord, onCapturedFrame: (PrototypeAudioFrame) -> Unit) {
        var sequence = 0L
        val frame = ShortArray(format.samplesPerFrame)
        while (running.get()) {
            var offset = 0
            while (offset < frame.size && running.get()) {
                val count = audioRecord.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                if (count <= 0) error("AudioRecord read returned $count")
                offset += count
            }
            if (offset == frame.size && running.get()) {
                onCapturedFrame(PrototypeAudioFrame(format, sequence++, nanoTime(), frame.copyOf()))
            }
        }
    }

    private fun playbackLoop(audioTrack: AudioTrack) {
        while (running.get()) {
            val frame = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            var offset = 0
            while (offset < frame.samples.size && running.get()) {
                val written = audioTrack.write(
                    frame.samples,
                    offset,
                    frame.samples.size - offset,
                    AudioTrack.WRITE_BLOCKING
                )
                if (written <= 0) error("AudioTrack write returned $written")
                offset += written
            }
        }
    }

    private fun createRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val channel = AudioFormat.CHANNEL_IN_MONO
        val minimum = AudioRecord.getMinBufferSize(format.sampleRateHz, channel, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val value = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                format.sampleRateHz,
                channel,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, format.samplesPerFrame * 2 * 4)
            )
        } catch (exception: SecurityException) {
            return null
        }
        return value.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: run {
            value.release()
            null
        }
    }

    private fun containLoopFailure(
        fallback: String,
        onFailure: (String) -> Unit,
        operation: () -> Unit
    ) {
        runCatching(operation).onFailure { throwable ->
            if (running.get()) runCatching { onFailure(throwable.message ?: fallback) }
        }
    }

    private fun createTrack(): AudioTrack? {
        val channel = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(format.sampleRateHz, channel, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val value = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channel)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minimum, format.samplesPerFrame * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return value.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: run {
            value.release()
            null
        }
    }

    private fun waitFor(future: Future<*>?) {
        try {
            future?.get(500, TimeUnit.MILLISECONDS)
        } catch (exception: Exception) {
            future?.cancel(true)
        }
    }
}
