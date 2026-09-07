package com.simrelay.m0.audio.framework

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.AudioOperation
import com.simrelay.m0.audio.BackendResult
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CapabilityFailureMapper
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.FrameworkApiAccess
import com.simrelay.m0.audio.FrameworkApiPresence
import com.simrelay.m0.audio.FrameworkMethodAccess
import com.simrelay.m0.audio.PcmConfig
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class FrameworkAudioApiBridge(
    private val audioManager: AudioManager,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {
    private val backendId = AudioBackendId.FrameworkInterception

    fun inspectApis(): FrameworkApiPresence {
        if (sdkInt < MinimumApi) return FrameworkApiPresence.NotPresent
        return FrameworkApiPresence(
            interceptability = inspectMethod(InterceptabilityMethod),
            downlinkExtraction = inspectMethod(DownlinkMethod, AudioFormat::class.java),
            uplinkInjection = inspectMethod(UplinkMethod, AudioFormat::class.java)
        )
    }

    fun isPstnCallAudioInterceptable(): BackendResult<Boolean> {
        return protect(AudioOperation.InterceptabilityProbe) {
            val presence = inspectApis()
            if (!presence.allAccessible) {
                return@protect apiUnavailable(presence, AudioOperation.InterceptabilityProbe)
            }
            invoke(InterceptabilityMethod, presence, AudioOperation.InterceptabilityProbe) { method ->
                method.invoke(audioManager) as Boolean
            }
        }
    }

    fun createDownlink(config: PcmConfig): BackendResult<AudioRecord> {
        return protect(AudioOperation.OpenDownlink) {
            val presence = inspectApis()
            if (!presence.allAccessible) {
                return@protect apiUnavailable(presence, AudioOperation.OpenDownlink)
            }
            val format = audioFormat(config, isOutput = false)
            invoke(DownlinkMethod, presence, AudioOperation.OpenDownlink, AudioFormat::class.java) { method ->
                method.invoke(audioManager, format) as AudioRecord
            }
        }
    }

    fun createUplink(config: PcmConfig): BackendResult<AudioTrack> {
        return protect(AudioOperation.OpenUplink) {
            val presence = inspectApis()
            if (!presence.allAccessible) {
                return@protect apiUnavailable(presence, AudioOperation.OpenUplink)
            }
            val format = audioFormat(config, isOutput = true)
            invoke(UplinkMethod, presence, AudioOperation.OpenUplink, AudioFormat::class.java) { method ->
                method.invoke(audioManager, format) as AudioTrack
            }
        }
    }

    private fun audioFormat(config: PcmConfig, isOutput: Boolean): AudioFormat =
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(config.sampleRateHz)
            .setChannelMask(if (isOutput) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_IN_MONO)
            .build()

    private fun inspectMethod(name: String, vararg parameterTypes: Class<*>): FrameworkMethodAccess = try {
        AudioManager::class.java.getMethod(name, *parameterTypes)
        FrameworkMethodAccess(FrameworkApiAccess.Accessible)
    } catch (throwable: Throwable) {
        FrameworkMethodAccess(
            access = FrameworkApiAccess.UnavailableOrBlocked,
            exceptionClass = throwable.javaClass.name,
            message = CapabilityFailureMapper.sanitize(throwable.message)
        )
    }

    private fun <T> invoke(
        name: String,
        presence: FrameworkApiPresence,
        operation: AudioOperation,
        vararg parameterTypes: Class<*>,
        block: (Method) -> T
    ): BackendResult<T> = try {
        BackendResult.Success(block(AudioManager::class.java.getMethod(name, *parameterTypes)))
    } catch (exception: InvocationTargetException) {
        BackendResult.Failure(
            CapabilityFailureMapper.fromThrowable(
                backendId,
                exception.targetException ?: exception,
                presence,
                operation
            )
        )
    } catch (exception: NoSuchMethodException) {
        BackendResult.Failure(CapabilityFailureMapper.fromThrowable(backendId, exception, presence, operation))
    } catch (exception: SecurityException) {
        BackendResult.Failure(CapabilityFailureMapper.fromThrowable(backendId, exception, presence, operation))
    } catch (exception: IllegalStateException) {
        BackendResult.Failure(CapabilityFailureMapper.fromThrowable(backendId, exception, presence, operation))
    } catch (exception: UnsupportedOperationException) {
        BackendResult.Failure(CapabilityFailureMapper.fromThrowable(backendId, exception, presence, operation))
    } catch (exception: LinkageError) {
        BackendResult.Failure(CapabilityFailureMapper.fromThrowable(backendId, exception, presence, operation))
    } catch (exception: Throwable) {
        BackendResult.Failure(CapabilityFailureMapper.fromThrowable(backendId, exception, presence, operation))
    }

    private fun <T> apiUnavailable(
        presence: FrameworkApiPresence,
        operation: AudioOperation
    ): BackendResult<T> {
        val failure = listOf(
            presence.interceptability,
            presence.downlinkExtraction,
            presence.uplinkInjection
        ).firstOrNull { !it.isAccessible }
        return BackendResult.Failure(
            CallAudioCapability(
                backendId = backendId,
                state = if (sdkInt < MinimumApi) {
                    CapabilityState.UnsupportedByOs
                } else {
                    CapabilityState.ApiUnavailableOrBlocked
                },
                message = if (sdkInt < MinimumApi) {
                    "Framework interception requires Android API $MinimumApi or newer"
                } else {
                    "One or more framework interception methods are unavailable or blocked"
                },
                exceptionClass = failure?.exceptionClass,
                apiPresence = presence,
                operation = operation
            )
        )
    }

    private fun <T> protect(
        operation: AudioOperation,
        block: () -> BackendResult<T>
    ): BackendResult<T> = try {
        block()
    } catch (throwable: Throwable) {
        BackendResult.Failure(
            CapabilityFailureMapper.fromThrowable(
                backendId = backendId,
                throwable = throwable,
                apiPresence = runCatching { inspectApis() }.getOrDefault(FrameworkApiPresence.Unknown),
                operation = operation
            )
        )
    }

    companion object {
        const val MinimumApi = 33
        private const val InterceptabilityMethod = "isPstnCallAudioInterceptable"
        private const val DownlinkMethod = "getCallDownlinkExtractionAudioRecord"
        private const val UplinkMethod = "getCallUplinkInjectionAudioTrack"
    }
}
