package com.uacspoofer.mobile.engine.direct

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.vpn.NetworkFingerprint
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver
import org.json.JSONObject

/** Records the H2 route that worked on the current network. */
internal object DirectH2Profiles {
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1_000L
    private const val SAVED_PEER = "Saved clean HTTP/2 address "
    private val h2Keys = listOf(
        "h2_fragmentation", "h2_fingerprint", "tls_curve_preset",
        "h2_large_windows", "h2_socket_tuning", "perf_profile",
    )

    data class Profile(
        val source: String,
        val peer: String,
        val settings: JSONObject,
        val networkKey: String,
        val verifiedAt: Long,
    ) {
        fun applyTo(config: JSONObject) {
            config.put("forced_peer", peer)
            h2Keys.forEach { key -> settings.opt(key)?.let { config.put(key, it) } }
        }
    }

    fun rememberPow(context: Context, original: NetworkFingerprint, config: String, log: String) {
        runCatching {
            val current = NetworkFingerprintResolver(context).capture()
            if (!original.isSameUnderlyingNetwork(current) || original.key != current.key) return
            val options = JSONObject(config)
            if (options.optString("masque_transport") != "h2") return
            val logged = log.lineSequence().lastOrNull { it.startsWith(SAVED_PEER) }
                ?.removePrefix(SAVED_PEER)?.trim() ?: return
            val peer = ipv4H2Peer(logged) ?: return
            save(context, current, "pow", peer, options)
            AppLogRepository.debug(LogSource.POW, "Verified HTTP/2 profile recorded for Direct: peer=$peer")
        }
    }

    fun save(context: Context, network: NetworkFingerprint, source: String, peer: String, options: JSONObject) {
        if (network.networkHandle < 0) return
        val normalized = ipv4H2Peer(peer) ?: return
        val entry = JSONObject()
            .put("network", network.exactStorageKey())
            .put("at", System.currentTimeMillis())
            .put("peer", normalized)
            .put("settings", JSONObject().apply {
                h2Keys.forEach { key -> options.opt(key)?.let { put(key, it) } }
            })
        prefs(context).edit().putString(source, entry.toString()).apply()
    }

    fun find(context: Context, network: NetworkFingerprint?, excludedIp: String?): Profile? {
        if (network == null || network.networkHandle < 0) return null
        val preferences = prefs(context)
        for (source in listOf("direct", "pow")) {
            val entry = runCatching { JSONObject(preferences.getString(source, null) ?: "") }
                .getOrNull() ?: continue
            val verifiedAt = entry.optLong("at")
            val networkKey = entry.optString("network")
            val age = System.currentTimeMillis() - verifiedAt
            if (age !in 0..MAX_AGE_MS || networkKey != network.exactStorageKey()) continue
            val peer = ipv4H2Peer(entry.optString("peer")) ?: continue
            if (peer.substringBefore(':') == excludedIp) continue
            val settings = entry.optJSONObject("settings") ?: continue
            return Profile(source, peer, settings, networkKey, verifiedAt)
        }
        return null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("direct_verified_h2_profiles", Context.MODE_PRIVATE)
}

internal fun ipv4H2Peer(value: String): String? {
    val ip = value.substringBefore(':')
    val octets = ip.split('.')
    if (octets.size != 4 || octets.any { part ->
            part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } || part.toInt() > 255
        }
    ) return null
    // PoW can log the original UDP gateway address after connecting to its H2 port.
    val port = value.substringAfter(':', "443")
    if (port != "443" && port != "1701") return null
    return "${octets.joinToString(".") { it.toInt().toString() }}:443"
}
