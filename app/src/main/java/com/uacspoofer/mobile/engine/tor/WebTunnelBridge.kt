package com.uacspoofer.mobile.engine.tor

data class WebTunnelBridge(
    val raw: String,
    val address: String,
    val port: Int,
    val url: String,
) {
    val endpoint: String get() = "$address:$port"

    fun torrcLine(): String =
        "Bridge ${WebTunnelBridgeParser.ipv4PlaceholderLine(raw.removePrefix("Bridge ").trim())}"
}

object WebTunnelBridgeParser {
    fun parseAll(raw: String): List<WebTunnelBridge> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull(::parseLine)
            .toList()

    fun parseLine(raw: String): WebTunnelBridge? {
        val line = raw.trim().removePrefix("Bridge ").trim()
        if (line.isEmpty()) return null
        val parts = line.split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val transport = parts[0].lowercase()
        if (transport != "webtunnel") return null
        val endpoint = parts.getOrNull(1) ?: return null
        val hostPort = endpoint.substringBefore(' ')
        val host = hostPort.substringBeforeLast(':').trim().trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        val url = parts.drop(2)
            .firstOrNull { it.startsWith("url=", ignoreCase = true) }
            ?.substringAfter('=')
            .orEmpty()
        if (host.isBlank() || port !in 1..65_535 || url.isBlank()) return null
        return WebTunnelBridge(
            raw = line,
            address = host,
            port = port,
            url = url,
        )
    }

    /**
     * OnionHop dummy RFC 3849 IPv6 in the bridge IP field is not dialed, but Tor still
     * prefers it. On IPv4-only phones that makes the PT fail. Rewrite to TEST-NET-1.
     */
    fun ipv4PlaceholderLine(line: String): String {
        val match = ENDPOINT.matchEntire(line) ?: return line
        val host = match.groupValues[2].trim().trim('[', ']')
        if (!isDocumentationIpv6(host)) return line
        val port = match.groupValues[3]
        val rest = match.groupValues[4]
        return "webtunnel ${documentationIpv4(host + rest)}:$port $rest"
    }

    internal fun isDocumentationIpv6(host: String): Boolean =
        host.startsWith("2001:db8:", ignoreCase = true) ||
            host.equals("2001:db8::", ignoreCase = true) ||
            host.startsWith("2001:db8::", ignoreCase = true)

    internal fun documentationIpv4(seed: String): String {
        var hash = 0
        seed.lowercase().forEach { hash = hash * 31 + it.code }
        val octet = (hash.and(0x7fffffff) % 254) + 1
        return "192.0.2.$octet"
    }

    private val ENDPOINT = Regex(
        """^(webtunnel)\s+(\[[0-9a-fA-F:]+\]|[^:\s]+):(\d+)\s+(.*)$""",
        RegexOption.IGNORE_CASE,
    )
}
