package com.uacspoofer.mobile.engine.tor

data class TorEngineSettings(
    val bridgeLines: String = "",
    val socksPort: Int = SOCKS_PORT,
    val controlPort: Int = CONTROL_PORT,
    /** auto | on | off. Auto = split the WebTunnel ClientHello unless the PT refuses the local proxy. */
    val fragmentMode: String = FRAGMENT_AUTO,
    val fragmentPacket: String = "tlshello",
    val fragmentLength: Int = 5,
    val fragmentDelayMs: Int = 0,
    val exitCountryCode: String = TorExitCountry.AUTOMATIC,
    val exitStrict: Boolean = false,
    /** Automatic exit narrowed to nearby fast countries (de/nl/fr), not strict. */
    val fastExit: Boolean = false,
    /** Drop UDP sessions fast in the TUN (Tor SOCKS cannot carry UDP). */
    val blockUdp: Boolean = true,
    /** ReducedConnectionPadding 1: fewer padding cells, less radio wake-ups. */
    val reducedPadding: Boolean = true,
    /** One Tor process; later bridge batches are swapped over the control port. */
    val singleProcess: Boolean = true,
    /** Stage-specific bootstrap deadlines (handshake / directory). */
    val stageTimeouts: Boolean = true,
    /** 60 s SOCKS check while the screen is on, SIGNAL ACTIVE on network change. */
    val watchdog: Boolean = true,
    /** Once a day, fetch fresh WebTunnel bridges through Tor itself. */
    val bridgeRefresh: Boolean = false,
    /** Optional plain-text URL with webtunnel lines; empty = Tor Project Moat. */
    val bridgeRefreshUrl: String = "",
    /** Mirror Tor log lines to logcat (tag UAC-TOR). */
    val logcatMirror: Boolean = true,
    /** Two primary bridge guards + conflux throughput UX. */
    val dualGuards: Boolean = true,
    /** One small download per session (screen on) to rank bridges by speed. */
    val throughputScoring: Boolean = true,
    /** 256 KB tun2socks TCP buffers and socket buffers on the fragment proxy. */
    val bigBuffers: Boolean = true,
    /** Pin middle hops to the highest-bandwidth Fast+Stable relays from the consensus. */
    val fastRelays: Boolean = true,
    /** SocksPort IsolateDestAddr: parallel destinations get separate circuits. */
    val multiCircuit: Boolean = true,
    /** NEWNYM when sustained throughput under load stays below the threshold (max once / 2 min). */
    val slowCircuitSwap: Boolean = true,
    /** Tor TUN MTU; 0 = app default. */
    val tunMtu: Int = 0,
) {
    /** Public SOCKS (hev / apps). Tor daemon listens on [daemonSocksPort]; DomesticSocksSplit bridges them. */
    fun effectiveMtu(appMtu: Int): Int = if (tunMtu in MTU_CHOICES) tunMtu else appMtu
    val daemonSocksPort: Int get() = (socksPort + SOCKS_SPLIT_OFFSET).coerceAtMost(65_535)
    val fragmentEnabled: Boolean get() = fragmentMode != FRAGMENT_OFF

    fun validated(): TorEngineSettings = copy(
        bridgeLines = bridgeLines.trim(),
        socksPort = socksPort.coerceIn(1024, 65_535),
        controlPort = controlPort.coerceIn(1024, 65_535).let { port ->
            if (port == socksPort) (socksPort + 1).coerceAtMost(65_535) else port
        },
        fragmentMode = fragmentMode.trim().lowercase().takeIf { it in FRAGMENT_MODES } ?: FRAGMENT_AUTO,
        fragmentPacket = fragmentPacket.trim().ifBlank { "tlshello" },
        fragmentLength = fragmentLength.coerceIn(1, 256),
        fragmentDelayMs = fragmentDelayMs.coerceIn(0, 1_000),
        exitCountryCode = TorExitCountry.normalize(exitCountryCode),
        bridgeRefreshUrl = bridgeRefreshUrl.trim(),
        tunMtu = if (tunMtu in MTU_CHOICES) tunMtu else 0,
    )

    companion object {
        const val SOCKS_PORT = 19_050
        const val SOCKS_SPLIT_OFFSET = 10
        const val CONTROL_PORT = 19_051
        const val FRAGMENT_AUTO = "auto"
        const val FRAGMENT_ON = "on"
        const val FRAGMENT_OFF = "off"
        val FRAGMENT_MODES = setOf(FRAGMENT_AUTO, FRAGMENT_ON, FRAGMENT_OFF)
        val DEFAULT = TorEngineSettings()
        val MTU_CHOICES = setOf(1280, 1350, 1400)
    }
}