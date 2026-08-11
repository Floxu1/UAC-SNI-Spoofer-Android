package com.uacspoofer.mobile.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrafficStats(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val downloadBytesPerSecond: Long = 0L,
)


object TrafficStatsStore {
    private val mutableStats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = mutableStats.asStateFlow()

    private var previousUpload = 0L
    private var previousDownload = 0L
    private var previousTimestampMs = 0L

    @Synchronized
    fun reset() {
        previousUpload = 0L
        previousDownload = 0L
        previousTimestampMs = 0L
        mutableStats.value = TrafficStats()
    }

    @Synchronized
    fun update(native: TunStats, timestampMs: Long) {
        val upload = native.txBytes.coerceAtLeast(0L)
        val download = native.rxBytes.coerceAtLeast(0L)
        val elapsed = timestampMs - previousTimestampMs
        val countersAdvanced = upload >= previousUpload && download >= previousDownload
        val uploadRate = if (previousTimestampMs > 0L && elapsed > 0L && countersAdvanced) {
            ((upload - previousUpload) * 1_000L / elapsed).coerceAtLeast(0L)
        } else 0L
        val downloadRate = if (previousTimestampMs > 0L && elapsed > 0L && countersAdvanced) {
            ((download - previousDownload) * 1_000L / elapsed).coerceAtLeast(0L)
        } else 0L

        previousUpload = upload
        previousDownload = download
        previousTimestampMs = timestampMs
        mutableStats.value = TrafficStats(
            uploadBytes = upload,
            downloadBytes = download,
            uploadBytesPerSecond = uploadRate,
            downloadBytesPerSecond = downloadRate,
        )
    }
}
