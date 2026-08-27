package com.uacspoofer.mobile.profiles

import org.json.JSONObject

object ProfileNetworks {
    const val XHTTP = "xhttp"
    val SUPPORTED = setOf("ws", "tcp", "httpupgrade", "grpc", XHTTP)
    private val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")
    private val PACKET_ENCODINGS = setOf("xudp", "packet", "none")

    fun normalize(raw: String): String = when (val value = raw.trim().lowercase()) {
        "splithttp" -> XHTTP
        else -> value
    }

    fun isXhttp(raw: String): Boolean = normalize(raw) == XHTTP

    fun requireSupported(raw: String, label: String = "transport"): String {
        val network = normalize(raw)
        require(network in SUPPORTED) { "Unsupported $label: $raw" }
        return network
    }

    fun normalizeMode(raw: String): String {
        val value = raw.trim().lowercase().replace('_', '-')
        if (value.isBlank()) return ""
        require(value in XHTTP_MODES) { "Unsupported XHTTP mode: $raw" }
        return value
    }

    fun optionalMode(raw: String): String {
        val value = raw.trim().lowercase().replace('_', '-')
        return if (value in XHTTP_MODES) value else ""
    }

    fun vmessMode(headerType: String, explicitMode: String): String {
        val explicit = optionalMode(explicitMode)
        if (explicit.isNotBlank()) return explicit
        return optionalMode(headerType)
    }

    fun normalizeExtra(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "{}") return ""
        val candidate = trimmed.removeSurrounding("\"").trim()
        require(candidate.startsWith("{") && candidate.endsWith("}")) {
            "XHTTP extra is not valid JSON"
        }
        if (candidate == "{}") return ""
        return compactJsonObject(candidate) ?: candidate
    }

    fun normalizePacketEncoding(raw: String): String {
        val value = raw.trim().lowercase()
        if (value.isBlank()) return ""
        require(value in PACKET_ENCODINGS) { "Unsupported packetEncoding: $raw" }
        return value
    }

    private fun compactJsonObject(raw: String): String? = try {
        val compact = JSONObject(raw).toString().trim()
        compact.takeIf { it.startsWith("{") && it.endsWith("}") && it != "{}" }
    } catch (_: Throwable) {
        null
    }
}
