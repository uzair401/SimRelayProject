package com.simrelay.m0.audio

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightOperationGateTest {
    @Test
    fun stopsThenWaitsForBlockingOperationBeforeRelease() {
        val gate = InFlightOperationGate("test")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val operationExited = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val operationThread = thread {
            gate.run {
                events += "read-start"
                entered.countDown()
                unblock.await()
                events += "read-end"
            }
            operationExited.set(true)
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val closeThread = thread {
            gate.close(
                stop = {
                    events += "stop"
                    unblock.countDown()
                },
                release = {
                    assertTrue(operationExited.get())
                    events += "release"
                }
            )
        }

        operationThread.join(2_000)
        closeThread.join(2_000)
        assertTrue(!operationThread.isAlive)
        assertTrue(!closeThread.isAlive)
        assertEquals(listOf("read-start", "stop", "read-end", "release"), events)
    }

    @Test
    fun closeIsIdempotentAndRejectsNewOperations() {
        val gate = InFlightOperationGate("test")
        val stopCount = AtomicInteger()
        val releaseCount = AtomicInteger()

        gate.close(stopCount::incrementAndGet, releaseCount::incrementAndGet)
        gate.close(stopCount::incrementAndGet, releaseCount::incrementAndGet)

        assertEquals(1, stopCount.get())
        assertEquals(1, releaseCount.get())
        assertThrows(AudioSessionClosedException::class.java) { gate.run {} }
    }
}
