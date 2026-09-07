package com.simrelay.m0.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmWriteAllTest {
    @Test
    fun advancesOffsetUntilEverySampleIsWritten() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val results = ArrayDeque(listOf(3, 2, 3))

        val written = PcmWriteAll.write(offset = 4, count = 8) { offset, count ->
            calls += offset to count
            results.removeFirst()
        }

        assertEquals(8, written)
        assertEquals(listOf(4 to 8, 7 to 5, 9 to 3), calls)
    }

    @Test
    fun stopsAtCompletedPrefixWhenShutdownBegins() {
        var continueWriting = true

        val written = PcmWriteAll.write(
            offset = 0,
            count = 8,
            shouldContinue = { continueWriting }
        ) { _, _ ->
            continueWriting = false
            3
        }

        assertEquals(3, written)
    }

    @Test
    fun rejectsZeroNegativeAndOversizedWriteResults() {
        assertThrows(AudioZeroWriteException::class.java) {
            PcmWriteAll.write(0, 4) { _, _ -> 0 }
        }
        assertThrows(AudioWriteException::class.java) {
            PcmWriteAll.write(0, 4) { _, _ -> -2 }
        }
        assertThrows(AudioWriteContractException::class.java) {
            PcmWriteAll.write(0, 4) { _, _ -> 5 }
        }
    }
}
