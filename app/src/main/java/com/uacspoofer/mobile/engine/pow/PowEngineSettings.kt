package com.uacspoofer.mobile.engine.pow

data class PowEngineSettings(
    val exitCountryCode: String = "",
    val outerTransport: String = PowCoreConfig.OUTER_AUTO,
    val obfuscationProfile: String = "balanced",
    val h2Fragmentation: Boolean = true,
    val scanMode: String = PowCoreConfig.SCAN_BALANCED,
    val optimizedMode: Boolean = true,
    val firewallProfile: String = PowCoreConfig.FIREWALL_AUTO,
    val linkRecipe: String = PowCoreConfig.RECIPE_MASQUE_H2,
    val chainPsiphon: Boolean = true,
    /** chain = Psiphon over WARP, warp = WARP only, psiphon = Psiphon direct (no WARP leg). */
    val exitMode: String = EXIT_CHAIN,
    val keyFetch: String = PowCoreConfig.KEY_AUTO,
    val psiphonTunnelPool: Boolean = false,
    val quicTuned: Boolean = false,
    // Advanced settings (تنظیمات پیشرفته)
    val ghostHandover: Boolean = true,
    val logcatMirror: Boolean = true,
    val h2LargeWindows: Boolean = true,
    val h2SocketTuning: Boolean = true,
    val h2SpeedPick: Boolean = true,
    val h2Obfuscation: String = H2_OBFS_UNCHANGED,
    val tunMtu: Int = TUN_MTU_DEFAULT,
    val h2FragmentMode: String = H2_FRAG_AUTO,
    /** When true, sockets call VpnService.protect (bypass tunnel). Off for emulator testing. */
    val socketProtectEnabled: Boolean = true,
) {
    fun validated(): PowEngineSettings = validatedRaw().let { it.copy(chainPsiphon = it.exitMode != EXIT_WARP) }

    private fun validatedRaw(): PowEngineSettings {
        val country = PowRegions.normalize(exitCountryCode)
        val transport = outerTransport.trim().lowercase().ifBlank { PowCoreConfig.OUTER_AUTO }
        val obfuscation = obfuscationProfile.trim().lowercase().ifBlank { "balanced" }
        val firewall = firewallProfile.trim().lowercase()
        val recipe = linkRecipe.trim().lowercase()
        val fetch = keyFetch.trim().lowercase()
        return copy(
            exitCountryCode = country,
            outerTransport = if (transport == PowCoreConfig.OUTER_AUTO || transport in PowCoreConfig.OUTER_LADDER) {
                transport
            } else {
                PowCoreConfig.OUTER_AUTO
            },
            obfuscationProfile = if (obfuscation in OBFUSCATION_PROFILES) obfuscation else "balanced",
            scanMode = PowCoreConfig.normalizeScanMode(scanMode),
            firewallProfile = if (firewall in PowCoreConfig.FIREWALLS) firewall else PowCoreConfig.FIREWALL_AUTO,
            linkRecipe = if (recipe in PowCoreConfig.RECIPES) recipe else PowCoreConfig.RECIPE_AUTO,
            keyFetch = if (fetch in PowCoreConfig.KEY_FETCHES) fetch else PowCoreConfig.KEY_AUTO,
            h2Obfuscation = h2Obfuscation.trim().lowercase().let { if (it in H2_OBFS_CHOICES) it else H2_OBFS_UNCHANGED },
            exitMode = exitMode.trim().lowercase().let { if (it in EXIT_MODES) it else EXIT_CHAIN },
            tunMtu = when {
                tunMtu == TUN_MTU_DEFAULT -> TUN_MTU_DEFAULT
                tunMtu in TUN_MTU_MIN..TUN_MTU_MAX -> tunMtu
                else -> TUN_MTU_DEFAULT
            },
            h2FragmentMode = h2FragmentMode.trim().lowercase().let { if (it in H2_FRAG_CHOICES) it else H2_FRAG_AUTO },
            // Always on — toggles removed from UI; ignore any old off prefs.
            ghostHandover = true,
            h2LargeWindows = true,
            h2SocketTuning = true,
            h2SpeedPick = true,
        )
    }

    companion object {
        val OBFUSCATION_PROFILES = setOf("off", "light", "balanced", "aggressive")
        val DEFAULT = PowEngineSettings()
        const val H2_OBFS_UNCHANGED = "unchanged"
        val H2_OBFS_CHOICES = setOf(H2_OBFS_UNCHANGED, "light", "off")
        /** 0 = the app's own MTU (clamped to 1280 as before). */
        const val TUN_MTU_DEFAULT = 0
        const val TUN_MTU_MIN = 1_000
        const val TUN_MTU_MAX = 1_500
        const val EXIT_CHAIN = "chain"
        const val EXIT_WARP = "warp"
        const val EXIT_PSIPHON = "psiphon"
        val EXIT_MODES = setOf(EXIT_CHAIN, EXIT_WARP, EXIT_PSIPHON)
        const val H2_FRAG_AUTO = "auto"
        const val H2_FRAG_ON = "on"
        const val H2_FRAG_OFF = "off"
        val H2_FRAG_CHOICES = setOf(H2_FRAG_AUTO, H2_FRAG_ON, H2_FRAG_OFF)
        val TUN_MTU_CHOICES = setOf(TUN_MTU_DEFAULT, 1280, 1350, 1400)
    }
}
