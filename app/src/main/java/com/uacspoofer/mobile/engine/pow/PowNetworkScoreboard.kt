package com.uacspoofer.mobile.engine.pow

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Level B: Per-network scoreboard isolated to PoW.
 * Key = networkType + (operator/carrier) — stored inside PowEngineStore prefs,
 * never touches XRAY or Tor stores. Keeps outerProtocol + strategy winner per
 * physical network so reconnect on the same WiFi/cell is instant.
 */
internal object PowNetworkScoreboard {
    private const val KEY_PREFIX_OUTER = "pow_net_outer_"
    private const val KEY_PREFIX_STRATEGY = "pow_net_strat_"
    private const val KEY_PREFIX_STRAT_SHAPE = "pow_net_strat_shape_"
    private const val KEY_PREFIX_RTT = "pow_net_rtt_"

    fun networkKey(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "wifi:unknown"
            val net = cm.activeNetwork ?: return "other:unknown"
            val caps = cm.getNetworkCapabilities(net)
            val transport = when {
                caps == null -> "other"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                else -> "other"
            }
            val id = when (transport) {
                "wifi" -> runCatching {
                    val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                    wifi?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "unknown"
                }.getOrDefault("unknown")
                "cell" -> runCatching {
                    val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                    tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
                }.getOrDefault("unknown")
                else -> "unknown"
            }
            val safe = id.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(32).ifBlank { "unknown" }
            "$transport:$safe"
        }.getOrDefault("other:unknown")
    }

    fun saveOuter(prefs: android.content.SharedPreferences, netKey: String, protocol: String) {
        val clean = protocol.trim().lowercase().takeIf { it in PowCoreConfig.OUTER_LADDER } ?: return
        prefs.edit().putString(KEY_PREFIX_OUTER + netKey, clean).apply()
    }

    fun loadOuter(prefs: android.content.SharedPreferences, netKey: String): String? =
        prefs.getString(KEY_PREFIX_OUTER + netKey, null)?.takeIf { it in PowCoreConfig.OUTER_LADDER }

    fun saveStrategy(prefs: android.content.SharedPreferences, netKey: String, index: Int, shape: String, rttMs: Long = 0L) {
        prefs.edit()
            .putInt(KEY_PREFIX_STRATEGY + netKey, index)
            .putString(KEY_PREFIX_STRAT_SHAPE + netKey, shape)
            .apply()
        if (rttMs > 0) prefs.edit().putLong(KEY_PREFIX_RTT + netKey, rttMs).apply()
    }

    fun loadStrategy(prefs: android.content.SharedPreferences, netKey: String, shape: String, ladderSize: Int): Int? {
        if (prefs.getString(KEY_PREFIX_STRAT_SHAPE + netKey, null) != shape) return null
        if (!prefs.contains(KEY_PREFIX_STRATEGY + netKey)) return null
        return prefs.getInt(KEY_PREFIX_STRATEGY + netKey, 0).coerceIn(0, (ladderSize - 1).coerceAtLeast(0))
    }

    fun bestOuterForScoreboard(prefs: android.content.SharedPreferences, fallback: String?): String? {
        val key = runCatching { networkKey(prefs as Context) }.getOrNull()
        return key?.let { loadOuter(prefs, it) } ?: fallback
    }
}
