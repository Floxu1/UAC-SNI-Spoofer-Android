package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.tor.TorPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorStatusCopyTest {
    @Test
    fun persianBootstrapKeepsTorAndPercentAsOneLtrRun() {
        val hint = TorStatusCopy.bootstrapHint(20, persian = true)
        val isolated = isolateUnwrappedLtrRuns(hint.replace("\u200C", "\u2060\u200C\u2060"))
        assertTrue(isolated.contains("راه‌اندازی") || isolated.contains("راه\u2060\u200C\u2060اندازی"))
        assertTrue(isolated.contains("\u2066Tor 20%\u2069"))
        assertFalse(isolated.contains("٪"))
    }

    @Test
    fun persianDoesNotDumpEnglishBridgeScan() {
        val hint = TorStatusCopy.connectingHint(
            persian = true,
            percent = 0,
            phase = TorPhase.BRIDGING,
            detail = "Checking WebTunnel bridges",
            showRouteProgress = false,
        )
        assertFalse(hint.contains("Checking"))
        assertTrue(hint.contains("WebTunnel"))
        assertTrue(hint.contains("بریج"))
    }

    @Test
    fun risingHeapIsFlaggedAsGrowth() {
        val history = (0 until 30).map { 40L * 1024L * 1024L + it * 2L * 1024L * 1024L }
        val slope = RuntimeDiagnosticsSampler.slopeMbPerMin(history)
        assertTrue(slope > RuntimeDiagnosticsSampler.LEAK_SLOPE_MB_PER_MIN)
    }
}