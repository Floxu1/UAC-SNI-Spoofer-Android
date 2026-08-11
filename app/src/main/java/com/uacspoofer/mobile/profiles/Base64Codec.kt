package com.uacspoofer.mobile.profiles


internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(raw: String): ByteArray {
        val clean = raw.asSequence()
            .filterNot(Char::isWhitespace)
            .map { if (it == '-') '+' else if (it == '_') '/' else it }
            .takeWhile { it != '=' }
            .toList()
        require(clean.isNotEmpty()) { "Base64 payload is empty" }
        val output = ByteArray((clean.size * 6) / 8)
        var accumulator = 0
        var bits = 0
        var index = 0
        clean.forEach { char ->
            val value = ALPHABET.indexOf(char)
            require(value >= 0) { "Invalid Base64 character" }
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (index < output.size) output[index++] = (accumulator shr bits).toByte()
            }
        }
        return if (index == output.size) output else output.copyOf(index)
    }

    fun encode(bytes: ByteArray): String = buildString((bytes.size + 2) / 3 * 4) {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            append(ALPHABET[first ushr 2])
            append(ALPHABET[((first and 3) shl 4) or if (second >= 0) second ushr 4 else 0])
            append(if (second >= 0) ALPHABET[((second and 15) shl 2) or if (third >= 0) third ushr 6 else 0] else '=')
            append(if (third >= 0) ALPHABET[third and 63] else '=')
        }
    }
}
