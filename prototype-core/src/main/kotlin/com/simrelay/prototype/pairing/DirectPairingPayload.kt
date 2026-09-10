package com.simrelay.prototype.pairing

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class DirectPairingPayload(
    val host: String,
    val port: Int,
    val pairingToken: String,
    val expiresAtMillis: Long
) {
    init {
        require(host.isNotBlank() && host.length <= 255 && host.none(Char::isWhitespace))
        require(port in 1..65_535)
        require(pairingToken.length in 6..128 && pairingToken.none(Char::isWhitespace))
        require(expiresAtMillis > 0)
    }
}

object DirectPairingPayloadCodec {
    private const val Scheme = "simrelay-direct"
    private const val Path = "/pair"

    fun encode(payload: DirectPairingPayload): String {
        val query = listOf(
            "token" to payload.pairingToken,
            "expires" to payload.expiresAtMillis.toString()
        ).joinToString("&") { (key, value) -> "$key=${encodeQueryValue(value)}" }
        return URI(Scheme, null, payload.host, payload.port, Path, query, null).toASCIIString()
    }

    fun decode(value: String): Result<DirectPairingPayload> = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme == Scheme) { "Unsupported direct pairing scheme" }
        require(uri.path == Path) { "Invalid direct pairing path" }
        require(uri.userInfo == null && uri.fragment == null) { "Invalid direct pairing payload" }
        val host = requireNotNull(uri.host) { "Direct pairing host is missing" }
            .removePrefix("[")
            .removeSuffix("]")
        val entries = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .map { item ->
                val separator = item.indexOf('=')
                require(separator > 0) { "Invalid direct pairing query" }
                item.substring(0, separator) to decodeQueryValue(item.substring(separator + 1))
            }
        require(entries.map { it.first }.distinct().size == entries.size) {
            "Duplicate direct pairing field"
        }
        val values = entries.toMap()
        require(values.keys == setOf("token", "expires")) { "Invalid direct pairing fields" }
        DirectPairingPayload(
            host = host,
            port = uri.port,
            pairingToken = requireNotNull(values["token"]),
            expiresAtMillis = requireNotNull(values["expires"]).toLong()
        )
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeQueryValue(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
