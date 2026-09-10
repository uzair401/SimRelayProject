package com.simrelay.prototype.call

enum class PrototypeCallState {
    Idle,
    Incoming,
    Ringing,
    Answering,
    Active,
    Rejected,
    Dialing,
    Ended,
    Failure
}

enum class CallDirection {
    Incoming,
    Outgoing,
    Unknown
}

enum class CallControlAction {
    Observe,
    Answer,
    Reject,
    Hangup,
    Dial,
    SimulateIncoming
}

enum class CallControlCapabilityState {
    Supported,
    UnsupportedByOs,
    PermissionMissing,
    RoleOrPrivilegeMissing,
    InvalidRequest,
    RuntimeFailure
}

data class CallControlCapability(
    val action: CallControlAction,
    val state: CallControlCapabilityState,
    val message: String,
    val requiredPermission: String? = null
) {
    val isSupported: Boolean
        get() = state == CallControlCapabilityState.Supported
}

data class PrototypeCall(
    val sessionId: String,
    val direction: CallDirection,
    val displayIdentity: String
)

data class CallControlSnapshot(
    val state: PrototypeCallState,
    val call: PrototypeCall? = null,
    val error: String? = null
)

sealed interface CallControlResult {
    data class Success(val snapshot: CallControlSnapshot) : CallControlResult
    data class Failure(
        val reason: String,
        val capability: CallControlCapability? = null
    ) : CallControlResult
}

fun interface CallControlListener {
    fun onCallStateChanged(snapshot: CallControlSnapshot)
}

interface CallControlBackend : AutoCloseable {
    fun snapshot(): CallControlSnapshot
    fun capabilities(): List<CallControlCapability>
    fun setListener(listener: CallControlListener?)
    fun simulateIncomingCall(sessionId: String, displayIdentity: String): CallControlResult
    fun answer(sessionId: String): CallControlResult
    fun reject(sessionId: String): CallControlResult
    fun hangup(sessionId: String): CallControlResult
    fun dial(sessionId: String, displayIdentity: String): CallControlResult
    override fun close()
}
