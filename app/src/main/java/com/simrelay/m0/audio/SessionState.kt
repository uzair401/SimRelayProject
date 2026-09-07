package com.simrelay.m0.audio

enum class SessionState {
    Idle,
    Probing,
    Ready,
    Capturing,
    Injecting,
    FullDuplex,
    Stopping,
    Error
}

object SessionTransitionPolicy {
    private val transitions = mapOf(
        SessionState.Idle to setOf(SessionState.Probing, SessionState.Stopping),
        SessionState.Probing to setOf(SessionState.Ready, SessionState.Error, SessionState.Idle),
        SessionState.Ready to setOf(
            SessionState.Probing,
            SessionState.Capturing,
            SessionState.Injecting,
            SessionState.FullDuplex,
            SessionState.Stopping,
            SessionState.Error
        ),
        SessionState.Capturing to setOf(SessionState.Ready, SessionState.FullDuplex, SessionState.Stopping, SessionState.Error),
        SessionState.Injecting to setOf(SessionState.Ready, SessionState.FullDuplex, SessionState.Stopping, SessionState.Error),
        SessionState.FullDuplex to setOf(SessionState.Capturing, SessionState.Injecting, SessionState.Stopping, SessionState.Error),
        SessionState.Stopping to setOf(SessionState.Idle, SessionState.Ready, SessionState.Error),
        SessionState.Error to setOf(SessionState.Probing, SessionState.Stopping, SessionState.Idle)
    )

    fun canTransition(from: SessionState, to: SessionState): Boolean =
        from == to || transitions[from].orEmpty().contains(to)
}
