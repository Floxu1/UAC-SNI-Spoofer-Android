package com.uacspoofer.mobile.engine.pow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowNetworkRestoreTest {
    @Test
    fun lostNetworkDoesNotRetuneWhileAlive() {
        assertFalse(PowNetworkRestore.shouldActOnLost())
    }

    @Test
    fun lostNetworkIsVerifiedWhileTheSessionIsLive() {
        assertTrue(
            PowNetworkRestore.shouldVerifyOnLost(
                sessionLive = true,
                retuning = false,
                stopRequested = false,
            ),
        )
    }

    @Test
    fun lostNetworkIsIgnoredWhileRetuningOrStopped() {
        assertFalse(
            PowNetworkRestore.shouldVerifyOnLost(
                sessionLive = true,
                retuning = true,
                stopRequested = false,
            ),
        )
        assertFalse(
            PowNetworkRestore.shouldVerifyOnLost(
                sessionLive = true,
                retuning = false,
                stopRequested = true,
            ),
        )
        assertFalse(
            PowNetworkRestore.shouldVerifyOnLost(
                sessionLive = false,
                retuning = false,
                stopRequested = false,
            ),
        )
    }

    @Test
    fun availableNetworkRetriesOnlyWhileWaiting() {
        assertTrue(
            PowNetworkRestore.shouldRetryOnAvailable(
                waiting = true,
                stopRequested = false,
                pathReady = false,
            ),
        )
        assertFalse(
            PowNetworkRestore.shouldRetryOnAvailable(
                waiting = false,
                stopRequested = false,
                pathReady = false,
            ),
        )
        assertFalse(
            PowNetworkRestore.shouldRetryOnAvailable(
                waiting = true,
                stopRequested = true,
                pathReady = false,
            ),
        )
        assertFalse(
            PowNetworkRestore.shouldRetryOnAvailable(
                waiting = true,
                stopRequested = false,
                pathReady = true,
            ),
        )
    }

    @Test
    fun backoffCapsAtTwoMinutes() {
        assertEquals(5_000L, PowNetworkRestore.delayMs(0))
        assertEquals(15_000L, PowNetworkRestore.delayMs(1))
        assertEquals(30_000L, PowNetworkRestore.delayMs(2))
        assertEquals(60_000L, PowNetworkRestore.delayMs(3))
        assertEquals(120_000L, PowNetworkRestore.delayMs(4))
        assertEquals(120_000L, PowNetworkRestore.delayMs(20))
    }

    @Test
    fun reconnectCopyStaysUiSafe() {
        assertEquals(
            "Reconnecting after the tunnel stopped passing traffic…",
            PowNetworkRestore.statusDetail("the tunnel stopped passing traffic"),
        )
    }
}
