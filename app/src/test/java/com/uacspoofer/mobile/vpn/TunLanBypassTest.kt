package com.uacspoofer.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunLanBypassTest {
    @Test
    fun fullTunnelKeepsPublicAddressesAndDropsPrivateRanges() {
        assertEquals(TunLanBypass.PUBLIC_ROUTES, TunLanBypass.routesFor("0.0.0.0/0"))
        assertTrue(TunLanBypass.covers("1.1.1.1"))
        assertTrue(TunLanBypass.covers("8.8.8.8"))
        assertTrue(TunLanBypass.covers("100.64.1.1"))
        assertTrue(TunLanBypass.covers("198.18.0.1"))
        assertFalse(TunLanBypass.covers("10.1.2.3"))
        assertFalse(TunLanBypass.covers("127.0.0.1"))
        assertFalse(TunLanBypass.covers("172.16.5.5"))
        assertFalse(TunLanBypass.covers("172.31.255.255"))
        assertFalse(TunLanBypass.covers("192.168.1.1"))
        assertTrue(TunLanBypass.covers("172.15.255.255"))
        assertTrue(TunLanBypass.covers("172.32.0.1"))
        assertTrue(TunLanBypass.covers("192.167.255.255"))
        assertTrue(TunLanBypass.covers("192.169.0.1"))
    }

    @Test
    fun customRouteInsideAPrivateRangeIsNotInstalled() {
        assertEquals(emptyList<Pair<String, Int>>(), TunLanBypass.routesFor("10.1.2.0/24"))
        assertEquals(emptyList<Pair<String, Int>>(), TunLanBypass.routesFor("172.16.0.0/12"))
        assertEquals(emptyList<Pair<String, Int>>(), TunLanBypass.routesFor("192.168.1.0/24"))
        assertEquals(emptyList<Pair<String, Int>>(), TunLanBypass.routesFor("127.0.0.0/8"))
        assertEquals(listOf("1.2.3.0" to 24), TunLanBypass.routesFor("1.2.3.0/24"))
    }
}
