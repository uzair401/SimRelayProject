package com.simrelay.m0.diagnostics

import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.FrameworkApiAccess
import com.simrelay.m0.audio.FrameworkApiPresence
import com.simrelay.m0.util.JsonText

enum class QualificationEvidence {
    Observed,
    Unknown,
    NotTested
}

data class QualificationField(
    val evidence: QualificationEvidence,
    val value: String? = null,
    val detail: String? = null
) {
    fun toJson(): String = JsonText.obj(
        "evidence" to JsonText.string(evidence.name),
        "value" to JsonText.string(value),
        "detail" to JsonText.string(detail)
    )

    companion object {
        fun observed(value: Any, detail: String? = null) =
            QualificationField(QualificationEvidence.Observed, value.toString(), detail)

        fun unknown(detail: String? = null) =
            QualificationField(QualificationEvidence.Unknown, detail = detail)

        fun notTested(detail: String? = null) =
            QualificationField(QualificationEvidence.NotTested, detail = detail)
    }
}

data class DeviceQualificationProfile(
    val schemaVersion: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val androidVersion: String,
    val apiLevel: Int,
    val buildFingerprint: String,
    val securityState: QualificationField,
    val frameworkApiPresent: QualificationField,
    val frameworkApiAccessible: QualificationField,
    val frameworkCapability: QualificationField,
    val callAudioInterceptionPermission: QualificationField,
    val pstnInterceptable: QualificationField,
    val sessionReadiness: QualificationField,
    val supportedSampleRates: QualificationField,
    val rxResult: QualificationField,
    val txResult: QualificationField,
    val fullDuplexResult: QualificationField,
    val micIsolation: QualificationField,
    val speakerIsolation: QualificationField,
    val latency: QualificationField,
    val stability: QualificationField,
    val requiredBackend: QualificationField,
    val requiredProvisioning: QualificationField
) {
    fun toJson(): String = JsonText.obj(
        "schemaVersion" to schemaVersion.toString(),
        "manufacturer" to JsonText.string(manufacturer),
        "model" to JsonText.string(model),
        "device" to JsonText.string(device),
        "board" to JsonText.string(board),
        "hardware" to JsonText.string(hardware),
        "androidVersion" to JsonText.string(androidVersion),
        "apiLevel" to apiLevel.toString(),
        "buildFingerprint" to JsonText.string(buildFingerprint),
        "securityState" to securityState.toJson(),
        "frameworkApiPresent" to frameworkApiPresent.toJson(),
        "frameworkApiAccessible" to frameworkApiAccessible.toJson(),
        "frameworkCapability" to frameworkCapability.toJson(),
        "callAudioInterceptionPermission" to callAudioInterceptionPermission.toJson(),
        "pstnInterceptable" to pstnInterceptable.toJson(),
        "sessionReadiness" to sessionReadiness.toJson(),
        "supportedSampleRates" to supportedSampleRates.toJson(),
        "rxResult" to rxResult.toJson(),
        "txResult" to txResult.toJson(),
        "fullDuplexResult" to fullDuplexResult.toJson(),
        "micIsolation" to micIsolation.toJson(),
        "speakerIsolation" to speakerIsolation.toJson(),
        "latency" to latency.toJson(),
        "stability" to stability.toJson(),
        "requiredBackend" to requiredBackend.toJson(),
        "requiredProvisioning" to requiredProvisioning.toJson()
    )
}

object DeviceQualificationProfileFactory {
    fun create(
        device: DeviceSnapshot,
        permissions: PermissionSnapshot,
        capability: CallAudioCapability,
        readiness: CallAudioReadiness,
        selectedBackend: AudioBackendId
    ): DeviceQualificationProfile {
        val callAudioPermission = permissions.grants.firstOrNull {
            it.name == PermissionSnapshot.CallAudioInterception
        }
        return DeviceQualificationProfile(
            schemaVersion = 1,
            manufacturer = device.manufacturer,
            model = device.model,
            device = device.device,
            board = device.board,
            hardware = device.hardware,
            androidVersion = device.release,
            apiLevel = device.sdkInt,
            buildFingerprint = device.fingerprint,
            securityState = QualificationField.unknown("Collected by the external read-only device probe"),
            frameworkApiPresent = frameworkPresence(capability.apiPresence, device.sdkInt),
            frameworkApiAccessible = frameworkAccessibility(capability.apiPresence, device.sdkInt),
            frameworkCapability = QualificationField.observed(capability.state.name, capability.message),
            callAudioInterceptionPermission = callAudioPermission?.let {
                QualificationField.observed(
                    it.declarationState.name,
                    "protection=${it.protection.name} unavailableToNormalApp=${it.unavailableToNormalApp}"
                )
            } ?: QualificationField.unknown("Permission state unavailable"),
            pstnInterceptable = capability.pstnInterceptable?.let(QualificationField::observed)
                ?: QualificationField.unknown("Probe could not evaluate interceptability"),
            sessionReadiness = QualificationField.observed(readiness.state.name, readiness.message),
            supportedSampleRates = QualificationField.notTested("Requires an audio session"),
            rxResult = QualificationField.notTested("Requires a real PSTN experiment"),
            txResult = QualificationField.notTested("Requires a real PSTN experiment"),
            fullDuplexResult = QualificationField.notTested("Requires a real PSTN experiment"),
            micIsolation = QualificationField.notTested("Requires a real PSTN experiment"),
            speakerIsolation = QualificationField.notTested("Requires a real PSTN experiment"),
            latency = QualificationField.notTested("Requires a real PSTN experiment"),
            stability = QualificationField.notTested("Requires repeat and duration experiments"),
            requiredBackend = if (selectedBackend == AudioBackendId.Unsupported) {
                QualificationField.unknown("No usable backend was selected")
            } else {
                QualificationField.observed(selectedBackend.value)
            },
            requiredProvisioning = provisioning(capability, callAudioPermission)
        )
    }

    private fun frameworkPresence(presence: FrameworkApiPresence, apiLevel: Int): QualificationField = when {
        apiLevel < 33 -> QualificationField.observed(false, "Framework contract is unsupported by this OS")
        presence.allAccessible -> QualificationField.observed(true)
        presence.has(FrameworkApiAccess.NotPresent) -> QualificationField.observed(false)
        presence.has(FrameworkApiAccess.UnavailableOrBlocked) ->
            QualificationField.unknown("Reflection cannot prove physical method presence")
        else -> QualificationField.unknown("Framework methods were not checked")
    }

    private fun frameworkAccessibility(
        presence: FrameworkApiPresence,
        apiLevel: Int
    ): QualificationField = when {
        apiLevel < 33 -> QualificationField.notTested("Framework contract is unsupported by this OS")
        presence.allAccessible -> QualificationField.observed(true)
        presence.has(FrameworkApiAccess.UnavailableOrBlocked) -> QualificationField.observed(false)
        presence.has(FrameworkApiAccess.NotPresent) -> QualificationField.notTested("Methods are absent")
        else -> QualificationField.unknown("Framework methods were not checked")
    }

    private fun provisioning(
        capability: CallAudioCapability,
        permission: PermissionGrant?
    ): QualificationField = when {
        capability.isSupported -> QualificationField.observed("none_observed")
        permission?.unavailableToNormalApp == true -> QualificationField.observed(
            "privileged_or_role_permission",
            permission.name
        )
        else -> QualificationField.unknown("Cannot infer provisioning from current evidence")
    }

    private fun FrameworkApiPresence.has(access: FrameworkApiAccess): Boolean = listOf(
        interceptability,
        downlinkExtraction,
        uplinkInjection
    ).any { it.access == access }
}
