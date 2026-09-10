package com.simrelay.prototype.call

class FakeCallControlBackend : CallControlBackend {
    private val lock = Any()
    private var listener: CallControlListener? = null
    private var current = CallControlSnapshot(PrototypeCallState.Idle)
    private var closed = false

    override fun snapshot(): CallControlSnapshot = synchronized(lock) { current }

    override fun capabilities(): List<CallControlCapability> = CallControlAction.entries.map {
        CallControlCapability(it, CallControlCapabilityState.Supported, "Fake call control supports ${it.name}")
    }

    override fun setListener(listener: CallControlListener?) {
        synchronized(lock) { this.listener = listener }
    }

    override fun simulateIncomingCall(sessionId: String, displayIdentity: String): CallControlResult =
        beginCall(sessionId, displayIdentity, CallDirection.Incoming, PrototypeCallState.Incoming) {
            transition(PrototypeCallState.Ringing)
        }

    override fun dial(sessionId: String, displayIdentity: String): CallControlResult =
        beginCall(sessionId, displayIdentity, CallDirection.Outgoing, PrototypeCallState.Dialing) {
            transition(PrototypeCallState.Active)
        }

    override fun answer(sessionId: String): CallControlResult = synchronized(lock) {
        if (!matches(sessionId) || current.state !in setOf(PrototypeCallState.Incoming, PrototypeCallState.Ringing)) {
            return@synchronized failure("No matching ringing call")
        }
        transitionLocked(PrototypeCallState.Answering)
        transitionLocked(PrototypeCallState.Active)
        CallControlResult.Success(current)
    }

    override fun reject(sessionId: String): CallControlResult = terminate(
        sessionId,
        setOf(PrototypeCallState.Incoming, PrototypeCallState.Ringing),
        PrototypeCallState.Rejected
    )

    override fun hangup(sessionId: String): CallControlResult = terminate(
        sessionId,
        PrototypeCallState.entries.toSet() - setOf(
            PrototypeCallState.Idle,
            PrototypeCallState.Ended,
            PrototypeCallState.Rejected,
            PrototypeCallState.Failure
        ),
        PrototypeCallState.Ended
    )

    override fun close() {
        synchronized(lock) {
            if (closed) return
            if (current.state != PrototypeCallState.Idle) {
                transitionLocked(PrototypeCallState.Ended)
                transitionLocked(PrototypeCallState.Idle, clearCall = true)
            }
            listener = null
            closed = true
        }
    }

    private fun beginCall(
        sessionId: String,
        displayIdentity: String,
        direction: CallDirection,
        initialState: PrototypeCallState,
        afterStart: () -> Unit
    ): CallControlResult = synchronized(lock) {
        if (closed) return@synchronized failure("Call backend is closed")
        if (current.state != PrototypeCallState.Idle) return@synchronized failure("A call is already active")
        if (sessionId.isBlank() || displayIdentity.isBlank()) return@synchronized failure("Invalid fake call")
        current = CallControlSnapshot(
            initialState,
            PrototypeCall(sessionId, direction, displayIdentity)
        )
        notifyLocked()
        afterStart()
        CallControlResult.Success(current)
    }

    private fun terminate(
        sessionId: String,
        validStates: Set<PrototypeCallState>,
        terminalState: PrototypeCallState
    ): CallControlResult = synchronized(lock) {
        if (!matches(sessionId) || current.state !in validStates) return@synchronized failure("No matching call")
        transitionLocked(terminalState)
        if (terminalState != PrototypeCallState.Ended) transitionLocked(PrototypeCallState.Ended)
        transitionLocked(PrototypeCallState.Idle, clearCall = true)
        CallControlResult.Success(current)
    }

    private fun matches(sessionId: String): Boolean = current.call?.sessionId == sessionId

    private fun transition(state: PrototypeCallState) {
        synchronized(lock) { transitionLocked(state) }
    }

    private fun transitionLocked(state: PrototypeCallState, clearCall: Boolean = false) {
        current = current.copy(state = state, call = if (clearCall) null else current.call, error = null)
        notifyLocked()
    }

    private fun notifyLocked() {
        listener?.onCallStateChanged(current)
    }

    private fun failure(reason: String): CallControlResult.Failure = CallControlResult.Failure(reason)
}
