package com.simrelay.m0.diagnostics

import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.FrameworkApiAccess
import com.simrelay.m0.audio.FrameworkApiPresence
import com.simrelay.m0.audio.FrameworkMethodAccess
import com.simrelay.m0.audio.SessionReadinessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQualificationProfileTest {
    @Test
    fun preservesUnknownAndNotTestedFutureResults() {
        val profile = DeviceQualificationProfileFactory.create(
            device = device(),
            permissions = permissions(granted = false),
            capability = capability(CapabilityState.PermissionMissing, accessiblePresence()),
            readiness = readiness(SessionReadinessState.PermissionMissing),
            selectedBackend = AudioBackendId.FrameworkInterception
        )

        assertEquals(QualificationEvidence.Unknown, profile.securityState.evidence)
        assertEquals(QualificationEvidence.NotTested, profile.rxResult.evidence)
        assertEquals(QualificationEvidence.NotTested, profile.fullDuplexResult.evidence)
        assertEquals("framework_interception", profile.requiredBackend.value)
        assertEquals("privileged_or_role_permission", profile.requiredProvisioning.value)
        assertTrue(profile.toJson().contains("\"evidence\": \"NotTested\""))
    }

    @Test
    fun blockedLookupDoesNotClaimApiAbsence() {
        val blocked = FrameworkMethodAccess(
            FrameworkApiAccess.UnavailableOrBlocked,
            NoSuchMethodException::class.java.name
        )
        val profile = DeviceQualificationProfileFactory.create(
            device = device(),
            permissions = permissions(granted = false),
            capability = capability(
                CapabilityState.ApiUnavailableOrBlocked,
                FrameworkApiPresence(blocked, blocked, blocked)
            ),
            readiness = readiness(SessionReadinessState.BackendUnavailable),
            selectedBackend = AudioBackendId.Unsupported
        )

        assertEquals(QualificationEvidence.Unknown, profile.frameworkApiPresent.evidence)
        assertEquals("false", profile.frameworkApiAccessible.value)
        assertEquals(QualificationEvidence.Unknown, profile.requiredBackend.evidence)
    }

    private fun device() = DeviceSnapshot(
        manufacturer = "Manufacturer",
        brand = "Brand",
        model = "Model",
        device = "Device",
        product = "Product",
        hardware = "Hardware",
        board = "Board",
        fingerprint = "Fingerprint",
        buildId = "BuildId",
        buildType = "user",
        buildTags = "release-keys",
        bootloader = "Bootloader",
        socManufacturer = null,
        socModel = null,
        sdkInt = 36,
        release = "16",
        securityPatch = "2026-01-01",
        supportedAbis = listOf("arm64-v8a")
    )

    private fun permissions(granted: Boolean) = PermissionSnapshot(
        listOf(
            PermissionGrant(
                PermissionSnapshot.CallAudioInterception,
                if (granted) {
                    PermissionDeclarationState.DeclaredAndGranted
                } else {
                    PermissionDeclarationState.DeclaredNotGranted
                },
                PermissionProtection.SignatureOrPrivilegedOrRole
            )
        )
    )

    private fun accessiblePresence(): FrameworkApiPresence {
        val accessible = FrameworkMethodAccess(FrameworkApiAccess.Accessible)
        return FrameworkApiPresence(accessible, accessible, accessible)
    }

    private fun capability(state: CapabilityState, presence: FrameworkApiPresence) =
        CallAudioCapability(
            AudioBackendId.FrameworkInterception,
            state,
            state.name,
            apiPresence = presence
        )

    private fun readiness(state: SessionReadinessState) = CallAudioReadiness(
        AudioBackendId.FrameworkInterception,
        state,
        state.name
    )
}
