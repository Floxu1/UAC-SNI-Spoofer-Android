package com.uacspoofer.mobile.engine.tor

import android.content.Context
import org.json.JSONObject

/** Per-network (operator + transport fingerprint) memory of each WebTunnel bridge. */
data class TorBridgeScore(
    val success: Int = 0,
    val fail: Int = 0,
    val consecutiveFail: Int = 0,
    val latencyMs: Int = 0,
    val lastSeen: Long = 0L,
    val lastFail: Long = 0L,
    /** Measured download speed through this bridge, KB/s (0 = unknown). */
    val throughputKbps: Int = 0,
) {
    fun coolingDown(now: Long): Boolean =
        consecutiveFail >= COOLDOWN_AFTER && now - lastFail < COOLDOWN_MS

    /** Higher is better. */
    fun rank(): Double {
        val latencyPenalty = if (latencyMs > 0) latencyMs / 1000.0 else 1.5
        val speedBonus = (throughputKbps / 100.0).coerceAtMost(5.0)
        return success * 3.0 - fail * 1.0 - consecutiveFail * 2.0 - latencyPenalty + speedBonus
    }

    companion object {
        const val COOLDOWN_AFTER = 2
        const val COOLDOWN_MS = 30 * 60_000L
    }
}

internal object TorBridgeRanking {
    /** Scored order: healthy by rank, then unknown (keep input order), cooled-down last. */
    fun order(
        bridges: List<WebTunnelBridge>,
        scores: Map<String, TorBridgeScore>,
        now: Long,
    ): List<WebTunnelBridge> {
        val indexed = bridges.withIndex()
        val cooling = indexed.filter { scores[key(it.value)]?.coolingDown(now) == true }
        val rest = indexed.filterNot { scores[key(it.value)]?.coolingDown(now) == true }
        val known = rest.filter { scores[key(it.value)]?.let { s -> s.success > 0 || s.latencyMs > 0 } == true }
            .sortedByDescending { scores.getValue(key(it.value)).rank() }
        val unknown = rest.filterNot { it in known }
        return (known + unknown + cooling).map { it.value }
    }

    fun key(bridge: WebTunnelBridge): String = bridge.url.lowercase()
}

class TorBridgeScoreStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun scores(networkKey: String): Map<String, TorBridgeScore> {
        val raw = prefs.getString(networkKey, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { k ->
                val o = json.getJSONObject(k)
                TorBridgeScore(
                    success = o.optInt("s"),
                    fail = o.optInt("f"),
                    consecutiveFail = o.optInt("cf"),
                    latencyMs = o.optInt("l"),
                    lastSeen = o.optLong("ls"),
                    lastFail = o.optLong("lf"),
                    throughputKbps = o.optInt("t"),
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun recordScan(networkKey: String, latencies: Map<WebTunnelBridge, Int>) =
        update(networkKey) { map, now ->
            latencies.forEach { (bridge, ms) ->
                val k = TorBridgeRanking.key(bridge)
                val old = map[k] ?: TorBridgeScore()
                map[k] = old.copy(latencyMs = ms, lastSeen = now)
            }
        }

    fun recordSuccess(networkKey: String, bridge: WebTunnelBridge) = update(networkKey) { map, now ->
        val k = TorBridgeRanking.key(bridge)
        val old = map[k] ?: TorBridgeScore()
        map[k] = old.copy(success = old.success + 1, consecutiveFail = 0, lastSeen = now)
    }

    fun recordThroughput(networkKey: String, bridge: WebTunnelBridge, kbps: Int) = update(networkKey) { map, now ->
        val k = TorBridgeRanking.key(bridge)
        val old = map[k] ?: TorBridgeScore()
        // Smooth so one bad sample does not bury a good bridge.
        val next = if (old.throughputKbps > 0) (old.throughputKbps + kbps) / 2 else kbps
        map[k] = old.copy(throughputKbps = next, lastSeen = now)
    }

    fun recordFailure(networkKey: String, bridges: List<WebTunnelBridge>) = update(networkKey) { map, now ->
        bridges.forEach { bridge ->
            val k = TorBridgeRanking.key(bridge)
            val old = map[k] ?: TorBridgeScore()
            map[k] = old.copy(fail = old.fail + 1, consecutiveFail = old.consecutiveFail + 1, lastFail = now)
        }
    }

    @Synchronized
    private fun update(networkKey: String, block: (MutableMap<String, TorBridgeScore>, Long) -> Unit) {
        if (networkKey.isBlank()) return
        val map = scores(networkKey).toMutableMap()
        block(map, System.currentTimeMillis())
        val trimmed = map.entries.sortedByDescending { maxOf(it.value.lastSeen, it.value.lastFail) }.take(MAX_ENTRIES)
        val json = JSONObject()
        trimmed.forEach { (k, s) ->
            json.put(
                k,
                JSONObject()
                    .put("s", s.success).put("f", s.fail).put("cf", s.consecutiveFail)
                    .put("l", s.latencyMs).put("ls", s.lastSeen).put("lf", s.lastFail)
                    .put("t", s.throughputKbps),
            )
        }
        prefs.edit().putString(networkKey, json.toString()).apply()
    }

    private companion object {
        const val PREFS = "tor_bridge_scores_v1"
        const val MAX_ENTRIES = 160
    }
}