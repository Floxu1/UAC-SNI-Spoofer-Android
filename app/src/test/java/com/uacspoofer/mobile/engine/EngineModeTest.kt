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
    }

    @Test
    fun engineFlagsAreExclusive() {
        assertTrue(EngineMode.XRAY_CF.isXray)
        assertFalse(EngineMode.XRAY_CF.isTor)
        assertTrue(EngineMode.TOR_WEBTUNNEL.isTor)
        assertFalse(EngineMode.TOR_WEBTUNNEL.isXray)
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
    fun toggledSwapsXrayAndTor() {
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.XRAY_CF.toggled())
        assertEquals(EngineMode.XRAY_CF, EngineMode.TOR_WEBTUNNEL.toggled())
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
    }
}
