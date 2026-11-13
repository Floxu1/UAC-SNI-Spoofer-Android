package com.uacspoofer.mobile.vpn

/**
 * Full-tunnel routes with LAN and loopback left on the physical network.
 * 0.0.0.0/0 would capture these; the list is every other public IPv4 block.
 */
internal object TunLanBypass {
    val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
    )

    /** Complement of 0.0.0.0/0 minus [PRIVATE_CIDRS]. */
    val PUBLIC_ROUTES: List<Pair<String, Int>> = listOf(
        "0.0.0.0" to 5,
        "8.0.0.0" to 7,
        "11.0.0.0" to 8,
        "12.0.0.0" to 6,
        "16.0.0.0" to 4,
        "32.0.0.0" to 3,
        "64.0.0.0" to 3,
        "96.0.0.0" to 4,
        "112.0.0.0" to 5,
        "120.0.0.0" to 6,
        "124.0.0.0" to 7,
        "126.0.0.0" to 8,
        "128.0.0.0" to 3,
        "160.0.0.0" to 5,
        "168.0.0.0" to 6,
        "172.0.0.0" to 12,
        "172.32.0.0" to 11,
        "172.64.0.0" to 10,
        "172.128.0.0" to 9,
        "173.0.0.0" to 8,
        "174.0.0.0" to 7,
        "176.0.0.0" to 4,
        "192.0.0.0" to 9,
        "192.128.0.0" to 11,
        "192.160.0.0" to 13,
        "192.169.0.0" to 16,
        "192.170.0.0" to 15,
        "192.172.0.0" to 14,
        "192.176.0.0" to 12,
        "192.192.0.0" to 10,
        "193.0.0.0" to 8,
        "194.0.0.0" to 7,
        "196.0.0.0" to 6,
        "200.0.0.0" to 5,
        "208.0.0.0" to 4,
        "224.0.0.0" to 3,
    )

    fun privateIpsJson(): String = PRIVATE_CIDRS.joinToString(",") { "\"$it\"" }

    fun routesFor(configured: String): List<Pair<String, Int>> {
        val (address, prefix) = TunRouteParser.parse(configured)
        if (address == "0.0.0.0" && prefix == 0) return PUBLIC_ROUTES
        if (entirelyPrivate(address, prefix)) return emptyList()
        return listOf(address to prefix)
    }

    fun covers(ip: String): Boolean = PUBLIC_ROUTES.any { (network, prefix) ->
        contains(network, prefix, ip)
    }

    private fun entirelyPrivate(address: String, prefix: Int): Boolean {
        val start = ipv4(address)
        val end = start or hostMask(prefix)
        return PRIVATE_CIDRS.any { cidr ->
            val (network, parent) = TunRouteParser.parse(cidr)
            parent <= prefix && contains(network, parent, start) && contains(network, parent, end)
        }
    }

    private fun contains(network: String, prefix: Int, ip: String): Boolean =
        contains(network, prefix, ipv4(ip))

    private fun contains(network: String, prefix: Int, ip: Long): Boolean {
        val mask = networkMask(prefix)
        return (ipv4(network) and mask) == (ip and mask)
    }

    private fun networkMask(prefix: Int): Long =
        if (prefix <= 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL

    private fun hostMask(prefix: Int): Long = networkMask(prefix).inv() and 0xffffffffL

    private fun ipv4(text: String): Long {
        val parts = text.split('.')
        require(parts.size == 4) { "Invalid IPv4 $text" }
        return parts.fold(0L) { acc, part ->
            val octet = part.toInt()
            require(octet in 0..255) { "Invalid IPv4 $text" }
            (acc shl 8) or octet.toLong()
        }
    }
}
