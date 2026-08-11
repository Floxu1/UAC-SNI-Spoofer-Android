package com.uacspoofer.mobile.vpn

internal object TunRouteParser {
    fun parse(value: String): Pair<String, Int> {
        val parts = value.trim().split('/', limit = 2)
        require(parts.size == 2) { "Route must use IPv4 CIDR notation" }
        val octets = parts[0].split('.').map { it.toIntOrNull() ?: -1 }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "Invalid IPv4 route address" }
        val prefix = parts[1].toIntOrNull()
        require(prefix != null && prefix in 0..32) { "Invalid IPv4 route prefix" }
        return parts[0] to prefix
    }
}
