package com.uacspoofer.mobile.vpn

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class MonthlyTrafficSnapshot(
    val monthKey: String,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
}

object MonthlyTrafficLedger {
    fun monthKey(nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = nowMs
        return String.format(
            Locale.US,
            "%04d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
        )
    }

    fun apply(
        current: MonthlyTrafficSnapshot?,
        nowMonth: String,
        uploadDelta: Long,
        downloadDelta: Long,
    ): MonthlyTrafficSnapshot {
        val up = uploadDelta.coerceAtLeast(0L)
        val down = downloadDelta.coerceAtLeast(0L)
        if (current == null || current.monthKey != nowMonth) {
            return MonthlyTrafficSnapshot(nowMonth, up, down)
        }
        return current.copy(
            uploadBytes = current.uploadBytes + up,
            downloadBytes = current.downloadBytes + down,
        )
    }
}
