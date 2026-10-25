package com.uacspoofer.mobile.engine.pow

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * User-triggered MTU scan for the PoW tunnel settings sheet.
 * Walks 1500 → 1000 (step 10), also hits the exact path budget and 1280,
 * and picks the largest value that fits. Unknown path → recommended 1280.
 */
object MtuAutoScanner {
    /** Allowed manual / scanned values persisted in PoW settings. */
    const val MIN_SAVED = 1_000
    const val MAX_SAVED = 1_500
    const val SCAN_STEP = 10

    /** Typical VPN encapsulation overhead (WARP / MASQUE / Psiphon). */
    private const val VPN_OVERHEAD = 80
    private const val TRY_DELAY_MS = 45L

    private const val PREFS = "pow_mtu_scanner"
    private const val KEY_LAST_SCANNED = "last_scanned_mtu"

    data class Result(
        val mtu: Int,
        val networkMtu: Int,
        val tried: List<Int>,
    )

    fun networkMtu(context: Context): Int = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return 0
        val network = cm.activeNetwork ?: return 0
        cm.getLinkProperties(network)?.mtu ?: 0
    }.getOrDefault(0)

    /**
     * Path budget = underlying link MTU minus VPN overhead.
     * When the link MTU is unknown, budget stays at the recommended 1280.
     */
    fun pathBudget(networkMtu: Int): Int = when (networkMtu) {
        in 576..9_000 -> (networkMtu - VPN_OVERHEAD).coerceIn(MIN_SAVED, MAX_SAVED)
        else -> PowTunRelayConfig.DEFAULT_MTU
    }

    /**
     * Full sweep list: 1500, 1490, …, 1000, plus exact [budget] and 1280 if missing.
     * Sorted largest-first so the first fit is the best MTU.
     */
    fun sweepValues(budget: Int = MAX_SAVED): List<Int> {
        val values = linkedSetOf<Int>()
        var v = MAX_SAVED
        while (v >= MIN_SAVED) {
            values += v
            v -= SCAN_STEP
        }
        values += PowTunRelayConfig.DEFAULT_MTU
        if (budget in MIN_SAVED..MAX_SAVED) values += budget
        return values.sortedDescending()
    }

    fun pickLargest(budget: Int): Int {
        val capped = budget.coerceIn(MIN_SAVED, MAX_SAVED)
        return sweepValues(capped).firstOrNull { it <= capped }
            ?: PowTunRelayConfig.DEFAULT_MTU
    }

    fun lastScannedMtu(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_SCANNED, 0)
            .takeIf { it in MIN_SAVED..MAX_SAVED }
            ?: 0

    fun saveLastScannedMtu(context: Context, mtu: Int) {
        if (mtu !in MIN_SAVED..MAX_SAVED) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_SCANNED, mtu)
            .apply()
    }

    suspend fun scan(
        context: Context,
        onTrying: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.Default) {
        val link = networkMtu(context)
        val budget = pathBudget(link)
        val candidates = sweepValues(budget)
        val tried = mutableListOf<Int>()
        var chosen = PowTunRelayConfig.DEFAULT_MTU
        for (candidate in candidates) {
            tried += candidate
            onTrying(candidate)
            delay(TRY_DELAY_MS)
            if (candidate <= budget) {
                chosen = candidate.coerceIn(MIN_SAVED, MAX_SAVED)
                break
            }
        }
        saveLastScannedMtu(context, chosen)
        Result(mtu = chosen, networkMtu = link, tried = tried)
    }

    fun clampManual(raw: Int): Int? =
        raw.takeIf { it in MIN_SAVED..MAX_SAVED }
}
