package com.simrelay.m0.call

import com.simrelay.prototype.call.CallControlAction
import com.simrelay.prototype.call.CallControlCapability
import com.simrelay.prototype.call.CallControlCapabilityState

data class AndroidCallControlEnvironment(
    val apiLevel: Int,
    val readPhoneStateGranted: Boolean,
    val answerPhoneCallsGranted: Boolean,
    val callPhoneGranted: Boolean,
    val telecomAvailable: Boolean = true,
    val telephonyAvailable: Boolean = true
)

object AndroidPstnCallControlCapabilityPolicy {
    const val ReadPhoneStatePermission = "android.permission.READ_PHONE_STATE"
    const val AnswerPhoneCallsPermission = "android.permission.ANSWER_PHONE_CALLS"
    const val CallPhonePermission = "android.permission.CALL_PHONE"

    fun evaluate(environment: AndroidCallControlEnvironment): List<CallControlCapability> =
        CallControlAction.entries.map { action -> capability(action, environment) }

    private fun capability(
        action: CallControlAction,
        environment: AndroidCallControlEnvironment
    ): CallControlCapability = if (action == CallControlAction.Observe && !environment.telephonyAvailable) {
        CallControlCapability(
            action,
            CallControlCapabilityState.UnsupportedByOs,
            "Android Telephony service is unavailable"
        )
    } else if (
        action !in setOf(CallControlAction.Observe, CallControlAction.SimulateIncoming) &&
        !environment.telecomAvailable
    ) {
        CallControlCapability(
            action,
            CallControlCapabilityState.UnsupportedByOs,
            "Android Telecom service is unavailable"
        )
    } else when (action) {
        CallControlAction.Observe -> permissionCapability(
            action,
            environment.readPhoneStateGranted,
            ReadPhoneStatePermission
        )
        CallControlAction.Answer -> apiPermissionCapability(
            action,
            environment.apiLevel,
            minimumApi = 26,
            environment.answerPhoneCallsGranted,
            AnswerPhoneCallsPermission
        )
        CallControlAction.Reject,
        CallControlAction.Hangup -> apiPermissionCapability(
            action,
            environment.apiLevel,
            minimumApi = 28,
            environment.answerPhoneCallsGranted,
            AnswerPhoneCallsPermission
        )
        CallControlAction.Dial -> permissionCapability(
            action,
            environment.callPhoneGranted,
            CallPhonePermission
        )
        CallControlAction.SimulateIncoming -> CallControlCapability(
            action,
            CallControlCapabilityState.UnsupportedByOs,
            "A real PSTN backend cannot simulate an incoming carrier call"
        )
    }

    private fun apiPermissionCapability(
        action: CallControlAction,
        apiLevel: Int,
        minimumApi: Int,
        granted: Boolean,
        permission: String
    ): CallControlCapability = if (apiLevel < minimumApi) {
        CallControlCapability(
            action,
            CallControlCapabilityState.UnsupportedByOs,
            "${action.name} requires Android API $minimumApi or newer"
        )
    } else {
        permissionCapability(action, granted, permission)
    }

    private fun permissionCapability(
        action: CallControlAction,
        granted: Boolean,
        permission: String
    ): CallControlCapability = if (granted) {
        CallControlCapability(action, CallControlCapabilityState.Supported, "${action.name} is available")
    } else {
        CallControlCapability(
            action,
            CallControlCapabilityState.PermissionMissing,
            "${action.name} requires $permission",
            requiredPermission = permission
        )
    }
}
