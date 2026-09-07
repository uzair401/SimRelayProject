package com.simrelay.m0.diagnostics

import com.simrelay.m0.audio.CallAudioCapability
import com.simrelay.m0.audio.CallAudioReadiness
import com.simrelay.m0.audio.SessionReadinessState
import com.simrelay.m0.call.CallState

object CallSessionReadinessEvaluator {
    fun evaluate(
        capability: CallAudioCapability,
        backendReadiness: CallAudioReadiness,
        callState: CallState
    ): CallAudioReadiness {
        if (!capability.isSupported) return backendReadiness
        return when (callState) {
            CallState.Idle -> backendReadiness.copy(
                state = SessionReadinessState.SessionNotReady,
                message = "No active call is currently observed"
            )
            CallState.Ringing -> backendReadiness.copy(
                state = SessionReadinessState.SessionNotReady,
                message = "The observed call is ringing but not active"
            )
            CallState.OffHook, CallState.Unknown -> backendReadiness
        }
    }
}
