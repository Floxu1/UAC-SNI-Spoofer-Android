package com.uacspoofer.mobile.engine

import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.vpn.ExitIpInfoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineModeTest {
    @Test
    fun unknownStoredValueFallsBackToXray() {
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored(null))
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored(""))
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored("cloudflare"))
        assertEquals(EngineMode.XRAY_CF, EngineMode.fromStored("xray_cf"))
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.fromStored("tor_webtunnel"))
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.fromStored("TOR_WEBTUNNEL"))
        assertEquals(EngineMode.UAC_POW, EngineMode.fromStored("uac_pow"))
        assertEquals(EngineMode.UAC_POW, EngineMode.fromStored("UAC_POW"))
    }

    @Test
    fun engineFlagsAreExclusive() {
        assertTrue(EngineMode.XRAY_CF.isXray)
        assertFalse(EngineMode.XRAY_CF.isTor)
        assertFalse(EngineMode.XRAY_CF.isPow)
        assertTrue(EngineMode.TOR_WEBTUNNEL.isTor)
        assertFalse(EngineMode.TOR_WEBTUNNEL.isXray)
        assertFalse(EngineMode.TOR_WEBTUNNEL.isPow)
        assertTrue(EngineMode.UAC_POW.isPow)
        assertFalse(EngineMode.UAC_POW.isXray)
        assertFalse(EngineMode.UAC_POW.isTor)
    }

    @Test
    fun engineCanChangeOnlyWhenIdle() {
        assertTrue(canChangeEngineMode(ConnectionState.DISCONNECTED))
        assertTrue(canChangeEngineMode(ConnectionState.ERROR))
        assertFalse(canChangeEngineMode(ConnectionState.CONNECTING))
        assertFalse(canChangeEngineMode(ConnectionState.CONNECTED))
        assertFalse(canChangeEngineMode(ConnectionState.DISCONNECTING))
    }

    @Test
    fun toggledCyclesXrayTorAndPow() {
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.XRAY_CF.toggled())
        assertEquals(EngineMode.UAC_POW, EngineMode.TOR_WEBTUNNEL.toggled())
        assertEquals(EngineMode.UAC_DIRECT, EngineMode.UAC_POW.toggled())
        assertEquals(EngineMode.XRAY_CF, EngineMode.UAC_DIRECT.toggled())
        assertEquals(EngineMode.UAC_DIRECT, EngineMode.fromStored("uac_direct"))
        assertEquals(EngineMode.UAC_DIRECT, EngineMode.fromStored("act_hitch"))
        assertTrue(EngineMode.UAC_DIRECT.isDirect)
        assertFalse(EngineMode.UAC_DIRECT.isXray)
    }

    @Test
    fun engine4HiddenFromVisibleListUntilUnlocked() {
        assertEquals(
            listOf(EngineMode.XRAY_CF, EngineMode.TOR_WEBTUNNEL, EngineMode.UAC_POW),
            EngineMode.visible(engine4Unlocked = false),
        )
        assertEquals(EngineMode.entries.toList(), EngineMode.visible(engine4Unlocked = true))
        assertEquals(EngineMode.XRAY_CF, EngineMode.UAC_POW.next(engine4Unlocked = false))
        assertEquals(EngineMode.UAC_DIRECT, EngineMode.UAC_POW.next(engine4Unlocked = true))
    }

    @Test
    fun torExitLookupDoesNotReuseXrayProfileCache() {
        assertEquals(
            "${ExitIpInfoRepository.TOR_LOOKUP_ID}:auto",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = true),
        )
        assertEquals(
            "${ExitIpInfoRepository.TOR_LOOKUP_ID}:de",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = true, "DE"),
        )
        assertEquals("builtin:mci", ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false))
        assertEquals(
            "${ExitIpInfoRepository.POW_LOOKUP_ID}:auto",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false, powEngine = true),
        )
    }
}
