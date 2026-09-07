package com.simrelay.m0.audio

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SafeBackgroundExecutorTest {
    @Test
    fun containsTaskFailureWithOperationContext() {
        val failure = IllegalStateException("boom")
        var captured: Pair<AudioOperation, Throwable>? = null
        val executor = SafeBackgroundExecutor(Executor { it.run() }) { operation, throwable ->
            captured = operation to throwable
        }

        executor.execute(AudioOperation.ReadDownlink) { throw failure }

        assertEquals(AudioOperation.ReadDownlink, captured?.first)
        assertSame(failure, captured?.second)
    }

    @Test
    fun containsExecutorSubmissionFailure() {
        val failure = RejectedExecutionException("stopped")
        var captured: Pair<AudioOperation, Throwable>? = null
        val executor = SafeBackgroundExecutor(Executor { throw failure }) { operation, throwable ->
            captured = operation to throwable
        }

        executor.execute(AudioOperation.StopUplink) {}

        assertEquals(AudioOperation.StopUplink, captured?.first)
        assertSame(failure, captured?.second)
    }
}
