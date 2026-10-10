package com.uacspoofer.mobile.engine.pow

/**
 * Connected-session liveness for UAC PoW.
 * Local process flags plus byte movement. No HTTP.
 * Idle traffic (screen on or off) is not a dead tunnel.
 * A missing process must be seen twice before reconnect.
 */
internal object PowWatchdog {
    const val INTERVAL_MS = 30_000L
    const val INTERVAL_SCREEN_OFF_MS = 120_000L

    /**
     * Battery: screen off + no active streams means idle — the liveness poll can
     * stretch to [INTERVAL_SCREEN_OFF_MS]. A background stream (music/video) keeps
     * the short interval so a dead path is still caught fast.
     */
    fun intervalMs(screenOn: Boolean, activeStreams: Int): Long = when {
        !screenOn && activeStreams <= 0 -> INTERVAL_SCREEN_OFF_MS
        else -> INTERVAL_MS
    }
    const val SETTLE_MS = 8_000L
    const val STRIKES_BEFORE_RECONNECT = 2

    sealed class Verdict {
        data object Healthy : Verdict()
        data class Strike(val nextStrikes: Int, val reason: String) : Verdict()
        data class Dead(val reason: String) : Verdict()
    }

    fun judge(
        processesAlive: Boolean,
        txBytes: Long,
        rxBytes: Long,
        previousTx: Long,
        previousRx: Long,
        processMisses: Int,
        stallStrikes: Int,
    ): Verdict {
        if (!processesAlive) {
            val next = processMisses + 1
            val reason = "engine process exited"
            return if (next < STRIKES_BEFORE_RECONNECT) {
                Verdict.Strike(next, reason)
            } else {
                Verdict.Dead(reason)
            }
        }
        if (previousTx < 0L || previousRx < 0L) return Verdict.Healthy
        val downstreamMoved = rxBytes != previousRx
        val upstreamMoved = txBytes != previousTx
        if (downstreamMoved) return Verdict.Healthy
        if (!upstreamMoved) return Verdict.Healthy
        val next = stallStrikes + 1
        val reason = "the tunnel accepted traffic but answered nothing"
        return if (next < STRIKES_BEFORE_RECONNECT) {
            Verdict.Strike(next, reason)
        } else {
            Verdict.Dead(reason)
        }
    }
}
