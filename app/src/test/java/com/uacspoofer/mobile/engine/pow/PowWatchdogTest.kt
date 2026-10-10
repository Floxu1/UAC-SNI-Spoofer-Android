package com.uacspoofer.mobile.engine.pow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowWatchdogTest {
    @Test
    fun firstTickOnlyBaselines() {
        val verdict = PowWatchdog.judge(
            processesAlive = true,
            txBytes = 10L,
            rxBytes = 20L,
            previousTx = -1L,
            previousRx = -1L,
            processMisses = 0,
            stallStrikes = 0,
        )
        assertEquals(PowWatchdog.Verdict.Healthy, verdict)
    }

    @Test
    fun downstreamBytesAreAlwaysHealthy() {
        val verdict = PowWatchdog.judge(
            processesAlive = true,
            txBytes = 10L,
            rxBytes = 40L,
            previousTx = 10L,
            previousRx = 20L,
            processMisses = 1,
            stallStrikes = 1,
        )
        assertEquals(PowWatchdog.Verdict.Healthy, verdict)
    }

    @Test
    fun idleIsNotDeadWhileScreenWouldBeOn() {
        val verdict = PowWatchdog.judge(
            processesAlive = true,
            txBytes = 10L,
            rxBytes = 20L,
            previousTx = 10L,
            previousRx = 20L,
            processMisses = 0,
            stallStrikes = 0,
        )
        assertEquals(PowWatchdog.Verdict.Healthy, verdict)
    }

    @Test
    fun screenOffIdleStretchesTheInterval() {
        assertEquals(
            PowWatchdog.INTERVAL_SCREEN_OFF_MS,
            PowWatchdog.intervalMs(screenOn = false, activeStreams = 0),
        )
    }

    @Test
    fun backgroundStreamingKeepsTheShortInterval() {
        assertEquals(PowWatchdog.INTERVAL_MS, PowWatchdog.intervalMs(screenOn = false, activeStreams = 2))
        assertEquals(PowWatchdog.INTERVAL_MS, PowWatchdog.intervalMs(screenOn = true, activeStreams = 0))
    }

    @Test
    fun processDeathNeedsTwoMisses() {
        val first = PowWatchdog.judge(
            processesAlive = false,
            txBytes = 10L,
            rxBytes = 20L,
            previousTx = 10L,
            previousRx = 20L,
            processMisses = 0,
            stallStrikes = 0,
        )
        assertTrue(first is PowWatchdog.Verdict.Strike)
        assertEquals(1, (first as PowWatchdog.Verdict.Strike).nextStrikes)

        val second = PowWatchdog.judge(
            processesAlive = false,
            txBytes = 10L,
            rxBytes = 20L,
            previousTx = 10L,
            previousRx = 20L,
            processMisses = 1,
            stallStrikes = 0,
        )
        assertTrue(second is PowWatchdog.Verdict.Dead)
        assertEquals("engine process exited", (second as PowWatchdog.Verdict.Dead).reason)
    }

    @Test
    fun txWithoutRxNeedsTwoStrikes() {
        val first = PowWatchdog.judge(
            processesAlive = true,
            txBytes = 80L,
            rxBytes = 20L,
            previousTx = 10L,
            previousRx = 20L,
            processMisses = 0,
            stallStrikes = 0,
        )
        assertTrue(first is PowWatchdog.Verdict.Strike)
        assertEquals(
            "the tunnel accepted traffic but answered nothing",
            (first as PowWatchdog.Verdict.Strike).reason,
        )

        val second = PowWatchdog.judge(
            processesAlive = true,
            txBytes = 80L,
            rxBytes = 20L,
            previousTx = 10L,
            previousRx = 20L,
            processMisses = 0,
            stallStrikes = 1,
        )
        assertTrue(second is PowWatchdog.Verdict.Dead)
    }
}