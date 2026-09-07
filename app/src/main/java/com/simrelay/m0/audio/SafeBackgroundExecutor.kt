package com.simrelay.m0.audio

import java.util.concurrent.Executor

class SafeBackgroundExecutor(
    private val executor: Executor,
    private val failureSink: (AudioOperation, Throwable) -> Unit
) {
    fun execute(operation: AudioOperation, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (throwable: Throwable) {
                    failureSink(operation, throwable)
                }
            }
        } catch (throwable: Throwable) {
            failureSink(operation, throwable)
        }
    }
}
