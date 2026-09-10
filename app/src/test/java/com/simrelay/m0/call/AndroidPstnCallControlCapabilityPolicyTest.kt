package com.simrelay.m0.call

import com.simrelay.prototype.call.CallControlAction
import com.simrelay.prototype.call.CallControlCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidPstnCallControlCapabilityPolicyTest {
    @Test
    fun mapsPermissionsAndApiGatesPerOperation() {
        val capabilities = AndroidPstnCallControlCapabilityPolicy.evaluate(
            AndroidCallControlEnvironment(
                apiLevel = 27,
                readPhoneStateGranted = true,
                answerPhoneCallsGranted = false,
                callPhoneGranted = true
            )
        ).associateBy { it.action }

        assertEquals(CallControlCapabilityState.Supported, capabilities.getValue(CallControlAction.Observe).state)
        assertEquals(CallControlCapabilityState.PermissionMissing, capabilities.getValue(CallControlAction.Answer).state)
        assertEquals(CallControlCapabilityState.UnsupportedByOs, capabilities.getValue(CallControlAction.Reject).state)
        assertEquals(CallControlCapabilityState.UnsupportedByOs, capabilities.getValue(CallControlAction.Hangup).state)
        assertEquals(CallControlCapabilityState.Supported, capabilities.getValue(CallControlAction.Dial).state)
        assertEquals(CallControlCapabilityState.UnsupportedByOs, capabilities.getValue(CallControlAction.SimulateIncoming).state)
    }

    @Test
    fun reportsAllSupportedPublicActionsWhenApiAndPermissionsPermitThem() {
        val capabilities = AndroidPstnCallControlCapabilityPolicy.evaluate(
            AndroidCallControlEnvironment(
                apiLevel = 33,
                readPhoneStateGranted = true,
                answerPhoneCallsGranted = true,
                callPhoneGranted = true
            )
        ).associateBy { it.action }

        CallControlAction.entries.filterNot { it == CallControlAction.SimulateIncoming }.forEach {
            assertEquals(CallControlCapabilityState.Supported, capabilities.getValue(it).state)
        }
    }

    @Test
    fun reportsPlatformUnavailableWhenTelecomServiceIsAbsent() {
        val capabilities = AndroidPstnCallControlCapabilityPolicy.evaluate(
            AndroidCallControlEnvironment(
                apiLevel = 33,
                readPhoneStateGranted = true,
                answerPhoneCallsGranted = true,
                callPhoneGranted = true,
                telecomAvailable = false
            )
        ).associateBy { it.action }

        CallControlAction.entries.filterNot {
            it == CallControlAction.SimulateIncoming || it == CallControlAction.Observe
        }.forEach {
            assertEquals(CallControlCapabilityState.UnsupportedByOs, capabilities.getValue(it).state)
        }
        assertEquals(CallControlCapabilityState.Supported, capabilities.getValue(CallControlAction.Observe).state)
    }

    @Test
    fun reportsObservationUnavailableWhenTelephonyServiceIsAbsent() {
        val capability = AndroidPstnCallControlCapabilityPolicy.evaluate(
            AndroidCallControlEnvironment(
                apiLevel = 33,
                readPhoneStateGranted = true,
                answerPhoneCallsGranted = true,
                callPhoneGranted = true,
                telephonyAvailable = false
            )
        ).first { it.action == CallControlAction.Observe }

        assertEquals(CallControlCapabilityState.UnsupportedByOs, capability.state)
    }
}
