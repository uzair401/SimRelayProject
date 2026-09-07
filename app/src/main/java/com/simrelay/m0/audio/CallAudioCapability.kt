package com.simrelay.m0.audio

enum class CapabilityState {
    Supported,
    UnsupportedByOs,
    ApiNotPresent,
    ApiUnavailableOrBlocked,
    PermissionMissing,
    PrivilegeMissing,
    CallNotActive,
    AudioRouteUnavailable,
    DeviceNotInterceptable,
    UnsupportedAudioFormat,
    ResourceBusy,
    ArtifactFailure,
    NotImplemented,
    InitializationFailed,
    RuntimeFailure,
    Unknown
}

enum class FrameworkApiAccess {
    Accessible,
    NotPresent,
    UnavailableOrBlocked,
    NotChecked
}

data class FrameworkMethodAccess(
    val access: FrameworkApiAccess,
    val exceptionClass: String? = null,
    val message: String? = null
) {
    val isAccessible: Boolean
        get() = access == FrameworkApiAccess.Accessible

    companion object {
        val NotChecked = FrameworkMethodAccess(FrameworkApiAccess.NotChecked)
    }
}

data class FrameworkApiPresence(
    val interceptability: FrameworkMethodAccess,
    val downlinkExtraction: FrameworkMethodAccess,
    val uplinkInjection: FrameworkMethodAccess
) {
    val allAccessible: Boolean
        get() = interceptability.isAccessible &&
            downlinkExtraction.isAccessible &&
            uplinkInjection.isAccessible

    companion object {
        val Unknown = FrameworkApiPresence(
            FrameworkMethodAccess.NotChecked,
            FrameworkMethodAccess.NotChecked,
            FrameworkMethodAccess.NotChecked
        )
        val NotPresent = FrameworkApiPresence(
            FrameworkMethodAccess(FrameworkApiAccess.NotPresent),
            FrameworkMethodAccess(FrameworkApiAccess.NotPresent),
            FrameworkMethodAccess(FrameworkApiAccess.NotPresent)
        )
    }
}

enum class AudioOperation {
    CapabilityProbe,
    ApiInspection,
    InterceptabilityProbe,
    OpenDownlink,
    OpenUplink,
    StartDownlink,
    StartUplink,
    ReadDownlink,
    WriteUplink,
    StopDownlink,
    StopUplink,
    ExportDiagnostics,
    ArtifactWrite,
    Unknown
}

enum class SessionReadinessState {
    ReadyToAttempt,
    PermissionMissing,
    BackendUnavailable,
    FrameworkValidationRequired,
    Unknown
}

data class CallAudioReadiness(
    val backendId: AudioBackendId,
    val state: SessionReadinessState,
    val message: String,
    val audioMode: Int? = null
)

enum class AudioAction {
    DownlinkCapture,
    UplinkInjection,
    FullDuplex
}

data class PermissionRequirement(
    val name: String,
    val runtimeRequestable: Boolean
)

data class BackendRequirements(
    private val permissionsByAction: Map<AudioAction, Set<PermissionRequirement>> = emptyMap()
) {
    fun permissionsFor(action: AudioAction): Set<PermissionRequirement> =
        permissionsByAction[action].orEmpty()

    companion object {
        val None = BackendRequirements()
    }
}

data class CallAudioCapability(
    val backendId: AudioBackendId,
    val state: CapabilityState,
    val message: String,
    val exceptionClass: String? = null,
    val apiPresence: FrameworkApiPresence = FrameworkApiPresence.Unknown,
    val pstnInterceptable: Boolean? = null,
    val operation: AudioOperation = AudioOperation.CapabilityProbe
) {
    val isSupported: Boolean
        get() = state == CapabilityState.Supported
}

sealed interface BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>
    data class Failure(val capability: CallAudioCapability) : BackendResult<Nothing>
}
