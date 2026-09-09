package com.simrelay.prototype.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCallControlBackendTest {
    @Test
    fun incomingAnswerHangupReturnsToIdleAndAllowsSecondCall() {
        val backend = FakeCallControlBackend()
        val states = mutableListOf<PrototypeCallState>()
        backend.setListener { states += it.state }

        assertTrue(backend.simulateIncomingCall("one", "Prototype caller") is CallControlResult.Success)
        assertEquals(PrototypeCallState.Ringing, backend.snapshot().state)
        assertTrue(backend.answer("one") is CallControlResult.Success)
        assertEquals(PrototypeCallState.Active, backend.snapshot().state)
        assertTrue(backend.hangup("one") is CallControlResult.Success)
        assertEquals(PrototypeCallState.Idle, backend.snapshot().state)
        assertTrue(backend.simulateIncomingCall("two", "Second prototype caller") is CallControlResult.Success)
        assertEquals(PrototypeCallState.Ringing, backend.snapshot().state)
        assertTrue(states.containsAll(listOf(PrototypeCallState.Ringing, PrototypeCallState.Active, PrototypeCallState.Ended)))
    }

    @Test
    fun rejectsDuplicateAndMismatchedSessions() {
        val backend = FakeCallControlBackend()

        backend.simulateIncomingCall("one", "Prototype caller")

        assertTrue(backend.dial("two", "Prototype destination") is CallControlResult.Failure)
        assertTrue(backend.answer("two") is CallControlResult.Failure)
        assertEquals(PrototypeCallState.Ringing, backend.snapshot().state)
    }

    @Test
    fun rejectReturnsToIdleWithoutActiveState() {
        val backend = FakeCallControlBackend()
        val states = mutableListOf<PrototypeCallState>()
        backend.setListener { states += it.state }

        backend.simulateIncomingCall("one", "Prototype caller")
        assertTrue(backend.reject("one") is CallControlResult.Success)

        assertEquals(PrototypeCallState.Idle, backend.snapshot().state)
        assertTrue(PrototypeCallState.Rejected in states)
        assertTrue(PrototypeCallState.Active !in states)
    }
}
