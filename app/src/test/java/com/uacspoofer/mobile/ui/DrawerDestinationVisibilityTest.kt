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
        assertTrue(DrawerDestination.ADVANCED_SETTINGS.visibleFor(EngineMode.XRAY_CF))
        assertFalse(DrawerDestination.POW_SETTINGS.visibleFor(EngineMode.XRAY_CF))
        assertFalse(DrawerDestination.TOR_SETTINGS.visibleFor(EngineMode.XRAY_CF))
    }

    @Test
    fun torHidesMakerAndSpeedTestButKeepsConfigsSlot() {
        assertFalse(DrawerDestination.SNI_MAKER.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertFalse(DrawerDestination.ROUTE_SPEED_TEST.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.CONFIGS.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.HOME.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.SETTINGS.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.APP_BYPASS.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertTrue(DrawerDestination.TOR_SETTINGS.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertFalse(DrawerDestination.ADVANCED_SETTINGS.visibleFor(EngineMode.TOR_WEBTUNNEL))
        assertFalse(DrawerDestination.POW_SETTINGS.visibleFor(EngineMode.TOR_WEBTUNNEL))
    }

    @Test
    fun powHidesMakerAndSpeedTestLikeTor() {
        assertFalse(DrawerDestination.SNI_MAKER.visibleFor(EngineMode.UAC_POW))
        assertFalse(DrawerDestination.ROUTE_SPEED_TEST.visibleFor(EngineMode.UAC_POW))
        assertTrue(DrawerDestination.CONFIGS.visibleFor(EngineMode.UAC_POW))
        assertTrue(DrawerDestination.HOME.visibleFor(EngineMode.UAC_POW))
        assertTrue(DrawerDestination.SETTINGS.visibleFor(EngineMode.UAC_POW))
        assertTrue(DrawerDestination.POW_SETTINGS.visibleFor(EngineMode.UAC_POW))
        assertFalse(DrawerDestination.ADVANCED_SETTINGS.visibleFor(EngineMode.UAC_POW))
        assertFalse(DrawerDestination.TOR_SETTINGS.visibleFor(EngineMode.UAC_POW))
    }
}
