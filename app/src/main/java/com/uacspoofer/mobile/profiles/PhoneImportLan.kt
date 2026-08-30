package com.uacspoofer.mobile.profiles

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object PhoneImportLan {
    fun parseFormConfigs(body: String): String {
        val value = body.split('&')
            .firstOrNull { it.startsWith("configs=") }
            ?.substringAfter('=')
            .orEmpty()
            .replace('+', ' ')
        if (value.isBlank()) return ""
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name()).trim()
        }.getOrDefault(value.trim())
    }

    fun pickLanIpv4(
        interfaces: Sequence<Pair<String, String>> = liveAddresses(),
    ): String? {
        val usable = interfaces
            .map { (name, ip) -> name.lowercase() to ip }
            .filter { (_, ip) -> isReachableLanIpv4(ip) }
            .filter { (name, _) -> !isTunnelInterface(name) }
            .toList()
        return usable.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("wlp") }?.second
            ?: usable.firstOrNull { (name, _) -> name.startsWith("eth") || name.startsWith("en") }?.second
            ?: usable.firstOrNull { (name, _) -> name.startsWith("ap") || name.contains("softap") }?.second
            ?: usable.firstOrNull()?.second
    }

    fun isReachableLanIpv4(ip: String): Boolean {
        if (ip.isBlank() || ip.startsWith("127.") || ip == "0.0.0.0") return false
        if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) return false
        if (ip.startsWith("100.64.")) return false
        return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            ip.matches(Regex("""172\.(1[6-9]|2\d|3[0-1])\..+"""))
    }

    private fun isTunnelInterface(name: String): Boolean =
        name.startsWith("tun") ||
            name.startsWith("tap") ||
            name.startsWith("dummy") ||
            name.startsWith("rmnet") ||
            name.startsWith("ccmni") ||
            name.contains("ipsec") ||
            name.contains("wireguard")

    private fun liveAddresses(): Sequence<Pair<String, String>> =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { addr ->
                        val ip = addr.hostAddress ?: return@mapNotNull null
                        nif.name to ip
                    }
            }
}
