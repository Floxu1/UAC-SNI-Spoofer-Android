package com.uacspoofer.mobile.engine.pow

data class PowEngineSettings(
    val exitCountryCode: String = "",
    val outerTransport: String = PowCoreConfig.OUTER_AUTO,
    val obfuscationProfile: String = "balanced",
    val h2Fragmentation: Boolean = false,
) {
    fun validated(): PowEngineSettings {
        val country = PowRegions.normalize(exitCountryCode)
        val transport = outerTransport.trim().lowercase().ifBlank { PowCoreConfig.OUTER_AUTO }
        val obfuscation = obfuscationProfile.trim().lowercase().ifBlank { "balanced" }
        return copy(
            exitCountryCode = country,
            outerTransport = if (transport == PowCoreConfig.OUTER_AUTO || transport in PowCoreConfig.OUTER_LADDER) {
                transport
            } else {
                PowCoreConfig.OUTER_AUTO
            },
            obfuscationProfile = if (obfuscation in OBFUSCATION_PROFILES) obfuscation else "balanced",
        )
    }

    companion object {
        val OBFUSCATION_PROFILES = setOf("off", "light", "balanced", "aggressive")
        val DEFAULT = PowEngineSettings()
    }
}
