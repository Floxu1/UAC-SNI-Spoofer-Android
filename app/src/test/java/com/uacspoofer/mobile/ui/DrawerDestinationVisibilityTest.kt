package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.EngineMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerDestinationVisibilityTest {
    @Test
    fun xrayKeepsMakerAndSpeedTest() {
        assertTrue(DrawerDestination.SNI_MAKER.visibleFor(EngineMode.XRAY_CF))
        assertTrue(DrawerDestination.ROUTE_SPEED_TEST.visibleFor(EngineMode.XRAY_CF))
        assertTrue(DrawerDestination.CONFIGS.visibleFor(EngineMode.XRAY_CF))
    }

    @Test
    fun torHidesMakerAndSpeedTestButKeepsConfigsSlot() {
        assertFalse(DrawerDestination.SNI_MAKER.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertFalse(DrawerDestination.ROUTE_SPEED_TEST.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.CONFIGS.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.HOME.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.SETTINGS.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.APP_BYPASS.visibleFor(EngineMode.TOR_WEBTUNNEL))
    }
}
