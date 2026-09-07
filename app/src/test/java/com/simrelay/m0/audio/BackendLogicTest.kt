package com.simrelay.m0.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendLogicTest {
    @Test
    fun selectsFrameworkForSupportedOrProvisionableCapabilityOnSupportedOs() {
        assertEquals(
            AudioBackendId.FrameworkInterception,
            BackendSelectionPolicy.select(33, CapabilityState.Supported)
        )
        assertEquals(
            AudioBackendId.Unsupported,
            BackendSelectionPolicy.select(32, CapabilityState.Supported)
        )
        assertEquals(
            AudioBackendId.FrameworkInterception,
            BackendSelectionPolicy.select(36, CapabilityState.PermissionMissing)
        )
        assertEquals(
            AudioBackendId.Unsupported,
            BackendSelectionPolicy.select(36, CapabilityState.CallNotActive)
        )
    }

    @Test
    fun mapsDiagnosticFailuresToExplicitStates() {
        assertEquals(
            CapabilityState.ApiUnavailableOrBlocked,
            CapabilityFailureMapper.fromThrowable(
                AudioBackendId.FrameworkInterception,
                NoSuchMethodException("missing")
            ).state
        )
        assertEquals(
            CapabilityState.PermissionMissing,
            CapabilityFailureMapper.fromThrowable(
                AudioBackendId.FrameworkInterception,
                SecurityException("denied")
            ).state
        )
        assertEquals(
            CapabilityState.DeviceNotInterceptable,
            CapabilityFailureMapper.fromThrowable(
                AudioBackendId.FrameworkInterception,
                UnsupportedOperationException("PSTN call audio not accessible")
            ).state
        )
    }

    @Test
    fun validatesSessionTransitions() {
        assertTrue(SessionTransitionPolicy.canTransition(SessionState.Idle, SessionState.Probing))
        assertTrue(SessionTransitionPolicy.canTransition(SessionState.Ready, SessionState.FullDuplex))
        assertTrue(SessionTransitionPolicy.canTransition(SessionState.FullDuplex, SessionState.Stopping))
        assertFalse(SessionTransitionPolicy.canTransition(SessionState.Idle, SessionState.FullDuplex))
        assertFalse(SessionTransitionPolicy.canTransition(SessionState.Capturing, SessionState.Injecting))
        assertTrue(SessionTransitionPolicy.canTransition(SessionState.Capturing, SessionState.Error))
        assertTrue(SessionTransitionPolicy.canTransition(SessionState.Injecting, SessionState.Error))
    }

    @Test
    fun separatesCapabilityFromSessionReadiness() {
        val capability = CallAudioCapability(
            AudioBackendId.FrameworkInterception,
            CapabilityState.Supported,
            "Device supports interception"
        )
        val readiness = CallAudioReadiness(
            AudioBackendId.FrameworkInterception,
            SessionReadinessState.FrameworkValidationRequired,
            "Framework validates current session",
            audioMode = 0
        )

        assertTrue(capability.isSupported)
        assertEquals(SessionReadinessState.FrameworkValidationRequired, readiness.state)
        assertEquals(AudioBackendId.FrameworkInterception, readiness.backendId)
    }

    @Test
    fun representsOsAbsenceSeparatelyFromBlockedSystemApiAccess() {
        assertEquals(FrameworkApiAccess.NotPresent, FrameworkApiPresence.NotPresent.interceptability.access)
        val blocked = FrameworkMethodAccess(
            FrameworkApiAccess.UnavailableOrBlocked,
            NoSuchMethodException::class.java.name,
            "lookup filtered"
        )

        assertFalse(blocked.isAccessible)
        assertEquals(FrameworkApiAccess.UnavailableOrBlocked, blocked.access)
    }

    @Test
    fun mapsFailuresUsingOperationContext() {
        val backendId = AudioBackendId.FrameworkInterception
        assertEquals(
            CapabilityState.CallNotActive,
            CapabilityFailureMapper.fromThrowable(
                backendId,
                IllegalStateException("redirect mode unavailable"),
                operation = AudioOperation.OpenDownlink
            ).state
        )
        assertEquals(
            CapabilityState.RuntimeFailure,
            CapabilityFailureMapper.fromThrowable(
                backendId,
                IllegalStateException("released"),
                operation = AudioOperation.WriteUplink
            ).state
        )
        assertEquals(
            CapabilityState.UnsupportedAudioFormat,
            CapabilityFailureMapper.fromThrowable(
                backendId,
                IllegalArgumentException("bad format"),
                operation = AudioOperation.OpenUplink
            ).state
        )
        assertEquals(
            CapabilityState.ArtifactFailure,
            CapabilityFailureMapper.fromThrowable(
                backendId,
                java.io.IOException("disk failure"),
                operation = AudioOperation.ArtifactWrite
            ).state
        )
        assertEquals(
            AudioOperation.ArtifactWrite,
            CapabilityFailureMapper.fromThrowable(
                backendId,
                java.io.IOException("disk failure"),
                operation = AudioOperation.ArtifactWrite
            ).operation
        )
    }

    @Test
    fun backendRequirementsAreActionSpecific() {
        val recordAudio = PermissionRequirement("android.permission.RECORD_AUDIO", true)
        val requirements = BackendRequirements(
            mapOf(AudioAction.DownlinkCapture to setOf(recordAudio))
        )

        assertEquals(setOf(recordAudio), requirements.permissionsFor(AudioAction.DownlinkCapture))
        assertTrue(requirements.permissionsFor(AudioAction.UplinkInjection).isEmpty())
    }
}
