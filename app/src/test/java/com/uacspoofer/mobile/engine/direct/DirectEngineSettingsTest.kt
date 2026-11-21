package com.uacspoofer.mobile.engine.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectEngineSettingsTest {
    @Test
    fun defaultsUseBalancedSearchAndAutomaticDns() {
        assertEquals(DirectScanMode.BALANCED, DirectEngineSettings.DEFAULT.scanMode)
        assertTrue(DirectEngineSettings.DEFAULT.autoSelectDns)
    }

    @Test
    fun storedModeTakesPrecedenceOverLegacySwitch() {
        assertEquals(DirectScanMode.BALANCED, DirectScanMode.fromStored(null))
        assertEquals(DirectScanMode.DEEP, DirectScanMode.fromStored(null, legacyRangeScan = true))
        assertEquals(DirectScanMode.TURBO, DirectScanMode.fromStored("turbo", legacyRangeScan = true))
    }
}
