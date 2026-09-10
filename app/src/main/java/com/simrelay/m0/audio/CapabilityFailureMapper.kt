package com.simrelay.m0.audio

object CapabilityFailureMapper {
    fun fromThrowable(
        backendId: AudioBackendId,
        throwable: Throwable,
        apiPresence: FrameworkApiPresence = FrameworkApiPresence.Unknown,
        operation: AudioOperation = AudioOperation.Unknown
    ): CallAudioCapability {
        val state = when {
            operation in setOf(AudioOperation.ArtifactWrite, AudioOperation.ExportDiagnostics) ->
                CapabilityState.ArtifactFailure
            throwable is java.util.concurrent.RejectedExecutionException -> CapabilityState.ResourceBusy
            throwable is NoSuchMethodException || throwable is IllegalAccessException || throwable is LinkageError ->
                CapabilityState.ApiUnavailableOrBlocked
            throwable is SecurityException -> CapabilityState.PermissionMissing
            throwable is AudioSessionException -> CapabilityState.RuntimeFailure
            throwable is IllegalStateException && operation in setOf(
                AudioOperation.OpenDownlink,
                AudioOperation.OpenUplink
            ) -> CapabilityState.CallNotActive
            throwable is IllegalStateException && operation in setOf(
                AudioOperation.StartDownlink,
                AudioOperation.StartUplink
            ) -> CapabilityState.InitializationFailed
            throwable is UnsupportedOperationException -> {
                if (throwable.message.orEmpty().contains("PSTN", ignoreCase = true)) {
                    CapabilityState.PstnInterceptionUnsupported
                } else {
                    CapabilityState.AudioRouteUnavailable
                }
            }
            throwable is IllegalArgumentException && operation in setOf(
                AudioOperation.OpenDownlink,
                AudioOperation.OpenUplink
            ) -> CapabilityState.UnsupportedAudioFormat
            throwable is IllegalArgumentException -> CapabilityState.InitializationFailed
            else -> CapabilityState.RuntimeFailure
        }
        return CallAudioCapability(
            backendId = backendId,
            state = state,
            message = sanitize(throwable.message) ?: state.name,
            exceptionClass = throwable.javaClass.name,
            apiPresence = apiPresence,
            operation = operation
        )
    }

    fun sanitize(message: String?): String? = message
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.replace(Regex("\\s{2,}"), " ")
        ?.trim()
        ?.take(240)
        ?.ifEmpty { null }
}
