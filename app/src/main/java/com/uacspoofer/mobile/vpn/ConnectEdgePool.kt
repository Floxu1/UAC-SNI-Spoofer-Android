package com.uacspoofer.mobile.vpn

import android.content.Context
import com.uacspoofer.mobile.mci.MciEdge
import org.json.JSONArray
import org.json.JSONObject

data class AdaptiveConnectPlan(
    val candidates: List<AdaptiveCandidate>,
    val pool: ConnectPoolSelection,
)

data class ConnectPoolSelection(
    val edges: List<MciEdge>,
    val source: String,
) {
    companion object {
        const val SOURCE_DEFAULT = "default"
        const val SOURCE_NETWORK = "network"
        const val SOURCE_PREVIOUS = "previous"
        const val SOURCE_RESCUE = "rescue"
    }
}

internal fun resolveConnectPool(
    thisPool: List<MciEdge>?,
    lastPool: List<MciEdge>?,
    lastPoolKey: String?,
    thisKey: String,
): ConnectPoolSelection {
    val local = thisPool.orEmpty()
    if (local.isNotEmpty()) {
        return ConnectPoolSelection(local, ConnectPoolSelection.SOURCE_NETWORK)
    }
    val previous = lastPool.orEmpty()
    if (previous.isNotEmpty() && !lastPoolKey.isNullOrBlank() && lastPoolKey != thisKey) {
        return ConnectPoolSelection(previous, ConnectPoolSelection.SOURCE_PREVIOUS)
    }
    return ConnectPoolSelection(emptyList(), ConnectPoolSelection.SOURCE_DEFAULT)
}

internal fun applyConnectEdgePool(
    candidates: List<AdaptiveCandidate>,
    pool: List<MciEdge>,
): List<AdaptiveCandidate> {
    val edges = pool.distinctBy { canonicalEndpointKey(it.address, it.port) }
        .filter(::persistableConnectEdge)
    if (edges.isEmpty()) return candidates
    var index = 0
    return candidates.map { candidate ->
        if (
            candidate.id == AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID ||
            candidate.id == AdaptiveCandidatePlanner.CONNECT_LAST_GOOD_ID
        ) {
            candidate
        } else {
            val edge = edges[index % edges.size]
            index++
            candidate.copy(
                id = "cfpool-${edge.address.replace('.', '-')}-${edge.port}-${candidate.id}",
                label = "${candidate.label} • ${edge.address}:${edge.port}",
                edge = candidate.edge.copy(address = edge.address, port = edge.port),
            )
        }
    }
}

internal fun connectPoolScopeKey(operatorKey: String, profileId: String): String =
    "$operatorKey|${profileId.trim()}"

internal fun persistableConnectEdge(edge: MciEdge): Boolean {
    val address = edge.address.trim()
    if (address.isBlank() || edge.port !in 1..65_535) return false
    return !address.equals("127.0.0.1", ignoreCase = true) &&
        !address.equals("localhost", ignoreCase = true) &&
        address != "::1"
}

internal fun poolWithChampionFirst(pool: List<MciEdge>, champion: MciEdge?): List<MciEdge> {
    val usable = pool.filter(::persistableConnectEdge)
        .distinctBy { canonicalEndpointKey(it.address, it.port) }
    val lead = champion?.takeIf(::persistableConnectEdge) ?: return usable.take(CONNECT_POOL_MAX_EDGES)
    val leadKey = canonicalEndpointKey(lead.address, lead.port)
    return (listOf(lead) + usable.filter { canonicalEndpointKey(it.address, it.port) != leadKey })
        .take(CONNECT_POOL_MAX_EDGES)
}

internal const val CONNECT_POOL_MAX_EDGES = 10

class ConnectEdgePoolStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun operatorKey(network: NetworkFingerprint): String = network.learningKey()

    fun pool(network: NetworkFingerprint, profileId: String): List<MciEdge>? =
        load(connectPoolScopeKey(operatorKey(network), profileId))

    fun lastPool(profileId: String): List<MciEdge>? = lastPoolKey(profileId)?.let(::load)

    fun lastPoolKey(profileId: String): String? =
        prefs.getString(profileLastKey(profileId), null)?.takeIf(String::isNotBlank)

    fun champion(network: NetworkFingerprint, profileId: String): MciEdge? =
        loadChampion(connectPoolScopeKey(operatorKey(network), profileId))

    fun save(network: NetworkFingerprint, profileId: String, edges: List<MciEdge>) {
        val unique = edges.filter(::persistableConnectEdge)
            .distinctBy { canonicalEndpointKey(it.address, it.port) }
            .take(CONNECT_POOL_MAX_EDGES)
        if (unique.isEmpty()) return
        val scoped = connectPoolScopeKey(operatorKey(network), profileId)
        val array = JSONArray()
        unique.forEach { edge ->
            array.put(edgeJson(edge))
        }
        prefs.edit()
            .putString(
                poolKey(scoped),
                JSONObject().put("savedAt", System.currentTimeMillis()).put("edges", array).toString(),
            )
            .putString(profileLastKey(profileId), scoped)
            .apply()
    }

    fun saveChampion(network: NetworkFingerprint, profileId: String, edge: MciEdge) {
        if (!persistableConnectEdge(edge)) return
        val scoped = connectPoolScopeKey(operatorKey(network), profileId)
        prefs.edit()
            .putString(
                championKey(scoped),
                JSONObject()
                    .put("savedAt", System.currentTimeMillis())
                    .put("edge", edgeJson(edge))
                    .toString(),
            )
            .apply()
    }

    private fun loadChampion(scopeKey: String): MciEdge? {
        val raw = prefs.getString(championKey(scopeKey), null) ?: return null
        return runCatching {
            val snapshot = JSONObject(raw)
            val savedAt = snapshot.optLong("savedAt", 0L)
            if (savedAt <= 0L || System.currentTimeMillis() - savedAt > TTL_MS) return@runCatching null
            parseEdge(snapshot.optJSONObject("edge"))?.takeIf(::persistableConnectEdge)
        }.getOrNull()
    }

    private fun load(storageKey: String): List<MciEdge>? {
        val raw = prefs.getString(poolKey(storageKey), null) ?: return null
        return runCatching {
            val snapshot = JSONObject(raw)
            val savedAt = snapshot.optLong("savedAt", 0L)
            if (savedAt <= 0L || System.currentTimeMillis() - savedAt > TTL_MS) return@runCatching null
            val array = snapshot.optJSONArray("edges") ?: return@runCatching null
            buildList {
                for (index in 0 until array.length()) {
                    parseEdge(array.optJSONObject(index))?.let(::add)
                }
            }.filter(::persistableConnectEdge)
                .distinctBy { canonicalEndpointKey(it.address, it.port) }
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun parseEdge(item: JSONObject?): MciEdge? {
        if (item == null) return null
        val address = item.optString("address").trim()
        val port = item.optInt("port")
        if (address.isBlank() || port !in 1..65_535) return null
        return MciEdge(
            address = address,
            port = port,
            role = item.optString("role").ifBlank { "connect-pool" },
            finalmaskMaxSplit = item.optInt("split", 2).coerceIn(1, 10_000),
        )
    }

    private fun edgeJson(edge: MciEdge): JSONObject = JSONObject()
        .put("address", edge.address.trim())
        .put("port", edge.port)
        .put("role", edge.role)
        .put("split", edge.finalmaskMaxSplit)

    private fun poolKey(storageKey: String): String = "$KEY_POOL:$storageKey"

    private fun championKey(storageKey: String): String = "$KEY_CHAMPION:$storageKey"

    private fun profileLastKey(profileId: String): String = "$KEY_LAST_PROFILE:${profileId.trim()}"

    companion object {
        private const val PREFS = "uac_connect_edge_pool_v1"
        private const val KEY_POOL = "pool"
        private const val KEY_CHAMPION = "champion"
        private const val KEY_LAST_PROFILE = "lastOperatorProfile"
        private const val TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
