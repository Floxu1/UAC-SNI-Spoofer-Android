package com.uacspoofer.mobile.engine.pow

import java.io.File

internal object PowQualityPolicy {
    const val CACHE_TTL_MS = 12L * 60_000L
    const val SETTLE_MS = 25_000L
    const val SAMPLE_INTERVAL_MS = 40_000L
    const val RETUNE_COOLDOWN_MS = 90_000L
    const val MAX_QUALITY_RETUNES = 3
    const val MAX_CRASH_RECOVERS = 4
    const val BAD_STREAK = 2
    const val FAIL_FAST_MS = 1_500
    const val INNER_RETUNE_TIMEOUT_MS = 55_000L
    const val PROBE_TIMEOUT_MS = 3_500
    const val BASELINE_SAMPLES = 3

    fun isDegraded(baselineMs: Long, sampleMs: Long): Boolean {
        if (sampleMs <= 0L) return true
        if (baselineMs <= 0L) return false
        val doubled = baselineMs * 2L
        val padded = baselineMs + 250L
        return sampleMs >= maxOf(doubled, padded)
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
