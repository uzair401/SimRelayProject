package com.simrelay.m0.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class InFlightOperationGate(private val direction: String) {
    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()
    private var inFlightCount = 0
    private var closing = false
    private var closed = false

    val isClosing: Boolean
        get() = lock.withLock { closing }

    fun <T> run(block: () -> T): T {
        lock.withLock {
            if (closing || closed) throw AudioSessionClosedException(direction)
            inFlightCount += 1
        }
        return try {
            block()
        } finally {
            lock.withLock {
                inFlightCount -= 1
                if (inFlightCount == 0) stateChanged.signalAll()
            }
        }
    }

    fun close(stop: () -> Unit, release: () -> Unit) {
        val ownsClose = lock.withLock {
            if (closed) return
            if (closing) {
                while (!closed) stateChanged.awaitUninterruptibly()
                return
            }
            closing = true
            true
        }
        if (!ownsClose) return
        var firstFailure: Throwable? = null
        try {
            stop()
        } catch (throwable: Throwable) {
            firstFailure = throwable
        }
        lock.withLock {
            while (inFlightCount > 0) stateChanged.awaitUninterruptibly()
        }
        try {
            release()
        } catch (throwable: Throwable) {
            if (firstFailure == null) firstFailure = throwable
        } finally {
            lock.withLock {
                closed = true
                stateChanged.signalAll()
            }
        }
        firstFailure?.let { throw it }
    }
}
