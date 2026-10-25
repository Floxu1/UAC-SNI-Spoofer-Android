package com.uacspoofer.mobile.engine.pow

import android.content.Context
import org.json.JSONObject
import java.io.File

object PowCoreConfig {
    const val SOCKS_PORT = 1819
    const val TUN_SOCKS_PORT = 1818
    const val CHAIN_SOCKS_PORT = 1820
    // Psiphon tunnel 2 ("two tunnels at once"); 1821/1822 belong to the ghost handover.
    const val SIDECAR_SOCKS_PORT = 1823
    const val OUTER_AUTO = "auto"
    const val DISCOVERY_CACHE = "cache"
    const val DISCOVERY_FRESH = "fresh"
    const val SCAN_TURBO = "turbo"
    const val SCAN_BALANCED = "balanced"
    const val SCAN_THOROUGH = "thorough"

    /** MASQUE first: MCI advanced filtering drops UDP (WireGuard and classic WoW) before the handshake. */
    val OUTER_LADDER = listOf("masque", "gool", "wireguard")
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
        // Saved clean H2 peers are tried BEFORE any scan and each verify can
        // burn 6-8s. Leaving them after MASQUE/SIM death makes turbo retune
        // spend its whole budget on 400/timeout edges and never raise outer.
        cleanH2File(context),
    )

    fun expireStalePathMemory(context: Context, nowMs: Long = System.currentTimeMillis()): Int =
        pathMemoryFiles(context).count { PowQualityPolicy.expireFileIfStale(it, nowMs) }

    fun forgetPathMemory(context: Context): Int =
        pathMemoryFiles(context).count { file -> file.isFile && file.delete() }

    fun cleanH2File(context: Context): File = File(context.filesDir, "uac-pow-clean-h2.json")

    fun cleanH2Peers(context: Context): List<String> {
        val file = cleanH2File(context)
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrDefault("")
        val body = text.substringAfter("\"peers\"").substringAfter("[").substringBefore("]")
        return body.split(',')
            .map { it.trim().trim('"') }
            .filter { it.contains('.') || it.contains(':') }
    }

    fun chainOuterJson(
        context: Context,
        protocol: String,
        settings: PowEngineSettings,
        discovery: String = DISCOVERY_CACHE,
        scanMode: String = PowCoreConfig.SCAN_BALANCED,
        mci: Boolean = false,
    ): String = JSONObject().apply {
        put("config_path", identityPath(context).absolutePath)
        put("protocol", protocol)
        put("listen", "127.0.0.1:$CHAIN_SOCKS_PORT")
        put("scan_mode", scanMode)
        val plan = outerPlan(settings, mci)
        put("ip_scan", plan.ipFor(protocol))
        put("endpoint_cache_path", masqueCachePath(context).absolutePath)
        put(
            "endpoint_discovery",
            if (settings.linkRecipe == RECIPE_MASQUE_H2_SAVED) "clean" else discovery,
        )
        put("masque_transport", if (protocol == "masque") plan.masqueTransport else "h3")
        put("obfuscation_profile", obfuscationFor(settings, protocol, plan))
        put("retry_obfuscation_profiles", true)
        put("tls_curve_preset", "chrome")
        put("wireguard_data_check", true)
        put("log_level", "info")
        put("perf_profile", "high")
        put("h2_fragmentation", h2FragmentEffective(context, settings, protocol, plan))
        put("quic_tuned", settings.quicTuned)
        put("h2_large_windows", settings.h2LargeWindows)
        put("h2_socket_tuning", settings.h2SocketTuning)
        put("h2_speed_pick", settings.h2SpeedPick)
        put("api_camo", settings.keyFetch)
        put("gateway", false)
    }.toString()

    /** H2 fragment: On/Off are the user's; Auto = recipe default OR the adaptive DPI flag. MASQUE h2 only. */
    fun h2FragmentEffective(context: Context, settings: PowEngineSettings, protocol: String, plan: PowOuterPlan): Boolean {
        val h2 = protocol == "masque" && plan.masqueTransport == "h2"
        val adaptive = PowAdaptiveObfuscation.adaptiveOn(context)
        val effective = h2 && when (settings.h2FragmentMode) {
            PowEngineSettings.H2_FRAG_ON -> true
            PowEngineSettings.H2_FRAG_OFF -> false
            else -> plan.fragment || adaptive
        }
        if (protocol == "masque") PowLiveExit.updatePath { it.copy(h2Fragment = effective) }
        if (protocol == "masque") {
            com.uacspoofer.mobile.logging.AppLogRepository.info(
                com.uacspoofer.mobile.logging.LogSource.POW,
                "H2 fragment: mode=${settings.h2FragmentMode} effective=$effective" +
                    " (recipe=${plan.fragment} adaptive=$adaptive transport=${plan.masqueTransport})",
            )
        }
        return effective
    }

    /** MASQUE over HTTP/2 already hides inside TLS; the user may lighten the extra obfuscation. */
    fun obfuscationFor(settings: PowEngineSettings, protocol: String, plan: PowOuterPlan): String {
        val h2 = protocol == "masque" && plan.masqueTransport == "h2"
        val override = settings.h2Obfuscation.trim().lowercase()
        return if (h2 && override in PowEngineSettings.H2_OBFS_CHOICES && override != PowEngineSettings.H2_OBFS_UNCHANGED) {
            override
        } else {
            settings.obfuscationProfile
        }
    }

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
            SCAN_TURBO -> Triple(90_000L, 24_000L, 34_000L)
            SCAN_THOROUGH -> Triple(180_000L, 55_000L, 70_000L)
            else -> Triple(120_000L, 36_000L, 48_000L)
        }
        return when (protocol) {
            "masque" -> masque
            "wireguard" -> wireguard
            else -> gool
        }
    }

    fun candidates(settings: PowEngineSettings, mci: Boolean = false): List<String> {
        val mode = settings.outerTransport
        val ladder = if (mci) MCI_LADDER else OUTER_LADDER
        return when {
            mode.isEmpty() || mode == OUTER_AUTO -> ladder
            ladder.contains(mode) || OUTER_LADDER.contains(mode) -> listOf(mode)
            else -> ladder
        }
    }

    private val MCI_LADDER = listOf("wireguard", "gool", "masque")

    const val FIREWALL_AUTO = "auto"
    const val FIREWALL_IRANCELL = "irancell"
    const val FIREWALL_MCI = "mci"
    val FIREWALLS = setOf(FIREWALL_AUTO, FIREWALL_IRANCELL, FIREWALL_MCI)

    const val RECIPE_AUTO = "auto"
    const val RECIPE_WARP_V6 = "warp-v6"
    const val RECIPE_WARP_ANY = "warp-any"
    const val RECIPE_WOW_V6 = "wow-v6"
    const val RECIPE_WOW_ANY = "wow-any"
    const val RECIPE_MASQUE_H2 = "masque-h2"
    const val RECIPE_MASQUE_H3 = "masque-h3"
    const val RECIPE_MASQUE_H2_SPLIT = "masque-h2-split"
    const val RECIPE_MASQUE_H2_SAVED = "masque-h2-saved"
    val RECIPES = setOf(
        RECIPE_AUTO,
        RECIPE_WARP_V6,
        RECIPE_WARP_ANY,
        RECIPE_WOW_V6,
        RECIPE_WOW_ANY,
        RECIPE_MASQUE_H2,
        RECIPE_MASQUE_H3,
        RECIPE_MASQUE_H2_SPLIT,
        RECIPE_MASQUE_H2_SAVED,
    )

    const val KEY_AUTO = "auto"
    const val KEY_ECH = "ech"
    const val KEY_FRAGMENT = "fragment"
    const val KEY_EXTERNAL = "external"
    /** OOB desync only (Android: nottl first; no relay / ECH / split). */
    const val KEY_OOB = "oob"
    val KEY_FETCHES = setOf(KEY_AUTO, KEY_OOB, KEY_ECH, KEY_FRAGMENT, KEY_EXTERNAL)

    fun keyProtocols(recipe: String): List<String> = when (recipe) {
        RECIPE_WOW_V6, RECIPE_WOW_ANY -> listOf("wireguard", "gool")
        RECIPE_MASQUE_H2, RECIPE_MASQUE_H3, RECIPE_MASQUE_H2_SPLIT, RECIPE_MASQUE_H2_SAVED -> listOf("masque")
        RECIPE_WARP_V6, RECIPE_WARP_ANY -> listOf("wireguard")
        else -> listOf("wireguard", "gool", "masque")
    }

    fun keyRequestJson(context: Context, settings: PowEngineSettings, protocol: String): String {
        val json = JSONObject(
            chainOuterJson(
                context,
                protocol,
                settings,
                mci = usesMciFirewall(context, settings),
            ),
        )
        json.put("protocol", protocol)
        json.put("api_camo", settings.validated().keyFetch)
        return json.toString()
    }

    fun certRenewJson(context: Context, settings: PowEngineSettings): String {
        val json = JSONObject(keyRequestJson(context, settings, "masque"))
        json.put("renew_masque", true)
        return json.toString()
    }

    fun usesMciFirewall(context: Context, settings: PowEngineSettings): Boolean = when (settings.firewallProfile) {
        FIREWALL_MCI -> true
        FIREWALL_IRANCELL -> false
        else -> PowCarrier.carrierClass(context) != "irancell"
    }

    fun outerPlan(settings: PowEngineSettings, mci: Boolean): PowOuterPlan {
        val recipe = settings.validated().linkRecipe
        return when (recipe) {
            RECIPE_WARP_V6 -> PowOuterPlan(listOf("wireguard"), "v6", "h3", false, true, "WARP IPv6")
            RECIPE_WARP_ANY -> PowOuterPlan(listOf("wireguard"), "both", "h3", false, true, "WARP any IP")
            RECIPE_WOW_V6 -> PowOuterPlan(listOf("gool"), "v6", "h3", false, true, "WARP-in-WARP IPv6")
            RECIPE_WOW_ANY -> PowOuterPlan(listOf("gool"), "both", "h3", false, true, "WARP-in-WARP any IP")
            RECIPE_MASQUE_H2 -> PowOuterPlan(listOf("masque"), "both", "h2", false, true, "MASQUE HTTP/2")
            RECIPE_MASQUE_H3 -> PowOuterPlan(listOf("masque"), "v4", "h3", false, true, "MASQUE HTTP/3")
            RECIPE_MASQUE_H2_SPLIT -> PowOuterPlan(listOf("masque"), "v4", "h2", true, true, "MASQUE HTTP/2 split hello")
            RECIPE_MASQUE_H2_SAVED -> PowOuterPlan(listOf("masque"), "both", "h2", false, true, "MASQUE HTTP/2 saved IPs")
            else -> if (mci) {
                // Same dial that connected on an MCI modem with the Irancell
                // firewall selected: HTTP/2, full ClientHello, v4 and v6.
                PowOuterPlan(
                    listOf("masque"),
                    "both",
                    "h2",
                    false,
                    true,
                    "MASQUE HTTP/2",
                )
            } else {
                PowOuterPlan(OUTER_LADDER, "v4", "h3", false, false, "Irancell ladder")
            }
        }
    }
}

class PowOuterPlan(
    val protocols: List<String>,
    val ipMode: String,
    val masqueTransport: String,
    val fragment: Boolean,
    val fixedOrder: Boolean,
    val summary: String,
) {
    fun ipFor(protocol: String): String = when (ipMode) {
        "v6-warp" -> if (protocol == "wireguard" || protocol == "gool") "v6" else "v4"
        "v6", "v4", "both" -> ipMode
        else -> "v4"
    }
}
