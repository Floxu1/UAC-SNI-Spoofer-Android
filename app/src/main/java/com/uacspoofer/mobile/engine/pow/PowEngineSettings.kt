package com.uacspoofer.mobile.engine.pow

data class PowEngineSettings(
    val exitCountryCode: String = "",
    val outerTransport: String = PowCoreConfig.OUTER_AUTO,
    val obfuscationProfile: String = "balanced",
    val h2Fragmentation: Boolean = true,
    val scanMode: String = PowCoreConfig.SCAN_BALANCED,
    val optimizedMode: Boolean = true,
    val firewallProfile: String = PowCoreConfig.FIREWALL_AUTO,
    val linkRecipe: String = PowCoreConfig.RECIPE_AUTO,
    val chainPsiphon: Boolean = true,
    val keyFetch: String = PowCoreConfig.KEY_AUTO,
    val psiphonTunnelPool: Boolean = false,
    val quicTuned: Boolean = false,
) {
    fun validated(): PowEngineSettings {
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
        )
    }

    companion object {
        val OBFUSCATION_PROFILES = setOf("off", "light", "balanced", "aggressive")
        val DEFAULT = PowEngineSettings()
    }
}
