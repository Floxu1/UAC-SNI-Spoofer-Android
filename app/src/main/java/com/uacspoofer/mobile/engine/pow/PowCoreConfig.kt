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
    const val SCAN_TURBO = "turbo"
    const val SCAN_BALANCED = "balanced"
    const val SCAN_THOROUGH = "thorough"

    val OUTER_LADDER = listOf("wireguard", "masque", "gool")
    val SCAN_MODES = listOf(SCAN_TURBO, SCAN_BALANCED, SCAN_THOROUGH)

    fun normalizeScanMode(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when (value) {
            "fast", SCAN_TURBO -> SCAN_TURBO
            "deep", SCAN_THOROUGH -> SCAN_THOROUGH
            SCAN_BALANCED -> SCAN_BALANCED
            else -> SCAN_BALANCED
        }
    }

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
        scanMode: String = PowCoreConfig.SCAN_BALANCED,
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

    fun outerBudgetMs(protocol: String, retune: Boolean = false, scanMode: String = SCAN_BALANCED): Long {
        if (retune) {
            return when (protocol) {
                "masque" -> 14_000L
                "wireguard" -> 11_000L
                else -> 16_000L
            }
        }
        val scan = normalizeScanMode(scanMode)
        val (masque, wireguard, gool) = when (scan) {
            SCAN_TURBO -> Triple(32_000L, 24_000L, 34_000L)
            SCAN_THOROUGH -> Triple(75_000L, 55_000L, 70_000L)
            else -> Triple(45_000L, 36_000L, 48_000L)
        }
        return when (protocol) {
            "masque" -> masque
            "wireguard" -> wireguard
            else -> gool
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
