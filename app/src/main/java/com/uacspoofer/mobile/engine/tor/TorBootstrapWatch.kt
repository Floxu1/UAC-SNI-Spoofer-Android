package com.uacspoofer.mobile.engine.tor

internal object TorBootstrapWatch {
    const val STALL_MS = 18_000L
    const val DIRECTORY_STALL_MS = 75_000L
    const val DIRECTORY_STALL_WARM_MS = 20_000L
    const val HANDSHAKE_DEADLINE_WARM_MS = 15_000L
    const val HANDSHAKE_DEADLINE_COLD_MS = 25_000L

    /** The long directory budget is only for a cold start (no cached consensus). */
    fun stallBudgetMs(percent: Int, reachedHandshake: Boolean, coldStart: Boolean = true): Long =
        if (reachedHandshake && percent in 45..74) {
            if (coldStart) DIRECTORY_STALL_MS else DIRECTORY_STALL_WARM_MS
        } else {
            STALL_MS
        }

    fun handshakeDeadlineMs(coldStart: Boolean): Long =
        if (coldStart) HANDSHAKE_DEADLINE_COLD_MS else HANDSHAKE_DEADLINE_WARM_MS

    /** Stage timeout: the bridge never finished the Tor handshake (10-15 %) in time. */
    fun handshakeMissed(elapsedMs: Long, reachedHandshake: Boolean, coldStart: Boolean, enabled: Boolean): Boolean =
        enabled && !reachedHandshake && elapsedMs >= handshakeDeadlineMs(coldStart)

    fun marksHandshake(percent: Int): Boolean = percent >= 15

    /** The PT would not use TOR_PT_PROXY (our local fragment proxy). */
    fun isProxyRefusal(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("did not configure the specified outgoing proxy") ||
            lower.contains("proxy-error") ||
            (lower.contains("outgoing proxy") && lower.contains("not support"))
    }

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

internal class TorPtProxyRefusedException(message: String) : IllegalStateException(message)

/** The pluggable transport keeps dying (e.g. wrong-ABI binary under emulator translation). */
internal class TorPtCrashLoopException(message: String) : IllegalStateException(message)

/** Counts "Managed proxy ... terminated" lines in a sliding window. */
internal class PtCrashCounter(private val limit: Int = LIMIT, private val windowMs: Long = WINDOW_MS) {
    private val times = ArrayDeque<Long>()

    fun record(line: String, now: Long): Boolean {
        if (!isPtTermination(line)) return false
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        return times.size >= limit
    }

    companion object {
        const val LIMIT = 3
        const val WINDOW_MS = 10_000L

        fun isPtTermination(line: String): Boolean {
            val lower = line.lowercase()
            return lower.contains("managed proxy") && lower.contains("terminated")
        }
    }
}