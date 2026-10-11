package com.uacspoofer.mobile.engine.pow

import java.io.File

internal object PowQualityPolicy {
    const val CACHE_TTL_MS = 12L * 60_000L
    // Level A: faster settle so baseline is ready in ~5s instead of 12s
    const val SETTLE_MS = 5_000L
    // Adaptive intervals: active browsing vs idle/background
    const val SAMPLE_INTERVAL_MS = 7_000L
    const val SAMPLE_INTERVAL_IDLE_MS = 25_000L
    const val SAMPLE_INTERVAL_SCREEN_OFF_MS = 30_000L
    const val RETUNE_COOLDOWN_MS = 22_000L
    const val RETUNE_COOLDOWN_NETWORK_MS = 12_000L
    const val MAX_QUALITY_RETUNES = 5
    const val MAX_CRASH_RECOVERS = 4
    const val BAD_STREAK = 2
    const val BAD_STREAK_JITTER = 3
    const val JITTER_THRESHOLD_MS = 120L
    const val FAIL_FAST_MS = 1_200
    const val INNER_RETUNE_TIMEOUT_MS = 42_000L
    const val GHOST_HANDOVER_TIMEOUT_MS = 38_000L
    const val PROBE_TIMEOUT_MS = 1_800
    const val BASELINE_SAMPLES = 3
    const val DNS_WARM_TIMEOUT_MS = 1_200

    // --- Battery-friendly passive quality watch (optimized mode) ---
    // Samples ride on real user traffic; an active probe is only fired when the
    // relay has been quiet for a while, so a screen-on session still catches a
    // degraded path without polling the radio every few seconds.
    const val PASSIVE_INTERVAL_MS = 15_000L
    const val PASSIVE_INTERVAL_IDLE_MS = 60_000L
    const val IDLE_PROBE_AFTER_MS = 45_000L
    const val PASSIVE_MAX_RETUNES = 3
    // Four bad checks in a row (about a minute of screen-on use) before the
    // path is judged; one slow page load or a brief stall is not enough.
    const val PASSIVE_BAD_STREAK = 4

    // Busy gate. Latency measured while the tunnel is moving bulk data is
    // queueing delay behind that data, not a slow path, so the passive watch
    // does not judge the path at those times.
    const val BUSY_BYTES_PER_SEC = 200L * 1024L
    const val BUSY_ACTIVE_STREAMS = 16
    const val BUSY_SPOT_WINDOW_MS = 2_000L

    fun bytesPerSecond(deltaBytes: Long, elapsedMs: Long): Long =
        if (deltaBytes <= 0L || elapsedMs <= 0L) 0L else deltaBytes * 1000L / elapsedMs

    fun isBusy(bytesPerSec: Long, activeStreams: Int): Boolean =
        bytesPerSec >= BUSY_BYTES_PER_SEC || activeStreams >= BUSY_ACTIVE_STREAMS

    /** Nearest-rank percentile of the positive samples; 0 when there are none. */
    fun percentile(samples: List<Long>, pct: Int): Long {
        val clean = samples.filter { it > 0L }.sorted()
        if (clean.isEmpty()) return 0L
        val p = pct.coerceIn(0, 100)
        val rank = ((p * clean.size + 99) / 100).coerceIn(1, clean.size)
        return clean[rank - 1]
    }

    /** Low quartile: what the path can do, without the slow outliers that queueing adds. */
    fun p25(samples: List<Long>): Long = percentile(samples, 25)

    fun isDegraded(baselineMs: Long, sampleMs: Long): Boolean {
        if (sampleMs <= 0L) return true
        if (baselineMs <= 0L) return false
        // 1.55x + 160ms — slightly tighter for edge TTFB, looser for very low baselines
        val scaled = (baselineMs * 155L) / 100L
        val padded = baselineMs + 160L
        return sampleMs >= maxOf(scaled, padded)
    }

    fun isJitterHigh(recentMs: List<Long>): Boolean {
        if (recentMs.size < 2) return false
        val clean = recentMs.filter { it > 0L }
        if (clean.size < 2) return false
        val max = clean.maxOrNull() ?: return false
        val min = clean.minOrNull() ?: return false
        return (max - min) >= JITTER_THRESHOLD_MS && max >= (min * 13L / 10L)
    }

    fun requiredBadStreak(recentMs: List<Long>): Int =
        if (isJitterHigh(recentMs)) BAD_STREAK_JITTER else BAD_STREAK

    fun median(samples: List<Long>): Long {
        val clean = samples.filter { it > 0L }.sorted()
        if (clean.isEmpty()) return 0L
        return clean[clean.size / 2]
    }

    fun adaptiveIntervalMs(screenOn: Boolean, powerSave: Boolean): Long = when {
        !screenOn -> SAMPLE_INTERVAL_SCREEN_OFF_MS
        powerSave -> SAMPLE_INTERVAL_IDLE_MS
        else -> SAMPLE_INTERVAL_MS
    }

    fun expireFileIfStale(file: File, nowMs: Long, ttlMs: Long = CACHE_TTL_MS): Boolean {
        if (!file.isFile) return false
        if (nowMs - file.lastModified() <= ttlMs) return false
        return file.delete()
    }

    fun outerOrder(ladder: List<String>, current: String): List<String> {
        val hit = current.trim().lowercase()
        if (hit.isEmpty() || hit !in ladder) return ladder
        return listOf(hit) + ladder.filter { it != hit }
    }
}
