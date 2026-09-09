package com.simrelay.prototype.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface SignalDecodeResult {
    data class Success(val message: SignalMessage) : SignalDecodeResult
    data class Failure(val reason: String) : SignalDecodeResult
}

object SignalCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun encode(message: SignalMessage): String {
        SignalValidator.requireValid(message)
        return json.encodeToString(SignalMessage.serializer(), message)
    }

    fun decode(value: String): SignalDecodeResult {
        val message = try {
            json.decodeFromString(SignalMessage.serializer(), value)
        } catch (exception: SerializationException) {
            return SignalDecodeResult.Failure("Malformed JSON message")
        } catch (exception: IllegalArgumentException) {
            return SignalDecodeResult.Failure("Invalid message value")
        }
        return SignalValidator.validate(message)?.let(SignalDecodeResult::Failure)
            ?: SignalDecodeResult.Success(message)
    }
}
