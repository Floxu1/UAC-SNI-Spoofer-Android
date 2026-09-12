package com.uacspoofer.mobile.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MonthlyTrafficStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableUsage = MutableStateFlow(read())
    val usage: StateFlow<MonthlyTrafficSnapshot> = mutableUsage.asStateFlow()

    fun snapshot(): MonthlyTrafficSnapshot = rollover(mutableUsage.value)

    @Synchronized
    fun add(uploadDelta: Long, downloadDelta: Long, nowMs: Long = System.currentTimeMillis()) {
        val next = MonthlyTrafficLedger.apply(
            current = rollover(mutableUsage.value, nowMs),
            nowMonth = MonthlyTrafficLedger.monthKey(nowMs),
            uploadDelta = uploadDelta,
            downloadDelta = downloadDelta,
        )
        persist(next)
    }

    @Synchronized
    private fun rollover(
        current: MonthlyTrafficSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): MonthlyTrafficSnapshot {
        val month = MonthlyTrafficLedger.monthKey(nowMs)
        if (current.monthKey == month) return current
        val fresh = MonthlyTrafficSnapshot(month)
        persist(fresh)
        return fresh
    }

    private fun read(): MonthlyTrafficSnapshot {
        val month = prefs.getString(KEY_MONTH, null).orEmpty()
        if (month.isBlank()) {
            return MonthlyTrafficSnapshot(MonthlyTrafficLedger.monthKey(System.currentTimeMillis()))
        }
        return MonthlyTrafficSnapshot(
            monthKey = month,
            uploadBytes = prefs.getLong(KEY_UPLOAD, 0L).coerceAtLeast(0L),
            downloadBytes = prefs.getLong(KEY_DOWNLOAD, 0L).coerceAtLeast(0L),
        )
    }

    private fun persist(value: MonthlyTrafficSnapshot) {
        prefs.edit()
            .putString(KEY_MONTH, value.monthKey)
            .putLong(KEY_UPLOAD, value.uploadBytes)
            .putLong(KEY_DOWNLOAD, value.downloadBytes)
            .apply()
        mutableUsage.value = value
    }

    companion object {
        private const val PREFS = "monthly_traffic_v1"
        private const val KEY_MONTH = "month"
        private const val KEY_UPLOAD = "upload"
        private const val KEY_DOWNLOAD = "download"

        @Volatile private var instance: MonthlyTrafficStore? = null

        fun get(context: Context): MonthlyTrafficStore = instance ?: synchronized(this) {
            instance ?: MonthlyTrafficStore(context.applicationContext).also { store ->
                instance = store
                TrafficStatsStore.monthlySink = { up, down -> store.add(up, down) }
            }
        }
    }
}
