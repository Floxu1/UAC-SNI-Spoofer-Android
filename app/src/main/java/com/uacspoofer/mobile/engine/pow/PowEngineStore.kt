package com.uacspoofer.mobile.engine.pow

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal data class PowLastConnect(
    val protocol: String = "",
    val transport: String = "",
    val ip: String = "",
    val peer: String = "",
    val throughPsiphon: Boolean = true,
) {
    fun describe(): String {
        if (protocol.isBlank()) return ""
        if (protocol == "psiphon") return "Psiphon"
        val name = when (protocol) {
            "wireguard" -> "WireGuard"
            "gool" -> "WARP-in-WARP"
            "masque" -> "MASQUE"
            else -> protocol
        }
        val transportLabel = when (transport) {
            "h2" -> "HTTP/2"
            "h3" -> "HTTP/3"
            else -> ""
        }
        val host = peer.substringBefore('%').substringBefore(']')
        val ipLabel = when {
            ip == "v6" || peer.startsWith("[") || host.count { it == ':' } > 1 -> "IPv6"
            ip == "v4" || host.count { it == '.' } == 3 -> "IPv4"
            else -> ""
        }
        val head = listOf(name, transportLabel, ipLabel, peer).filter { it.isNotBlank() }.joinToString(" · ")
        return head + if (throughPsiphon) " · Psiphon" else " · WARP"
    }
}

class PowEngineStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    init {
        if (!prefs.getBoolean(KEY_H2_QUALITY_MIGRATION, false)) {
            prefs.edit()
                .putBoolean(KEY_H2_FRAGMENT, false)
                .putBoolean(KEY_H2_QUALITY_MIGRATION, true)
                .apply()
        }
        if (!prefs.contains(KEY_LINK_RECIPE)) {
            val recipe = when (prefs.getString(KEY_OUTER, PowCoreConfig.OUTER_AUTO)) {
                "wireguard" -> PowCoreConfig.RECIPE_WARP_ANY
                "gool" -> PowCoreConfig.RECIPE_WOW_ANY
                "masque" -> PowCoreConfig.RECIPE_MASQUE_H3
                else -> PowCoreConfig.RECIPE_AUTO
            }
            prefs.edit().putString(KEY_LINK_RECIPE, recipe).apply()
        }
        if (!prefs.getBoolean(KEY_CONNECT_FIRST_MIGRATION, false)) {
            // Previous connects recorded last-good WARP/WoW after MASQUE and
            // WireGuard were killed mid-scan. Auto then always started there.
            PowNetworkScoreboard.clearAll(prefs)
            prefs.edit()
                .remove(KEY_OUTER_PROTOCOL)
                .putBoolean(KEY_CONNECT_FIRST_MIGRATION, true)
                .apply()
            runCatching { PowCoreConfig.lastconnPath(appContext).takeIf { it.isFile }?.delete() }
            runCatching { PowCoreConfig.goolLastconnPath(appContext).takeIf { it.isFile }?.delete() }
        }
    }

    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<PowEngineSettings> = mutableSettings.asStateFlow()
    private val mutableLastConnect = MutableStateFlow(readLastConnect())
    internal val lastConnect: StateFlow<PowLastConnect> = mutableLastConnect.asStateFlow()

    fun snapshot(): PowEngineSettings = mutableSettings.value

    fun save(settings: PowEngineSettings): PowEngineSettings {
        val validated = settings.validated()
        prefs.edit()
            .putString(KEY_EXIT_COUNTRY, validated.exitCountryCode)
            .putString(KEY_OUTER, validated.outerTransport)
            .putString(KEY_OBFUSCATION, validated.obfuscationProfile)
            .putBoolean(KEY_H2_FRAGMENT, validated.h2Fragmentation)
            .putString(KEY_SCAN_MODE, validated.scanMode)
            .putBoolean(KEY_OPTIMIZED_MODE, validated.optimizedMode)
            .putString(KEY_FIREWALL, validated.firewallProfile)
            .putString(KEY_LINK_RECIPE, validated.linkRecipe)
            .putBoolean(KEY_CHAIN_PSIPHON, validated.chainPsiphon)
            .putString(KEY_FETCH, validated.keyFetch)
            .putBoolean(KEY_PSIPHON_POOL, validated.psiphonTunnelPool)
            .putBoolean(KEY_QUIC_TUNED, validated.quicTuned)
            .apply()
        mutableSettings.value = validated
        return validated
    }

    // Level B: per-network scoreboard — try netKey first, fallback to global.
    fun rememberedOuterProtocol(): String? {
        val netKey = PowNetworkScoreboard.networkKey(appContext)
        PowNetworkScoreboard.loadOuter(prefs, netKey)?.let { return it }
        return prefs.getString(KEY_OUTER_PROTOCOL, null)?.trim()?.lowercase()?.takeIf { it in PowCoreConfig.OUTER_LADDER }
    }

    fun saveOuterProtocol(protocol: String) {
        val clean = protocol.trim().lowercase()
        if (clean !in PowCoreConfig.OUTER_LADDER) return
        prefs.edit().putString(KEY_OUTER_PROTOCOL, clean).apply()
        PowNetworkScoreboard.saveOuter(prefs, PowNetworkScoreboard.networkKey(appContext), clean)
    }

    fun rememberedStrategyIndex(shape: String, ladderSize: Int): Int {
        val netKey = PowNetworkScoreboard.networkKey(appContext)
        PowNetworkScoreboard.loadStrategy(prefs, netKey, shape, ladderSize)?.let { return it }
        val storedShape = prefs.getString(KEY_STRATEGY_SHAPE, null)
        if (storedShape != shape) {
            prefs.edit().remove(KEY_STRATEGY_INDEX).putString(KEY_STRATEGY_SHAPE, shape).apply()
            return 0
        }
        return prefs.getInt(KEY_STRATEGY_INDEX, 0).coerceIn(0, (ladderSize - 1).coerceAtLeast(0))
    }

    fun saveStrategyWinner(index: Int, shape: String) {
        prefs.edit().putInt(KEY_STRATEGY_INDEX, index).putString(KEY_STRATEGY_SHAPE, shape).apply()
        PowNetworkScoreboard.saveStrategy(prefs, PowNetworkScoreboard.networkKey(appContext), index, shape)
    }

    fun saveStrategyWinnerWithRtt(index: Int, shape: String, rttMs: Long) {
        saveStrategyWinner(index, shape)
        if (rttMs > 0) PowNetworkScoreboard.saveStrategy(prefs, PowNetworkScoreboard.networkKey(appContext), index, shape, rttMs)
    }

    fun saveStrategyRtt(strategy: String, rttMs: Long) {
        PowNetworkScoreboard.saveStrategyRtt(prefs, PowNetworkScoreboard.networkKey(appContext), strategy, rttMs)
    }

    fun strategyRtts(strategies: List<String>): Map<String, Long> =
        PowNetworkScoreboard.loadStrategyRtts(prefs, PowNetworkScoreboard.networkKey(appContext), strategies)

    fun rememberedRegion(): String? =
        PowNetworkScoreboard.loadRegion(prefs, PowNetworkScoreboard.networkKey(appContext))

    fun saveRegion(region: String) {
        PowNetworkScoreboard.saveRegion(prefs, PowNetworkScoreboard.networkKey(appContext), region)
    }

    fun availableRegions(): List<String> =
        prefs.getString(KEY_AVAILABLE_REGIONS, "")
            .orEmpty()
            .split(',')
            .map { it.trim().uppercase(Locale.US) }
            .filter(PowRegions::isCode)

    fun rememberAvailableRegions(codes: List<String>) {
        val clean = codes.map { it.trim().uppercase(Locale.US) }.filter(PowRegions::isCode).distinct().sorted()
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_AVAILABLE_REGIONS, clean.joinToString(",")).apply()
    }

    internal fun saveLastConnect(protocol: String, transport: String, ip: String, throughPsiphon: Boolean) {
        val clean = protocol.trim().lowercase()
        if (clean.isEmpty()) return
        val paths = learnedPaths()
        val peer = when (clean) {
            "gool" -> paths.wowLastPeer.orEmpty()
            "psiphon" -> ""
            else -> paths.masqueLastPeer.orEmpty().ifBlank { paths.wowLastPeer.orEmpty() }
        }
        prefs.edit()
            .putString(KEY_LAST_PROTOCOL, clean)
            .putString(KEY_LAST_TRANSPORT, transport.trim().lowercase())
            .putString(KEY_LAST_IP, ip.trim().lowercase())
            .putBoolean(KEY_LAST_PSIPHON, throughPsiphon)
            .putString(KEY_LAST_PEER, peer)
            .apply()
        mutableLastConnect.value = PowLastConnect(clean, transport.trim().lowercase(), ip.trim().lowercase(), peer, throughPsiphon)
    }

    internal fun learnedPaths(): PowLearnedPathSnapshot = PowPathMemory.snapshot(appContext, prefs)

    internal fun forgetLearnedPaths(): Int = PowPathMemory.forget(appContext, prefs)

    private fun read(): PowEngineSettings = PowEngineSettings(
        exitCountryCode = prefs.getString(KEY_EXIT_COUNTRY, "").orEmpty(),
        outerTransport = prefs.getString(KEY_OUTER, PowCoreConfig.OUTER_AUTO).orEmpty(),
        obfuscationProfile = prefs.getString(KEY_OBFUSCATION, "balanced").orEmpty(),
        h2Fragmentation = prefs.getBoolean(KEY_H2_FRAGMENT, false),
        scanMode = prefs.getString(KEY_SCAN_MODE, PowCoreConfig.SCAN_BALANCED).orEmpty(),
        optimizedMode = prefs.getBoolean(KEY_OPTIMIZED_MODE, true),
        firewallProfile = prefs.getString(KEY_FIREWALL, PowCoreConfig.FIREWALL_AUTO).orEmpty(),
        linkRecipe = prefs.getString(KEY_LINK_RECIPE, PowCoreConfig.RECIPE_AUTO).orEmpty(),
        chainPsiphon = prefs.getBoolean(KEY_CHAIN_PSIPHON, true),
        keyFetch = prefs.getString(KEY_FETCH, PowCoreConfig.KEY_AUTO).orEmpty(),
        psiphonTunnelPool = prefs.getBoolean(KEY_PSIPHON_POOL, false),
        quicTuned = prefs.getBoolean(KEY_QUIC_TUNED, false),
    ).validated()

    private fun readLastConnect(): PowLastConnect {
        val protocol = prefs.getString(KEY_LAST_PROTOCOL, null)?.trim()?.lowercase().orEmpty()
        if (protocol.isNotEmpty()) {
            return PowLastConnect(
                protocol = protocol,
                transport = prefs.getString(KEY_LAST_TRANSPORT, "").orEmpty(),
                ip = prefs.getString(KEY_LAST_IP, "").orEmpty(),
                peer = prefs.getString(KEY_LAST_PEER, "").orEmpty(),
                throughPsiphon = prefs.getBoolean(KEY_LAST_PSIPHON, true),
            )
        }
        val outer = prefs.getString(KEY_OUTER_PROTOCOL, null)?.trim()?.lowercase().orEmpty()
        if (outer !in PowCoreConfig.OUTER_LADDER) return PowLastConnect()
        val paths = learnedPaths()
        val peer = if (outer == "gool") paths.wowLastPeer.orEmpty() else paths.masqueLastPeer.orEmpty()
        return PowLastConnect(protocol = outer, peer = peer, throughPsiphon = true)
    }

    companion object {
        private const val PREFS = "uac_pow_engine_v1"
        private const val KEY_EXIT_COUNTRY = "exit_country"
        private const val KEY_OUTER = "outer_transport"
        private const val KEY_OBFUSCATION = "obfuscation_profile"
        private const val KEY_H2_FRAGMENT = "h2_fragmentation"
        private const val KEY_SCAN_MODE = "scan_mode"
        private const val KEY_OPTIMIZED_MODE = "optimized_mode"
        private const val KEY_FIREWALL = "firewall_profile"
        private const val KEY_LINK_RECIPE = "link_recipe"
        private const val KEY_CHAIN_PSIPHON = "chain_psiphon"
        private const val KEY_FETCH = "key_fetch"
        private const val KEY_PSIPHON_POOL = "psiphon_tunnel_pool"
        private const val KEY_QUIC_TUNED = "quic_tuned"
        private const val KEY_LAST_PROTOCOL = "last_connect_protocol"
        private const val KEY_LAST_TRANSPORT = "last_connect_transport"
        private const val KEY_LAST_IP = "last_connect_ip"
        private const val KEY_LAST_PSIPHON = "last_connect_psiphon"
        private const val KEY_LAST_PEER = "last_connect_peer"
        private const val KEY_H2_QUALITY_MIGRATION = "h2_fragment_off_for_quality"
        private const val KEY_CONNECT_FIRST_MIGRATION = "connect_first_outer_v1"
        private const val KEY_OUTER_PROTOCOL = "outer_protocol"
        private const val KEY_STRATEGY_INDEX = "psiphon_winning_strategy_chained"
        private const val KEY_STRATEGY_SHAPE = "psiphon_winning_strategy_chained_shape"
        private const val KEY_AVAILABLE_REGIONS = "psiphon_available_regions"

        @Volatile private var instance: PowEngineStore? = null

        fun get(context: Context): PowEngineStore = instance ?: synchronized(this) {
            instance ?: PowEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
