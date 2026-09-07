package com.simrelay.m0.diagnostics

import com.simrelay.m0.audio.AudioBackendId
import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.CapabilityState
import com.simrelay.m0.audio.SessionReadinessState
import com.simrelay.m0.call.CallState
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSessionReadinessEvaluatorTest {
    private val supported = CallAudioCapability(
        AudioBackendId.FrameworkInterception,
        CapabilityState.Supported,
        "supported"
    )
    private val frameworkReadiness = CallAudioReadiness(
        AudioBackendId.FrameworkInterception,
        SessionReadinessState.FrameworkValidationRequired,
        "framework validates"
    )

    @Test
    fun idleCallChangesReadinessWithoutChangingCapability() {
        val readiness = CallSessionReadinessEvaluator.evaluate(
            supported,
            frameworkReadiness,
            CallState.Idle
        )

        assertEquals(CapabilityState.Supported, supported.state)
        assertEquals(SessionReadinessState.SessionNotReady, readiness.state)
    }

    @Test
    fun offHookAndUnknownStatesAllowFrameworkValidation() {
        assertEquals(
            SessionReadinessState.FrameworkValidationRequired,
            CallSessionReadinessEvaluator.evaluate(supported, frameworkReadiness, CallState.OffHook).state
        )
        assertEquals(
            SessionReadinessState.FrameworkValidationRequired,
            CallSessionReadinessEvaluator.evaluate(supported, frameworkReadiness, CallState.Unknown).state
        )
    }

    @Test
    fun unavailableCapabilityKeepsBackendReadiness() {
        val unavailable = supported.copy(state = CapabilityState.ApiUnavailableOrBlocked)
        val backendUnavailable = frameworkReadiness.copy(state = SessionReadinessState.BackendUnavailable)

        assertEquals(
            backendUnavailable,
            CallSessionReadinessEvaluator.evaluate(unavailable, backendUnavailable, CallState.Idle)
        )
    }
}
