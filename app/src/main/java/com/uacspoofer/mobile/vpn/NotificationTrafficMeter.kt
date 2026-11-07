package com.uacspoofer.mobile.vpn

import java.util.Locale

/** One reading of the notification traffic line: totals and per-second speeds. */
data class NotificationTraffic(
    val downloadBytes: Long = 0L,
    val uploadBytes: Long = 0L,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
)

/**
 * Turns raw counters into the live line of the VPN notification.
 *
 * The tunnel's own counters (TunStats) are used when the engine reports them.
 * When they stay at zero (proxy mode has no TUN), the app's own byte counters
 * from android.net.TrafficStats are used instead, counted from the moment the
 * meter started. Once the tunnel counters move, the meter sticks with them.
 */
class NotificationTrafficMeter {
    private var useTun = false
    private var uidBaseRx = -1L
    private var uidBaseTx = -1L
    private var prevDown = 0L
    private var prevUp = 0L
    private var prevAt = 0L

    @Volatile
    var last: NotificationTraffic = NotificationTraffic()
        private set

    @Synchronized
    fun reset() {
        useTun = false
        uidBaseRx = -1L
        uidBaseTx = -1L
        prevDown = 0L
        prevUp = 0L
        prevAt = 0L
        last = NotificationTraffic()
    }

    /**
     * @param tun tunnel counters (upload = txBytes, download = rxBytes, as in TrafficStatsStore)
     * @param uidRx / uidTx the app's TrafficStats counters, or a negative value when unsupported
     */
    @Synchronized
    fun sample(tun: TunStats, uidRx: Long, uidTx: Long, nowMs: Long): NotificationTraffic {
        if (uidBaseRx < 0L && uidRx >= 0L) uidBaseRx = uidRx
        if (uidBaseTx < 0L && uidTx >= 0L) uidBaseTx = uidTx
        if (!useTun && (tun.rxBytes > 0L || tun.txBytes > 0L)) {
            useTun = true
            prevAt = 0L
        }
        val down: Long
        val up: Long
        if (useTun) {
            down = tun.rxBytes.coerceAtLeast(0L)
            up = tun.txBytes.coerceAtLeast(0L)
        } else {
            down = if (uidRx >= 0L && uidBaseRx >= 0L) (uidRx - uidBaseRx).coerceAtLeast(0L) else 0L
            up = if (uidTx >= 0L && uidBaseTx >= 0L) (uidTx - uidBaseTx).coerceAtLeast(0L) else 0L
        }
        val elapsed = nowMs - prevAt
        val rated = prevAt > 0L && elapsed > 0L && down >= prevDown && up >= prevUp
        val downRate = if (rated) (down - prevDown) * 1_000L / elapsed else 0L
        val upRate = if (rated) (up - prevUp) * 1_000L / elapsed else 0L
        prevDown = down
        prevUp = up
        prevAt = nowMs
        val out = NotificationTraffic(down, up, downRate.coerceAtLeast(0L), upRate.coerceAtLeast(0L))
        last = out
        return out
    }

    companion object {
        /** "↓ 372.75 MB | 11.02 KB/s   ↑ 40.44 MB | 2.94 KB/s" */
        fun liveLine(t: NotificationTraffic): String =
            "\u2193 ${formatBytes(t.downloadBytes)} | ${formatSpeed(t.downloadBytesPerSecond)}" +
                "   \u2191 ${formatBytes(t.uploadBytes)} | ${formatSpeed(t.uploadBytesPerSecond)}"

        /** Totals only, for the paused state: "↓ 372.75 MB   ↑ 40.44 MB" */
        fun totalsLine(t: NotificationTraffic): String =
            "\u2193 ${formatBytes(t.downloadBytes)}   \u2191 ${formatBytes(t.uploadBytes)}"

        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L).toDouble()
            return when {
                b < 1024.0 -> "${bytes.coerceAtLeast(0L)} B"
                b < 1024.0 * 1024 -> String.format(Locale.US, "%.2f KB", b / 1024)
                b < 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", b / (1024 * 1024))
                else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
            }
        }

        fun formatSpeed(bytesPerSecond: Long): String {
            val b = bytesPerSecond.coerceAtLeast(0L).toDouble()
            return when {
                b < 1024.0 -> "${bytesPerSecond.coerceAtLeast(0L)} B/s"
                b < 1024.0 * 1024 -> String.format(Locale.US, "%.2f KB/s", b / 1024)
                else -> String.format(Locale.US, "%.2f MB/s", b / (1024 * 1024))
            }
        }
    }
}