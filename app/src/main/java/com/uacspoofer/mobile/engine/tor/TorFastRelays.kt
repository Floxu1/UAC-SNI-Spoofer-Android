package com.uacspoofer.mobile.engine.tor

import java.util.Base64

/** Picks the highest-bandwidth Fast+Stable relays from a `GETINFO ns/all` body. */
internal object TorFastRelays {
    const val MIDDLE_COUNT = 60
    const val MIN_USABLE = 10
    const val REFRESH_MS = 24L * 60 * 60 * 1000

    data class Relay(val fingerprint: String, val bandwidth: Long, val flags: Set<String>)

    fun parse(body: String): List<Relay> {
        val out = ArrayList<Relay>()
        var fp: String? = null
        var flags: Set<String> = emptySet()
        for (raw in body.lineSequence()) {
            val line = raw.trim().removePrefix("250+ns/all=").removePrefix("250-ns/all=")
            when {
                line.startsWith("r ") -> {
                    fp = line.split(' ').getOrNull(2)?.let(::idToHex)
                    flags = emptySet()
                }
                line.startsWith("s ") -> flags = line.substring(2).split(' ').toSet()
                line.startsWith("w ") -> {
                    val bw = Regex("""Bandwidth=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    val f = fp
                    if (f != null && bw != null) out += Relay(f, bw, flags)
                    fp = null
                }
            }
        }
        return out
    }

    fun pickMiddles(relays: List<Relay>, count: Int = MIDDLE_COUNT): List<String> =
        relays.asSequence()
            .filter { "Fast" in it.flags && "Stable" in it.flags && "Running" in it.flags && "Valid" in it.flags }
            .filterNot { "BadExit" in it.flags }
            .sortedByDescending { it.bandwidth }
            .take(count)
            .map { it.fingerprint }
            .toList()

    fun middleNodesConf(fps: List<String>): String =
        if (fps.size < MIN_USABLE) "MiddleNodes=" else "MiddleNodes=" + fps.joinToString(",") { "$$it" }

    private fun idToHex(b64: String): String? = runCatching {
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        Base64.getDecoder().decode(padded).joinToString("") { "%02X".format(it) }
    }.getOrNull()?.takeIf { it.length == 40 }
}