package com.uacspoofer.mobile.engine.tor

internal object TorBootstrapWatch {
    const val STALL_MS = 18_000L
    const val DIRECTORY_STALL_MS = 40_000L

    fun stallBudgetMs(percent: Int, reachedHandshake: Boolean): Long =
        if (reachedHandshake && percent in 45..74) DIRECTORY_STALL_MS else STALL_MS

    fun marksHandshake(percent: Int): Boolean = percent >= 15

    fun isProgressLine(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.contains("new control connection")) return false
        if (lower.contains("ignoring directory request")) return false
        if (lower.contains("no lists of tls groups")) return false
        return lower.contains("bootstrapped") ||
            lower.contains("new bridge descriptor") ||
            lower.contains("enough_dirinfo") ||
            lower.contains("loading relay descriptors") ||
            lower.contains("asking for relay descriptors") ||
            lower.contains("asking for networkstatus") ||
            lower.contains("handshake with a relay done") ||
            lower.contains("connected to a relay") ||
            lower.contains("connected to pluggable transport") ||
            lower.contains("establishing an encrypted directory") ||
            lower.contains("onehop_create") ||
            lower.contains("requesting_status") ||
            lower.contains("requesting_descriptors")
    }
}
