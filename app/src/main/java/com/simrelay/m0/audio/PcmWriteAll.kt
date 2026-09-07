package com.simrelay.m0.audio

object PcmWriteAll {
    fun write(
        offset: Int,
        count: Int,
        shouldContinue: () -> Boolean = { true },
        writer: (offset: Int, count: Int) -> Int
    ): Int {
        require(offset >= 0)
        require(count >= 0)
        var totalWritten = 0
        while (totalWritten < count && shouldContinue()) {
            val remaining = count - totalWritten
            val written = writer(offset + totalWritten, remaining)
            if (written < 0) throw AudioWriteException(written)
            if (written == 0) throw AudioZeroWriteException()
            if (written > remaining) throw AudioWriteContractException(written, remaining)
            totalWritten += written
        }
        return totalWritten
    }
}
