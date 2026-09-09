package com.uacspoofer.mobile.engine.pow

import java.io.File

internal object PowQualityPolicy {
    const val CACHE_TTL_MS = 12L * 60_000L
    const val SETTLE_MS = 8_000L
    const val SAMPLE_INTERVAL_MS = 10_000L
    const val RETUNE_COOLDOWN_MS = 30_000L
    const val MAX_QUALITY_RETUNES = 3
    const val MAX_CRASH_RECOVERS = 4
    const val BAD_STREAK = 2
    const val FAIL_FAST_MS = 1_500
    const val INNER_RETUNE_TIMEOUT_MS = 55_000L
    const val PROBE_TIMEOUT_MS = 2_000
    const val BASELINE_SAMPLES = 5

    fun isDegraded(baselineMs: Long, sampleMs: Long): Boolean {
        if (sampleMs <= 0L) return true
        if (baselineMs <= 0L) return false
        // Tighter threshold for browsing: 1.6x + 180ms captures TTFB regressions earlier.
        val scaled = (baselineMs * 16L) / 10L
        val padded = baselineMs + 180L
        return sampleMs >= maxOf(scaled, padded)
    }

    fun median(samples: List<Long>): Long {
        val clean = samples.filter { it > 0L }.sorted()
        if (clean.isEmpty()) return 0L
        return clean[clean.size / 2]
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
