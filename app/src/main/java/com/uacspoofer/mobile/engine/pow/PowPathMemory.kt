package com.uacspoofer.mobile.engine.pow

import android.content.Context

internal data class PowLearnedPathSnapshot(
    val currentNetworkKey: String,
    val globalOuter: String?,
    val networks: List<PowScoreboardRow>,
    val masqueLastPeer: String?,
    val wowLastPeer: String?,
    val masqueCachedGateways: List<String>,
) {
    val isEmpty: Boolean
        get() = globalOuter.isNullOrBlank() &&
            networks.isEmpty() &&
            masqueLastPeer.isNullOrBlank() &&
            wowLastPeer.isNullOrBlank() &&
            masqueCachedGateways.isEmpty()

    fun summary(): String {
        val outer = globalOuter?.let(PowCoreConfig::outerLabel)
        val peer = masqueLastPeer ?: wowLastPeer
        return when {
            outer != null && peer != null -> "$outer · $peer"
            outer != null -> outer
            peer != null -> peer
            networks.isNotEmpty() -> networks.first().outer?.let(PowCoreConfig::outerLabel) ?: "—"
            else -> ""
        }
    }

    companion object {
        val EMPTY = PowLearnedPathSnapshot(
            currentNetworkKey = "other:unknown",
            globalOuter = null,
            networks = emptyList(),
            masqueLastPeer = null,
            wowLastPeer = null,
            masqueCachedGateways = emptyList(),
        )
    }
}

internal object PowPathMemory {
    fun snapshot(context: Context, prefs: android.content.SharedPreferences): PowLearnedPathSnapshot {
        return runCatching {
            val app = context.applicationContext
            PowLearnedPathSnapshot(
                currentNetworkKey = PowNetworkScoreboard.networkKind(app),
                globalOuter = prefs.getString("outer_protocol", null)
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it in PowCoreConfig.OUTER_LADDER },
                networks = PowNetworkScoreboard.rows(prefs),
                masqueLastPeer = parseTomlPeer(readText(PowCoreConfig.lastconnPath(app))),
                wowLastPeer = parseTomlPeer(readText(PowCoreConfig.goolLastconnPath(app))),
                masqueCachedGateways = parseMasqueGateways(readText(PowCoreConfig.masqueCachePath(app))),
            )
        }.getOrElse { PowLearnedPathSnapshot.EMPTY }
    }

    fun forget(context: Context, prefs: android.content.SharedPreferences): Int {
        val scoreboard = PowNetworkScoreboard.clearAll(prefs)
        val editor = prefs.edit()
            .remove("outer_protocol")
            .remove("psiphon_winning_strategy_chained")
            .remove("psiphon_winning_strategy_chained_shape")
        editor.apply()
        val files = PowCoreConfig.forgetPathMemory(context)
        return scoreboard + files
    }

    fun networkLabel(networkKey: String): String {
        val parts = networkKey.split(':', limit = 2)
        val kind = when (parts.getOrNull(0)) {
            "wifi" -> "Wi-Fi"
            "cell" -> "Cell"
            "eth" -> "Ethernet"
            else -> "Net"
        }
        val name = parts.getOrNull(1)?.replace('_', ' ')?.trim().orEmpty()
        return if (name.isBlank() || name == "unknown") kind else "$kind · $name"
    }

    fun parseTomlPeer(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val line = text.lineSequence().firstOrNull { it.trim().startsWith("peer") } ?: return null
        val value = line.substringAfter('=', missingDelimiterValue = "")
            .trim()
            .trim('"', '\'')
        return value.takeIf { it.contains(':') && it.length in 3..80 }
    }

    fun parseMasqueGateways(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return Regex("""\{[^{}]*\}""").findAll(text).mapNotNull { match ->
            val ip = Regex(""""ip"\s*:\s*"([^"]+)"""").find(match.value)?.groupValues?.getOrNull(1)?.trim()
            val port = Regex(""""port"\s*:\s*(\d+)""").find(match.value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (!ip.isNullOrBlank() && port != null && port in 1..65_535) "$ip:$port" else null
        }.toList()
    }

    private fun readText(file: java.io.File): String? =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
}
