package com.uacspoofer.mobile.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TrafficStatsStoreTest {
    @Before
    fun setUp() = TrafficStatsStore.reset()

    @After
    fun tearDown() = TrafficStatsStore.reset()

    @Test
    fun mapsTunTxToUploadAndRxToDownload() {
        TrafficStatsStore.update(TunStats(3, 2_000, 4, 8_000), 1_000)
        TrafficStatsStore.update(TunStats(5, 5_000, 8, 14_000), 2_000)

        assertEquals(5_000, TrafficStatsStore.stats.value.uploadBytes)
        assertEquals(14_000, TrafficStatsStore.stats.value.downloadBytes)
        assertEquals(3_000, TrafficStatsStore.stats.value.uploadBytesPerSecond)
        assertEquals(6_000, TrafficStatsStore.stats.value.downloadBytesPerSecond)
    }

    @Test
    fun counterResetNeverCreatesNegativeRates() {
        TrafficStatsStore.update(TunStats(3, 20_000, 4, 80_000), 1_000)
        TrafficStatsStore.update(TunStats(1, 100, 1, 200), 2_000)

        assertEquals(0, TrafficStatsStore.stats.value.uploadBytesPerSecond)
        assertEquals(0, TrafficStatsStore.stats.value.downloadBytesPerSecond)
    }
}
