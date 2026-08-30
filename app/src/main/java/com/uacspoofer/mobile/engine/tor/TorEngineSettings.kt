package com.uacspoofer.mobile.engine.tor

data class TorEngineSettings(
    val bridgeLines: String = "",
    val socksPort: Int = SOCKS_PORT,
    val controlPort: Int = CONTROL_PORT,
    val fragmentEnabled: Boolean = true,
    val fragmentPacket: String = "tlshello",
    val fragmentLength: Int = 5,
    val fragmentDelayMs: Int = 0,
    val exitCountryCode: String = TorExitCountry.AUTOMATIC,
    val exitStrict: Boolean = false,
) {
    fun validated(): TorEngineSettings = copy(
        bridgeLines = bridgeLines.trim(),
        socksPort = socksPort.coerceIn(1024, 65_535),
        controlPort = controlPort.coerceIn(1024, 65_535).let { port ->
            if (port == socksPort) (socksPort + 1).coerceAtMost(65_535) else port
        },
        fragmentPacket = fragmentPacket.trim().ifBlank { "tlshello" },
        fragmentLength = fragmentLength.coerceIn(1, 256),
        fragmentDelayMs = fragmentDelayMs.coerceIn(0, 1_000),
        exitCountryCode = TorExitCountry.normalize(exitCountryCode),
    )

    companion object {
        const val SOCKS_PORT = 19_050
        const val CONTROL_PORT = 19_051
        val DEFAULT = TorEngineSettings()
    }
}
