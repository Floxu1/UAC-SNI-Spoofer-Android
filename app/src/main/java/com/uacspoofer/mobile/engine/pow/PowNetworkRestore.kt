package com.uacspoofer.mobile.engine.pow

/**
 * After UAC PoW is up, a lost carrier or dropped internet is not a live retune.
 * The path is treated as dead only by the watchdog; reconnect waits on backoff
 * until ConnectivityManager says a network is available again.
 */
internal object PowNetworkRestore {
    val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)

    /** SIM / default-data subscription changes need a longer settle. */
    const val SIM_SWITCH_DEBOUNCE_MS = 2_000L

    fun delayMs(attemptIndex: Int): Long {
        val index = attemptIndex.coerceAtLeast(0).coerceAtMost(BACKOFF_MS.lastIndex)
        return BACKOFF_MS[index]
    }

    fun shouldActOnLost(): Boolean = false

    /**
     * A lost network is not by itself proof the tunnel died — the default network
     * may have simply changed. But it is a reason to *verify* the path quickly so a
     * Wi-Fi/cell handoff is recovered in seconds instead of waiting on the liveness
     * poll. Push-based; costs nothing while idle.
     */
    fun shouldVerifyOnLost(
        sessionLive: Boolean,
        retuning: Boolean,
        stopRequested: Boolean,
        paused: Boolean = false,
    ): Boolean = sessionLive && !retuning && !stopRequested && !paused

    fun shouldRetryOnAvailable(
        waiting: Boolean,
        stopRequested: Boolean,
        pathReady: Boolean,
    ): Boolean = waiting && !stopRequested && !pathReady

    fun statusDetail(reason: String): String {
        val clean = reason.trim().ifBlank { "the connection dropped" }
        return "Reconnecting after $clean."
    }

    fun simSwitchDetail(): String = "SIM changed - reconnecting."
}
