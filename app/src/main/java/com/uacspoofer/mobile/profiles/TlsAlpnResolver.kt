package com.uacspoofer.mobile.profiles

object TlsAlpnResolver {
    private val slashPairPattern = Regex("(?i)^(h2)/(http1\\.1|http/1\\.1)$")

    fun parseValues(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        slashPairPattern.matchEntire(trimmed)?.let {
            return listOf("h2", "http/1.1")
        }
        return trimmed.split(',')
            .map { normalizeToken(it.trim()) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun canonicalString(raw: String, network: String): String {
        val parsed = parseValues(raw)
        if (parsed.isEmpty()) {
            return if (network.equals("grpc", ignoreCase = true)) "h2" else "http/1.1"
        }
        return parsed.joinToString(",")
    }

    fun resolveForTransport(network: String, rawAlpn: String): List<String> {
        val requested = parseValues(rawAlpn)
        return when (network.lowercase()) {
            "grpc" -> buildList {
                if (requested.any { it.equals("h2", ignoreCase = true) }) add("h2")
                requested.filterNot { it.equals("h2", ignoreCase = true) }.forEach { add(it) }
                if (isEmpty()) add("h2")
            }
            "ws", "httpupgrade" -> buildList {
                add("http/1.1")
                requested.filterNot { isHttp11(it) }.forEach { add(it) }
            }
            else -> requested.ifEmpty { listOf("http/1.1") }
        }
    }

    private fun isHttp11(value: String): Boolean =
        value.equals("http/1.1", ignoreCase = true) || value.equals("http1.1", ignoreCase = true)

    private fun normalizeToken(token: String): String = when (token.lowercase()) {
        "http1.1" -> "http/1.1"
        else -> token
    }
}
