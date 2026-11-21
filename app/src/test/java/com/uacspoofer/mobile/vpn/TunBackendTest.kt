package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.mci.MciNativeXrayConfig
import com.uacspoofer.mobile.settings.TunBackend
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunBackendTest {
    @Test
    fun unknownOrMissingPreferenceUsesStableBackend() {
        assertEquals(TunBackend.HEV, TunBackend.fromId(null))
        assertEquals(TunBackend.HEV, TunBackend.fromId("typo"))
        TunBackend.entries.forEach { assertEquals(it, TunBackend.fromId(it.id)) }
    }

    @Test
    fun zeptunKeepsFakeDnsSeparateFromUpstreamUdpCapability() {
        val tor = JSONObject(SocksTunRelay.Config(9050, 1280, udp = false, fakeDns = "198.18.0.2").zeptunJson(17))
        assertEquals("mobile", tor.getString("preset"))
        assertEquals(17, tor.getJSONObject("tun").getInt("fd"))
        assertFalse(tor.getJSONObject("tun").getBoolean("configure"))
        assertEquals("epoll", tor.getJSONObject("io").getString("backend"))
        assertFalse(tor.getJSONObject("handler").getJSONObject("socks5").getBoolean("udp"))
        val dns = tor.getJSONObject("dns")
        assertEquals("198.18.0.2", dns.getJSONArray("address").getString(0))
        assertEquals("100.64.0.0/10", dns.getJSONArray("fake_ranges").getString(0))
        assertFalse(dns.has("upstream"))

        val xray = JSONObject(SocksTunRelay.Config(23100, 1400, udp = true).zeptunJson(18))
        assertTrue(xray.getJSONObject("handler").getJSONObject("socks5").getBoolean("udp"))
        assertFalse(xray.has("dns"))
    }

    @Test
    fun xrayKeepsRoutingAndStatsWhenDeviceInboundUsesSocks() {
        val stable = JSONObject(MciNativeXrayConfig.build())
        val zeptun = JSONObject(MciNativeXrayConfig.build(socksTunPort = 23100))
        assertEquals(stable.getJSONObject("routing").toString(), zeptun.getJSONObject("routing").toString())
        assertEquals(stable.getJSONObject("policy").toString(), zeptun.getJSONObject("policy").toString())
        val stableIn = stable.getJSONArray("inbounds")
        val zeptunIn = zeptun.getJSONArray("inbounds")
        assertEquals(stableIn.getJSONObject(0).toString(), zeptunIn.getJSONObject(0).toString())
        assertEquals("tun", stableIn.getJSONObject(1).getString("protocol"))
        val device = zeptunIn.getJSONObject(1)
        assertEquals("tun-in", device.getString("tag"))
        assertEquals("socks", device.getString("protocol"))
        assertEquals("127.0.0.1", device.getString("listen"))
        assertTrue(device.getJSONObject("settings").getBoolean("udp"))
    }
}
