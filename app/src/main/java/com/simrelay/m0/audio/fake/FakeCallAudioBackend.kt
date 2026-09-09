package com.simrelay.m0.audio.fake

import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.AudioOperation
import com.simrelay.m0.audio.BackendRequirements
import com.simrelay.m0.audio.BackendResult
import com.simrelay.m0.audio.CallAudioBackend
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.DownlinkSession
import com.simrelay.m0.audio.PcmConfig
import com.simrelay.m0.audio.SessionReadinessState
import com.simrelay.m0.audio.UplinkSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class FakeAudioMetrics(
    val rxFrames: Long = 0,
    val rxBytes: Long = 0,
    val txFrames: Long = 0,
    val txBytes: Long = 0,
    val txRms: Double = 0.0,
    val txPeak: Int = 0,
    val firstRxElapsedNanos: Long? = null,
    val firstTxElapsedNanos: Long? = null
)

class FakeCallAudioBackend(
    private val nanoTime: () -> Long = System::nanoTime
) : CallAudioBackend {
    override val id = AudioBackendId.FakeDevelopment
    override val requirements = BackendRequirements.None
    private val closed = AtomicBoolean(false)
    private val downlinkOpen = AtomicBoolean(false)
    private val uplinkOpen = AtomicBoolean(false)
    private val metrics = AtomicReference(FakeAudioMetrics())
    private val txAccumulator = TxAccumulator()

    override fun probe(): CallAudioCapability = if (closed.get()) {
        failureCapability("Fake audio backend is closed", AudioOperation.CapabilityProbe)
    } else {
        CallAudioCapability(id, CapabilityState.Supported, "Deterministic development audio is available")
    }

    override fun readiness(capability: CallAudioCapability): CallAudioReadiness = CallAudioReadiness(
        id,
        if (capability.isSupported) SessionReadinessState.ReadyToAttempt else SessionReadinessState.BackendUnavailable,
        capability.message
    )

    override fun openDownlink(config: PcmConfig): BackendResult<DownlinkSession> {
        if (closed.get()) return BackendResult.Failure(failureCapability("Fake audio backend is closed", AudioOperation.OpenDownlink))
        if (!downlinkOpen.compareAndSet(false, true)) {
            return BackendResult.Failure(busyCapability("Fake downlink is already open", AudioOperation.OpenDownlink))
        }
        return BackendResult.Success(FakeDownlinkSession(config))
    }

    override fun openUplink(config: PcmConfig): BackendResult<UplinkSession> {
        if (closed.get()) return BackendResult.Failure(failureCapability("Fake audio backend is closed", AudioOperation.OpenUplink))
        if (!uplinkOpen.compareAndSet(false, true)) {
            return BackendResult.Failure(busyCapability("Fake uplink is already open", AudioOperation.OpenUplink))
        }
        txAccumulator.reset()
        return BackendResult.Success(FakeUplinkSession(config))
    }

    fun metrics(): FakeAudioMetrics = metrics.get()

    override fun close() {
        closed.set(true)
        downlinkOpen.set(false)
        uplinkOpen.set(false)
    }

    private inner class FakeDownlinkSession(
        override val config: PcmConfig
    ) : DownlinkSession {
        private val started = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private var sampleIndex = 0L
        private var nextFrameNanos = 0L

        override fun start(): BackendResult<Unit> {
            if (released.get()) return BackendResult.Failure(failureCapability("Fake downlink is released", AudioOperation.StartDownlink))
            if (!started.compareAndSet(false, true)) return BackendResult.Success(Unit)
            nextFrameNanos = nanoTime()
            return BackendResult.Success(Unit)
        }

        override fun read(buffer: ShortArray): BackendResult<Int> {
            if (!started.get() || released.get()) {
                return BackendResult.Failure(failureCapability("Fake downlink is not running", AudioOperation.ReadDownlink))
            }
            val count = minOf(buffer.size, config.frameSampleCount())
            if (count == 0) return BackendResult.Success(0)
            val waitNanos = nextFrameNanos - nanoTime()
            if (waitNanos > 0) LockSupport.parkNanos(waitNanos)
            if (!started.get() || released.get()) {
                return BackendResult.Failure(failureCapability("Fake downlink stopped", AudioOperation.ReadDownlink))
            }
            val scale = Short.MAX_VALUE * 0.18
            repeat(count) { index ->
                buffer[index] = (
                    sin(2.0 * PI * 1_000.0 * sampleIndex / config.sampleRateHz) * scale
                    ).roundToInt().toShort()
                sampleIndex += 1
            }
            nextFrameNanos = maxOf(nextFrameNanos + 20_000_000L, nanoTime())
            updateRx(count)
            return BackendResult.Success(count)
        }

        override fun stop() {
            started.set(false)
        }

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            started.set(false)
            downlinkOpen.set(false)
        }
    }

    private inner class FakeUplinkSession(
        override val config: PcmConfig
    ) : UplinkSession {
        private val started = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        override fun start(): BackendResult<Unit> {
            if (released.get()) return BackendResult.Failure(failureCapability("Fake uplink is released", AudioOperation.StartUplink))
            started.set(true)
            return BackendResult.Success(Unit)
        }

        override fun write(buffer: ShortArray, offset: Int, count: Int): BackendResult<Int> {
            if (!started.get() || released.get()) {
                return BackendResult.Failure(failureCapability("Fake uplink is not running", AudioOperation.WriteUplink))
            }
            if (offset < 0 || count < 0 || offset + count > buffer.size) {
                return BackendResult.Failure(
                    CallAudioCapability(
                        id,
                        CapabilityState.RuntimeFailure,
                        "Invalid fake uplink buffer range",
                        operation = AudioOperation.WriteUplink
                    )
                )
            }
            txAccumulator.add(buffer, offset, count)
            updateTx(count)
            return BackendResult.Success(count)
        }

        override fun stop() {
            started.set(false)
        }

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            started.set(false)
            uplinkOpen.set(false)
        }
    }

    private fun updateRx(samples: Int) {
        while (true) {
            val current = metrics.get()
            val updated = current.copy(
                rxFrames = current.rxFrames + 1,
                rxBytes = current.rxBytes + samples * 2L,
                firstRxElapsedNanos = current.firstRxElapsedNanos ?: nanoTime()
            )
            if (metrics.compareAndSet(current, updated)) return
        }
    }

    private fun updateTx(samples: Int) {
        val summary = txAccumulator.snapshot()
        while (true) {
            val current = metrics.get()
            val updated = current.copy(
                txFrames = current.txFrames + 1,
                txBytes = current.txBytes + samples * 2L,
                txRms = summary.first,
                txPeak = summary.second,
                firstTxElapsedNanos = current.firstTxElapsedNanos ?: nanoTime()
            )
            if (metrics.compareAndSet(current, updated)) return
        }
    }

    private fun failureCapability(message: String, operation: AudioOperation) = CallAudioCapability(
        id,
        CapabilityState.RuntimeFailure,
        message,
        operation = operation
    )

    private fun busyCapability(message: String, operation: AudioOperation) = CallAudioCapability(
        id,
        CapabilityState.ResourceBusy,
        message,
        operation = operation
    )

    private class TxAccumulator {
        private var count = 0L
        private var squares = 0.0
        private var peak = 0

        @Synchronized
        fun reset() {
            count = 0
            squares = 0.0
            peak = 0
        }

        @Synchronized
        fun add(buffer: ShortArray, offset: Int, length: Int) {
            for (index in offset until offset + length) {
                val value = buffer[index].toInt()
                squares += value.toDouble() * value
                peak = maxOf(peak, abs(value))
            }
            count += length
        }

        @Synchronized
        fun snapshot(): Pair<Double, Int> = Pair(
            if (count == 0L) 0.0 else sqrt(squares / count),
            peak
        )
    }
}
