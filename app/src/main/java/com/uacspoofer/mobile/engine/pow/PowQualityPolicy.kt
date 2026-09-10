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
