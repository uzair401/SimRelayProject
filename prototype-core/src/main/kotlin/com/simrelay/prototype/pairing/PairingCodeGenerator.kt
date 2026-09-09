package com.simrelay.prototype.pairing

import java.security.SecureRandom

class PairingCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
    private val digitCount: Int = 6
) {
    init {
        require(digitCount in 4..9)
    }

    fun generate(): String {
        val bound = powerOfTen(digitCount)
        return random.nextInt(bound).toString().padStart(digitCount, '0')
    }

    private fun powerOfTen(exponent: Int): Int {
        var result = 1
        repeat(exponent) { result *= 10 }
        return result
    }
}
