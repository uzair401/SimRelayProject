package com.simrelay.prototype.media

class PcmSampleBuffer(
    capacitySamples: Int
) {
    private val values = ShortArray(capacitySamples)
    private var readIndex = 0
    private var size = 0

    init {
        require(capacitySamples > 0)
    }

    @Synchronized
    fun write(samples: ShortArray): Int {
        var dropped = 0
        samples.forEach { sample ->
            if (size == values.size) {
                readIndex = (readIndex + 1) % values.size
                size -= 1
                dropped += 1
            }
            values[(readIndex + size) % values.size] = sample
            size += 1
        }
        return dropped
    }

    @Synchronized
    fun read(output: ShortArray): Int {
        val count = minOf(output.size, size)
        repeat(count) { index ->
            output[index] = values[readIndex]
            readIndex = (readIndex + 1) % values.size
        }
        size -= count
        return count
    }

    @Synchronized
    fun clear() {
        readIndex = 0
        size = 0
    }

    @Synchronized
    fun availableSamples(): Int = size
}
