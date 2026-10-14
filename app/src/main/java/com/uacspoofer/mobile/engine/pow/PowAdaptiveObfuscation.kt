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
    /** Adaptive state lives in its own key so it never overwrites the user's H2 fragment choice. */
    const val KEY_ADAPTIVE = "h2_fragment_adaptive"

    private fun autoMode(context: Context): Boolean =
        PowEngineStore.get(context).snapshot().h2FragmentMode == PowEngineSettings.H2_FRAG_AUTO

    /** Read at the next outer (re)connect; changing it never forces a reconnect by itself. */
    fun adaptiveOn(context: Context): Boolean =
        context.getSharedPreferences("uac_pow_engine_v1", Context.MODE_PRIVATE).getBoolean(KEY_ADAPTIVE, false)

    private const val JITTER_DPI_MS = 180L
    private const val FAILS_TO_ENABLE = 2
    @Volatile private var dpiFails = 0

    fun onProbe(success: Boolean, jitterHigh: Boolean) {
        if (!success || jitterHigh) dpiFails++ else dpiFails = (dpiFails - 1).coerceAtLeast(0)
    }

    fun shouldEnableFragmentation(context: Context, rttVarianceHigh: Boolean): Boolean {
        if (!autoMode(context)) return false
        val prefs = context.getSharedPreferences("uac_pow_engine_v1", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ADAPTIVE, false)) return true
        if (rttVarianceHigh && dpiFails >= FAILS_TO_ENABLE) {
            AppLogRepository.info(LogSource.POW, "Adaptive H2 fragmentation ON (DPI suspected jitter+fails=$dpiFails)")
            prefs.edit().putBoolean(KEY_ADAPTIVE, true).apply()
            return true
        }
        return false
    }

    fun shouldDisableFragmentation(context: Context, stableMs: Long): Boolean {
        if (stableMs <= 0L) return false
        if (!autoMode(context)) return false
        val prefs = context.getSharedPreferences("uac_pow_engine_v1", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ADAPTIVE, false)) return false
        // If path has been stable for >3 minutes, turn off for speed.
        if (stableMs > 3 * 60_000L && dpiFails == 0) {
            AppLogRepository.info(LogSource.POW, "Adaptive H2 fragmentation OFF (stable)")
            prefs.edit().putBoolean(KEY_ADAPTIVE, false).apply()
            return true
        }
        return false
    }
}
