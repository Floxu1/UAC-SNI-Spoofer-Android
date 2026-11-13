package com.uacspoofer.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTrafficMeterTest {
    @Test
    fun formatsLikeTheNotificationLine() {
        val t = NotificationTraffic(
            downloadBytes = (372.75 * 1024 * 1024).toLong(),
            uploadBytes = (40.44 * 1024 * 1024).toLong(),
            downloadBytesPerSecond = (11.02 * 1024).toLong(),
            uploadBytesPerSecond = (2.94 * 1024).toLong(),
        )
        assertEquals("\u2193 373M | 11K/s   \u2191 40.4M | 2.9K/s", NotificationTrafficMeter.liveLine(t))
        assertEquals("\u2193 373M   \u2191 40.4M", NotificationTrafficMeter.totalsLine(t))
        assertEquals("\u2193 373M | 11K/s", NotificationTrafficMeter.downText(t, true))
        assertEquals("\u2191 40.4M | 2.9K/s", NotificationTrafficMeter.upText(t, true))
    }

    @Test
    fun unitsStepUp() {
        assertEquals("0 B", NotificationTrafficMeter.formatBytes(0))
        assertEquals("1023 B", NotificationTrafficMeter.formatBytes(1023))
        assertEquals("1.00 KB", NotificationTrafficMeter.formatBytes(1024))
        assertEquals("1.50 GB", NotificationTrafficMeter.formatBytes(1024L * 1024 * 1024 * 3 / 2))
        assertEquals("512 B/s", NotificationTrafficMeter.formatSpeed(512))
        assertEquals("2.00 MB/s", NotificationTrafficMeter.formatSpeed(2L * 1024 * 1024))
    }

    @Test
    fun prefersTunnelCountersAndComputesSpeed() {
        val m = NotificationTrafficMeter()
        m.sample(TunStats(0, 1_000, 0, 10_000), 5_000_000, 6_000_000, 1_000)
        val t = m.sample(TunStats(0, 3_000, 0, 30_000), 9_000_000, 9_000_000, 2_000)
        assertEquals(30_000L, t.downloadBytes)
        assertEquals(3_000L, t.uploadBytes)
        assertEquals(20_000L, t.downloadBytesPerSecond)
        assertEquals(2_000L, t.uploadBytesPerSecond)
    }

    @Test
    fun fallsBackToAppCountersWhenTheTunnelReportsNone() {
        val m = NotificationTrafficMeter()
        m.sample(TunStats.ZERO, 1_000_000, 500_000, 1_000)
        val t = m.sample(TunStats.ZERO, 1_004_096, 501_024, 2_000)
        assertEquals(4_096L, t.downloadBytes)
        assertEquals(1_024L, t.uploadBytes)
        assertEquals(4_096L, t.downloadBytesPerSecond)
        assertEquals(1_024L, t.uploadBytesPerSecond)
    }

    @Test
    fun resetClearsEverything() {
        val m = NotificationTrafficMeter()
        m.sample(TunStats(0, 10, 0, 20), -1, -1, 1_000)
        m.reset()
        assertEquals(NotificationTraffic(), m.last)
    }
}