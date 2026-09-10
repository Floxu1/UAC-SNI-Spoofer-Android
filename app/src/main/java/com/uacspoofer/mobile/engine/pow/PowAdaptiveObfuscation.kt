package com.uacspoofer.mobile.engine.pow

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource

/**
 * Level C: Adaptive Obfuscation — enables H2 fragmentation only when DPI is likely.
 * Uses RTT variance + failure streak as cheap DPI signal. Default off for speed.
 * Isolated to PoW prefs key; never touches Xray/Tor.
 */
internal object PowAdaptiveObfuscation {
    private const val JITTER_DPI_MS = 180L
    private const val FAILS_TO_ENABLE = 2
    @Volatile private var dpiFails = 0

    fun onProbe(success: Boolean, jitterHigh: Boolean) {
        if (!success || jitterHigh) dpiFails++ else dpiFails = (dpiFails - 1).coerceAtLeast(0)
    }

    fun shouldEnableFragmentation(context: Context, rttVarianceHigh: Boolean): Boolean {
        val prefs = context.getSharedPreferences("uac_pow_engine_v1", Context.MODE_PRIVATE)
        if (prefs.getBoolean("h2_fragmentation", false)) return true
        if (rttVarianceHigh && dpiFails >= FAILS_TO_ENABLE) {
            AppLogRepository.info(LogSource.POW, "Adaptive H2 fragmentation ON (DPI suspected jitter+fails=$dpiFails)")
            prefs.edit().putBoolean("h2_fragmentation", true).apply()
            return true
        }
        return false
    }

    fun shouldDisableFragmentation(context: Context, stableMs: Long): Boolean {
        if (stableMs <= 0L) return false
        val prefs = context.getSharedPreferences("uac_pow_engine_v1", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("h2_fragmentation", false)) return false
        // If path has been stable for >3 minutes, turn off for speed.
        if (stableMs > 3 * 60_000L && dpiFails == 0) {
            AppLogRepository.info(LogSource.POW, "Adaptive H2 fragmentation OFF (stable)")
            prefs.edit().putBoolean("h2_fragmentation", false).apply()
            return true
        }
        return false
    }
}
