package com.uacspoofer.mobile.engine.pow

import android.content.Context
import org.json.JSONObject
import java.io.File

object PowCoreConfig {
    const val SOCKS_PORT = 1819
    const val TUN_SOCKS_PORT = 1818
    const val CHAIN_SOCKS_PORT = 1820
    const val OUTER_AUTO = "auto"
    const val DISCOVERY_CACHE = "cache"
    const val DISCOVERY_FRESH = "fresh"

    val OUTER_LADDER = listOf("wireguard", "masque", "gool")

    fun identityPath(context: Context): File = File(context.filesDir, "uac-pow.toml")

    fun masqueCachePath(context: Context): File = File(context.filesDir, "uac-pow-masque-cache.json")

    fun lastconnPath(context: Context): File = File(context.filesDir, "uac-pow-lastconn.toml")

    fun goolLastconnPath(context: Context): File = File(context.filesDir, "uac-pow-gool-lastconn.toml")

    fun pathMemoryFiles(context: Context): List<File> = listOf(
        masqueCachePath(context),
        lastconnPath(context),
        goolLastconnPath(context),
    )

    fun expireStalePathMemory(context: Context, nowMs: Long = System.currentTimeMillis()): Int =
        pathMemoryFiles(context).count { PowQualityPolicy.expireFileIfStale(it, nowMs) }

    fun forgetPathMemory(context: Context): Int =
        pathMemoryFiles(context).count { file -> file.isFile && file.delete() }

    fun chainOuterJson(
        context: Context,
        protocol: String,
        settings: PowEngineSettings,
        discovery: String = DISCOVERY_CACHE,
        scanMode: String = "balanced",
    ): String = JSONObject().apply {
        put("config_path", identityPath(context).absolutePath)
        put("protocol", protocol)
        put("listen", "127.0.0.1:$CHAIN_SOCKS_PORT")
        put("scan_mode", scanMode)
        put("ip_scan", "v4")
        put("endpoint_cache_path", masqueCachePath(context).absolutePath)
        put("endpoint_discovery", discovery)
        put("masque_transport", "h3")
        put("obfuscation_profile", settings.obfuscationProfile)
        put("retry_obfuscation_profiles", true)
        put("tls_curve_preset", "chrome")
        put("wireguard_data_check", true)
        put("log_level", "info")
        put("perf_profile", "high")
        put("h2_fragmentation", settings.h2Fragmentation)
        put("gateway", false)
    }.toString()

    fun outerLabel(protocol: String): String = when (protocol) {
        "masque" -> "MASQUE"
        "wireguard" -> "WireGuard"
        "gool" -> "WoW"
        else -> protocol.uppercase()
    }

    fun outerBudgetMs(protocol: String, retune: Boolean = false): Long {
        if (retune) {
            return when (protocol) {
                "masque" -> 14_000L
                "wireguard" -> 11_000L
                else -> 16_000L
            }
        }
        return when (protocol) {
            "masque" -> 28_000L
            "wireguard" -> 20_000L
            else -> 32_000L
        }
    }

    fun candidates(settings: PowEngineSettings): List<String> {
        val mode = settings.outerTransport
        return when {
            mode.isEmpty() || mode == OUTER_AUTO -> OUTER_LADDER
            OUTER_LADDER.contains(mode) -> listOf(mode)
            else -> OUTER_LADDER
        }
    }
}
