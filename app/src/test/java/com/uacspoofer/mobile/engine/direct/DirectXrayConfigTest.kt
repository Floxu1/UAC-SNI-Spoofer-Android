package com.uacspoofer.mobile.engine.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectXrayConfigTest {
    private fun build(s: DirectEngineSettings = DirectEngineSettings.DEFAULT, tun: Boolean = true) =
        DirectXrayConfig.build(s, "127.0.0.1", 10808, 1280, nativeTun = tun)

    @Test fun defaultsUseVariantAHelloSplit() {
        val c = build()
        assertTrue(c.contains("\"packets\":\"tlshello\",\"lengths\":[\"6\",\"98\",\"1\"]"))
        assertTrue(c.contains("\"lengths\":[\"114\",\"1\"],\"delays\":[\"1\"],\"maxSplit\":\"11\""))
        assertTrue(c.contains("\"maxSplit\":\"201\""))
    }

    @Test fun variantBSwapsFirstStage() {
        val c = build(DirectEngineSettings.DEFAULT.withVariant(DirectEngineSettings.VARIANT_B))
        assertTrue(c.contains("\"lengths\":[\"0\",\"104\",\"1\"]"))
    }

    @Test fun hasNoServerOutbound() {
        val c = build()
        listOf("vless", "vmess", "trojan", "shadowsocks", "wireguard", "\"servers\":[{\"address\":\"1").forEach {
            assertFalse(it, c.contains("\"protocol\":\"$it\""))
        }
        assertTrue(c.contains("\"protocol\":\"freedom\""))
    }

    @Test fun noiseToggleAndNoGeoFiles() {
        assertTrue(build().contains("\"type\":\"noise\""))
        assertFalse(build(DirectEngineSettings.DEFAULT.copy(udpNoise = false)).contains("\"type\":\"noise\""))
        assertFalse(build().contains("geoip:"))
        assertFalse(build().contains("geosite:"))
    }

    @Test fun tunInboundOnlyInTunnelMode() {
        assertTrue(build(tun = true).contains("\"protocol\":\"tun\""))
        assertFalse(build(tun = false).contains("\"protocol\":\"tun\""))
    }

    @Test fun invalidListsFallBack() {
        assertEquals("6,98,1", DirectEngineSettings.cleanList("abc", "6,98,1"))
        assertEquals("6,98,1", DirectEngineSettings.cleanList("6 98 1", "x"))
        assertEquals(DirectEngineSettings.DEFAULT_UNFILTERED_DNS, DirectEngineSettings.DEFAULT.copy(unfilteredDns = "junk").validated().unfilteredDns)
    }
}
