package com.simrelay.m0.audio.framework

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.AudioAction
import com.simrelay.m0.audio.AudioOperation
import com.simrelay.m0.audio.AudioReadException
import com.simrelay.m0.audio.BackendRequirements
import com.simrelay.m0.audio.BackendResult
import com.simrelay.m0.audio.CallAudioBackend
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.CapabilityFailureMapper
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.DownlinkSession
import com.simrelay.m0.audio.FrameworkApiPresence
import com.simrelay.m0.audio.InFlightOperationGate
import com.simrelay.m0.audio.PcmConfig
import com.simrelay.m0.audio.PcmWriteAll
import com.simrelay.m0.audio.PermissionRequirement
import com.simrelay.m0.audio.SessionReadinessState
import com.simrelay.m0.audio.UplinkSession
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class FrameworkInterceptionBackend(
    private val context: Context,
    private val audioManager: AudioManager,
    private val bridge: FrameworkAudioApiBridge = FrameworkAudioApiBridge(audioManager),
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : CallAudioBackend {
    override val id = AudioBackendId.FrameworkInterception
    override val requirements = BackendRequirements(
        AudioAction.entries.associateWith {
            setOf(PermissionRequirement(CallAudioPermission, runtimeRequestable = false))
        }
    )
    private val sessions = Collections.synchronizedSet(mutableSetOf<AutoCloseable>())
    private val backendClosed = AtomicBoolean(false)

    override fun probe(): CallAudioCapability {
        return try {
            if (sdkInt < FrameworkAudioApiBridge.MinimumApi) {
                return CallAudioCapability(
                    id,
                    CapabilityState.UnsupportedByOs,
                    "Framework interception requires Android API ${FrameworkAudioApiBridge.MinimumApi} or newer"
                )
            }
            val presence = bridge.inspectApis()
            if (!presence.allAccessible) {
                val failure = listOf(
                    presence.interceptability,
                    presence.downlinkExtraction,
                    presence.uplinkInjection
                ).firstOrNull { !it.isAccessible }
                return CallAudioCapability(
                    id,
                    CapabilityState.ApiUnavailableOrBlocked,
                    "One or more framework interception methods are unavailable or blocked",
                    exceptionClass = failure?.exceptionClass,
                    apiPresence = presence
                )
            }
            if (context.checkSelfPermission(CallAudioPermission) != PackageManager.PERMISSION_GRANTED) {
                return CallAudioCapability(
                    id,
                    CapabilityState.PermissionMissing,
                    "CALL_AUDIO_INTERCEPTION is not granted",
                    apiPresence = presence
                )
            }
            when (val result = bridge.isPstnCallAudioInterceptable()) {
                is BackendResult.Success -> CallAudioCapability(
                    id,
                    if (result.value) CapabilityState.Supported else CapabilityState.DeviceNotInterceptable,
                    if (result.value) "Framework PSTN interception is available" else "PSTN audio devices are not interceptable",
                    apiPresence = presence,
                    pstnInterceptable = result.value
                )
                is BackendResult.Failure -> result.capability
            }
        } catch (throwable: Throwable) {
            CapabilityFailureMapper.fromThrowable(
                id,
                throwable,
                operation = AudioOperation.CapabilityProbe
            )
        }
    }

    override fun readiness(capability: CallAudioCapability): CallAudioReadiness {
        val state = when (capability.state) {
            CapabilityState.Supported -> SessionReadinessState.FrameworkValidationRequired
            CapabilityState.PermissionMissing -> SessionReadinessState.PermissionMissing
            else -> SessionReadinessState.BackendUnavailable
        }
        val message = when (state) {
            SessionReadinessState.FrameworkValidationRequired ->
                "Framework will validate whether the current call-redirection mode permits opening audio"
            SessionReadinessState.PermissionMissing -> capability.message
            else -> "Backend capability does not currently permit a session attempt"
        }
        return CallAudioReadiness(id, state, message, audioManager.mode)
    }

    override fun openDownlink(config: PcmConfig): BackendResult<DownlinkSession> =
        protect(AudioOperation.OpenDownlink) {
            if (backendClosed.get()) return@protect backendClosedFailure(AudioOperation.OpenDownlink)
            val capability = probe()
            if (!capability.isSupported) return@protect BackendResult.Failure(capability)
            when (val result = bridge.createDownlink(config)) {
                is BackendResult.Success -> createDownlinkSession(result.value, config, capability.apiPresence)
                is BackendResult.Failure -> result
            }
        }

    override fun openUplink(config: PcmConfig): BackendResult<UplinkSession> =
        protect(AudioOperation.OpenUplink) {
            if (backendClosed.get()) return@protect backendClosedFailure(AudioOperation.OpenUplink)
            val capability = probe()
            if (!capability.isSupported) return@protect BackendResult.Failure(capability)
            when (val result = bridge.createUplink(config)) {
                is BackendResult.Success -> createUplinkSession(result.value, config, capability.apiPresence)
                is BackendResult.Failure -> result
            }
        }

    override fun close() {
        backendClosed.set(true)
        val openSessions = synchronized(sessions) { sessions.toList().also { sessions.clear() } }
        openSessions.forEach { session ->
            try {
                session.close()
            } catch (throwable: Throwable) {
                logCleanupFailure(throwable)
            }
        }
    }

    private fun createDownlinkSession(
        record: AudioRecord,
        config: PcmConfig,
        presence: FrameworkApiPresence
    ): BackendResult<DownlinkSession> {
        return try {
            if (record.state == AudioRecord.STATE_UNINITIALIZED) {
                releaseRecord(record)
                initializationFailure(
                    "AudioRecord is uninitialized",
                    presence,
                    AudioOperation.OpenDownlink
                )
            } else {
                val session = FrameworkDownlinkSession(record, config, presence) { sessions.remove(it) }
                val accepted = synchronized(sessions) {
                    if (backendClosed.get()) false else sessions.add(session)
                }
                if (accepted) BackendResult.Success(session) else {
                    session.close()
                    backendClosedFailure(AudioOperation.OpenDownlink)
                }
            }
        } catch (throwable: Throwable) {
            releaseRecord(record)
            BackendResult.Failure(
                CapabilityFailureMapper.fromThrowable(id, throwable, presence, AudioOperation.OpenDownlink)
            )
        }
    }

    private fun createUplinkSession(
        track: AudioTrack,
        config: PcmConfig,
        presence: FrameworkApiPresence
    ): BackendResult<UplinkSession> {
        return try {
            if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                releaseTrack(track)
                initializationFailure(
                    "AudioTrack is uninitialized",
                    presence,
                    AudioOperation.OpenUplink
                )
            } else {
                val session = FrameworkUplinkSession(track, config, presence) { sessions.remove(it) }
                val accepted = synchronized(sessions) {
                    if (backendClosed.get()) false else sessions.add(session)
                }
                if (accepted) BackendResult.Success(session) else {
                    session.close()
                    backendClosedFailure(AudioOperation.OpenUplink)
                }
            }
        } catch (throwable: Throwable) {
            releaseTrack(track)
            BackendResult.Failure(
                CapabilityFailureMapper.fromThrowable(id, throwable, presence, AudioOperation.OpenUplink)
            )
        }
    }

    private fun releaseRecord(record: AudioRecord) {
        try {
            record.release()
        } catch (throwable: Throwable) {
            logCleanupFailure(throwable)
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.release()
        } catch (throwable: Throwable) {
            logCleanupFailure(throwable)
        }
    }

    private fun <T> initializationFailure(
        message: String,
        presence: FrameworkApiPresence,
        operation: AudioOperation
    ): BackendResult<T> = BackendResult.Failure(
        CallAudioCapability(
            id,
            CapabilityState.InitializationFailed,
            message,
            apiPresence = presence,
            operation = operation
        )
    )

    private fun <T> backendClosedFailure(operation: AudioOperation): BackendResult<T> =
        BackendResult.Failure(
            CallAudioCapability(
                id,
                CapabilityState.ResourceBusy,
                "Framework backend is closing or closed",
                operation = operation
            )
        )

    private inner class FrameworkDownlinkSession(
        private val record: AudioRecord,
        override val config: PcmConfig,
        private val presence: FrameworkApiPresence,
        private val onClose: (AutoCloseable) -> Unit
    ) : DownlinkSession {
        private val gate = InFlightOperationGate("Downlink")

        override fun start(): BackendResult<Unit> = operation(presence, AudioOperation.StartDownlink) {
            gate.run { record.startRecording() }
        }

        override fun read(buffer: ShortArray): BackendResult<Int> =
            operation(presence, AudioOperation.ReadDownlink) {
                gate.run {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) throw AudioReadException(count)
                    count
                }
            }

        override fun stop() {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }

        override fun close() {
            try {
                gate.close(
                    stop = { stop() },
                    release = { record.release() }
                )
            } finally {
                onClose(this)
            }
        }
    }

    private inner class FrameworkUplinkSession(
        private val track: AudioTrack,
        override val config: PcmConfig,
        private val presence: FrameworkApiPresence,
        private val onClose: (AutoCloseable) -> Unit
    ) : UplinkSession {
        private val gate = InFlightOperationGate("Uplink")

        override fun start(): BackendResult<Unit> = operation(presence, AudioOperation.StartUplink) {
            gate.run { track.play() }
        }

        override fun write(buffer: ShortArray, offset: Int, count: Int): BackendResult<Int> =
            operation(presence, AudioOperation.WriteUplink) {
                require(offset in 0..buffer.size)
                require(count in 0..buffer.size - offset)
                gate.run {
                    PcmWriteAll.write(
                        offset = offset,
                        count = count,
                        shouldContinue = { !gate.isClosing }
                    ) { writeOffset, writeCount ->
                        track.write(buffer, writeOffset, writeCount, AudioTrack.WRITE_BLOCKING)
                    }
                }
            }

        override fun stop() {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        }

        override fun close() {
            try {
                gate.close(
                    stop = { stop() },
                    release = { track.release() }
                )
            } finally {
                onClose(this)
            }
        }
    }

    private fun <T> operation(
        presence: FrameworkApiPresence,
        operation: AudioOperation,
        block: () -> T
    ): BackendResult<T> = try {
        BackendResult.Success(block())
    } catch (throwable: Throwable) {
        BackendResult.Failure(CapabilityFailureMapper.fromThrowable(id, throwable, presence, operation))
    }

    private fun <T> protect(
        operation: AudioOperation,
        block: () -> BackendResult<T>
    ): BackendResult<T> = try {
        block()
    } catch (throwable: Throwable) {
        BackendResult.Failure(
            CapabilityFailureMapper.fromThrowable(id, throwable, operation = operation)
        )
    }

    private fun logCleanupFailure(throwable: Throwable) {
        Log.e(
            LogTag,
            "Audio cleanup failed: ${throwable.javaClass.name}: ${CapabilityFailureMapper.sanitize(throwable.message)}"
        )
    }

    companion object {
        const val CallAudioPermission = "android.permission.CALL_AUDIO_INTERCEPTION"
        private const val LogTag = "SimRelayFramework"
    }
}
