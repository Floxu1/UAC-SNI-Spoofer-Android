package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.profiles.PhoneImportLan

object LanSharePolicy {
    const val PORT = 18_080
    const val MAX_STREAMS = 24

    fun acceptClient(hostAddress: String?): Boolean {
        val ip = hostAddress?.substringBefore('%')?.trim().orEmpty()
        if (ip.isBlank()) return false
        if (ip == "127.0.0.1" || ip.startsWith("127.")) return true
        return PhoneImportLan.isReachableLanIpv4(ip)
    }

    fun endpoint(ip: String?, port: Int = PORT): String? {
        val host = ip?.trim().orEmpty()
        if (host.isBlank() || port <= 0) return null
        return "$host:$port"
    }
}

data class LanShareEndpoint(
    val listening: Boolean = false,
    val address: String? = null,
    val port: Int = LanSharePolicy.PORT,
    val detail: String = "",
) {
    val label: String? = if (listening) LanSharePolicy.endpoint(address, port) else null
}
