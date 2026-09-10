package com.simrelay.m0.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.simrelay.prototype.call.CallControlAction
import com.simrelay.prototype.call.CallControlBackend
import com.simrelay.prototype.call.CallControlCapability
import com.simrelay.prototype.call.CallControlCapabilityState
import com.simrelay.prototype.call.CallControlListener
import com.simrelay.prototype.call.CallControlResult
import com.simrelay.prototype.call.CallControlSnapshot
import com.simrelay.prototype.call.CallDirection
import com.simrelay.prototype.call.PrototypeCall
import com.simrelay.prototype.call.PrototypeCallState
import java.util.UUID

class AndroidPstnCallControlBackend(
    context: Context,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) : CallControlBackend {
    private val applicationContext = context.applicationContext
    private val telecom = applicationContext.getSystemService(TelecomManager::class.java)
    private val monitor = CallStateMonitor(applicationContext, ::onPlatformState)
    private val lock = Any()
    private var listener: CallControlListener? = null
    private var current = CallControlSnapshot(PrototypeCallState.Idle)
    private var closed = false

    init {
        monitor.start()
    }

    override fun snapshot(): CallControlSnapshot = synchronized(lock) { current }

    override fun capabilities(): List<CallControlCapability> =
        AndroidPstnCallControlCapabilityPolicy.evaluate(
            AndroidCallControlEnvironment(
                apiLevel = Build.VERSION.SDK_INT,
                readPhoneStateGranted = granted(Manifest.permission.READ_PHONE_STATE),
                answerPhoneCallsGranted = granted(AndroidPstnCallControlCapabilityPolicy.AnswerPhoneCallsPermission),
                callPhoneGranted = granted(Manifest.permission.CALL_PHONE),
                telecomAvailable = telecom != null,
                telephonyAvailable = applicationContext.getSystemService(TelephonyManager::class.java) != null
            )
        )

    override fun setListener(listener: CallControlListener?) {
        val snapshot = synchronized(lock) {
            this.listener = listener
            current
        }
        listener?.onCallStateChanged(snapshot)
    }

    override fun simulateIncomingCall(sessionId: String, displayIdentity: String): CallControlResult =
        unsupported(CallControlAction.SimulateIncoming)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun answer(sessionId: String): CallControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !granted(AndroidPstnCallControlCapabilityPolicy.AnswerPhoneCallsPermission)
        ) return unsupported(CallControlAction.Answer)
        return execute(
            action = CallControlAction.Answer,
            sessionId = sessionId,
            validStates = setOf(PrototypeCallState.Incoming, PrototypeCallState.Ringing)
        ) {
            transition(PrototypeCallState.Answering)
            requireNotNull(telecom).acceptRingingCall()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun reject(sessionId: String): CallControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            !granted(AndroidPstnCallControlCapabilityPolicy.AnswerPhoneCallsPermission)
        ) return unsupported(CallControlAction.Reject)
        return execute(
            action = CallControlAction.Reject,
            sessionId = sessionId,
            validStates = setOf(PrototypeCallState.Incoming, PrototypeCallState.Ringing)
        ) {
            if (!requireNotNull(telecom).endCall()) error("Telecom did not end the ringing call")
            transition(PrototypeCallState.Rejected)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun hangup(sessionId: String): CallControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            !granted(AndroidPstnCallControlCapabilityPolicy.AnswerPhoneCallsPermission)
        ) return unsupported(CallControlAction.Hangup)
        return execute(
            action = CallControlAction.Hangup,
            sessionId = sessionId,
            validStates = setOf(
                PrototypeCallState.Answering,
                PrototypeCallState.Active,
                PrototypeCallState.Dialing
            )
        ) {
            if (!requireNotNull(telecom).endCall()) error("Telecom did not end the active call")
            transition(PrototypeCallState.Ended)
        }
    }

    override fun dial(sessionId: String, displayIdentity: String): CallControlResult {
        val capability = capability(CallControlAction.Dial)
        if (!capability.isSupported) return CallControlResult.Failure(capability.message, capability)
        if (sessionId.isBlank() || displayIdentity.isBlank()) {
            return invalid(CallControlAction.Dial, "A session and dial address are required")
        }
        synchronized(lock) {
            if (closed) return invalid(CallControlAction.Dial, "Call control backend is closed")
            if (current.state != PrototypeCallState.Idle) return invalid(CallControlAction.Dial, "A call is already active")
            current = CallControlSnapshot(
                PrototypeCallState.Dialing,
                PrototypeCall(sessionId, CallDirection.Outgoing, "PSTN destination")
            )
            notifyLocked()
        }
        return try {
            requireNotNull(telecom).placeCall(Uri.fromParts("tel", displayIdentity, null), Bundle())
            CallControlResult.Success(snapshot())
        } catch (exception: SecurityException) {
            runtimeFailure(CallControlAction.Dial, exception, CallControlCapabilityState.RoleOrPrivilegeMissing)
        } catch (exception: RuntimeException) {
            runtimeFailure(CallControlAction.Dial, exception)
        }
    }

    override fun close() {
        runCatching { monitor.close() }.onFailure { throwable ->
            Log.e("SimRelayPrototype", "event=call_monitor_close_failed detail=${throwable.javaClass.simpleName}")
        }
        synchronized(lock) {
            listener = null
            closed = true
        }
    }

    private fun execute(
        action: CallControlAction,
        sessionId: String,
        validStates: Set<PrototypeCallState>,
        operation: () -> Unit
    ): CallControlResult {
        val capability = capability(action)
        if (!capability.isSupported) return CallControlResult.Failure(capability.message, capability)
        val valid = synchronized(lock) {
            !closed && current.call?.sessionId == sessionId && current.state in validStates
        }
        if (!valid) return invalid(action, "No matching call is available for ${action.name}")
        return try {
            operation()
            CallControlResult.Success(snapshot())
        } catch (exception: SecurityException) {
            runtimeFailure(action, exception, CallControlCapabilityState.RoleOrPrivilegeMissing)
        } catch (exception: RuntimeException) {
            runtimeFailure(action, exception)
        }
    }

    private fun onPlatformState(state: CallState) {
        synchronized(lock) {
            if (closed) return
            when (state) {
                CallState.Ringing -> {
                    val call = current.call ?: PrototypeCall(
                        idFactory(),
                        CallDirection.Incoming,
                        "PSTN caller"
                    )
                    current = CallControlSnapshot(PrototypeCallState.Ringing, call)
                    notifyLocked()
                }
                CallState.OffHook -> {
                    val call = current.call ?: PrototypeCall(
                        idFactory(),
                        CallDirection.Unknown,
                        "PSTN call"
                    )
                    current = CallControlSnapshot(PrototypeCallState.Active, call)
                    notifyLocked()
                }
                CallState.Idle -> {
                    if (current.state != PrototypeCallState.Idle) {
                        current = current.copy(state = PrototypeCallState.Ended)
                        notifyLocked()
                    }
                    current = CallControlSnapshot(PrototypeCallState.Idle)
                    notifyLocked()
                }
                CallState.Unknown -> {
                    current = current.copy(error = "PSTN call state is unavailable")
                    notifyLocked()
                }
            }
        }
    }

    private fun transition(state: PrototypeCallState) {
        synchronized(lock) {
            current = current.copy(state = state, error = null)
            notifyLocked()
        }
    }

    private fun notifyLocked() {
        runCatching { listener?.onCallStateChanged(current) }.onFailure { throwable ->
            Log.e("SimRelayPrototype", "event=call_state_callback_failed detail=${throwable.javaClass.simpleName}")
        }
    }

    private fun capability(action: CallControlAction): CallControlCapability =
        capabilities().first { it.action == action }

    private fun unsupported(action: CallControlAction): CallControlResult.Failure {
        val capability = capability(action)
        return CallControlResult.Failure(capability.message, capability)
    }

    private fun invalid(action: CallControlAction, reason: String): CallControlResult.Failure {
        val capability = CallControlCapability(
            action,
            CallControlCapabilityState.InvalidRequest,
            reason
        )
        return CallControlResult.Failure(reason, capability)
    }

    private fun runtimeFailure(
        action: CallControlAction,
        exception: RuntimeException,
        state: CallControlCapabilityState = CallControlCapabilityState.RuntimeFailure
    ): CallControlResult.Failure {
        val message = exception.message ?: exception.javaClass.simpleName
        val capability = CallControlCapability(action, state, message)
        synchronized(lock) {
            current = current.copy(state = PrototypeCallState.Failure, error = message)
            notifyLocked()
        }
        return CallControlResult.Failure(message, capability)
    }

    private fun granted(permission: String): Boolean =
        applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
