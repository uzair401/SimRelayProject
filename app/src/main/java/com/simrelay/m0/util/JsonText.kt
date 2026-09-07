package com.simrelay.m0.util

object JsonText {
    fun string(value: String?): String = value?.let { "\"${escape(it)}\"" } ?: "null"

    fun array(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")

    fun obj(vararg values: Pair<String, String>): String = values.joinToString(
        prefix = "{\n",
        postfix = "\n}",
        separator = ",\n"
    ) { (key, value) -> "  ${string(key)}: $value" }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u${character.code.toString(16).padStart(4, '0')}")
                } else {
                    append(character)
                }
            }
        }
    }
}
