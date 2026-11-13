package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MciNativeXrayConfigTest {
    @Test
    fun retiredTelegramUploadRouteIsIgnored() {
        val config = MciNativeXrayConfig.build(
            settings = AdvancedSettingsData.DEFAULT.copy(telegramRouteEnabled = true),
        )

        assertTrue(config.contains("\"tag\":\"proxy\""))
        assertTrue(config.contains("\"tag\":\"socks-in\""))
        assertFalse(config.contains("telegram-upload"))
        assertFalse(config.contains("149.154.165.0/24"))
        assertFalse(config.contains("104.18.9.83"))
        assertTrue(config.contains("\"ip\":[\"::/0\"]"))
        assertFalse(config.contains("telegram-balance"))
        assertTrue(config.contains("\"fingerprint\":\"unsafe\""))
    }

    @Test
    fun adaptiveDnsUsesASequentialTwoResolverChainOnTheMainTunnel() {
        val config = MciNativeXrayConfig.build(
            settings = AdvancedSettingsData.DEFAULT.copy(
                dnsResolverUrl = AdaptiveDnsResolvers.QUAD9.url,
            ),
        )

        assertTrue(config.contains(AdaptiveDnsResolvers.QUAD9.url))
        assertTrue(config.contains(AdaptiveDnsResolvers.GOOGLE.url))
        assertFalse(config.contains(AdaptiveDnsResolvers.CLOUDFLARE.url))
        assertFalse(config.contains(AdaptiveDnsResolvers.ADGUARD.url))
        assertFalse(config.contains(AdaptiveDnsResolvers.OPENDNS.url))
        assertTrue(config.contains("\"enableParallelQuery\":false"))
        assertTrue(config.contains("\"inboundTag\":[\"dns-query\"],\"outboundTag\":\"proxy\""))
        assertFalse(config.contains("\"inboundTag\":[\"dns-query\"],\"outboundTag\":\"probe-proxy\""))
    }

    @Test
    fun hostnameEdgeResolvesOnThePhoneNotThroughTheProxy() {
        val config = MciNativeXrayConfig.build(
            edge = MciEdge("panel.example.com", 28000, "uac-direct-compat", 1),
        )

        assertTrue(
            config.contains(
                """"address":"localhost","domains":["full:panel.example.com"],"skipFallback":true""",
            ),
        )
        assertTrue(config.contains("\"inboundTag\":[\"dns-query\"],\"outboundTag\":\"proxy\""))
    }

    @Test
    fun privateLanIsDirectEvenWhenIranDirectIsOff() {
        val config = MciNativeXrayConfig.build(
            settings = AdvancedSettingsData.DEFAULT.copy(iranDirect = false),
        )
        val privateRule = config.indexOf("10.0.0.0/8")
        val dnsRule = config.indexOf("\"port\":\"53\"")

        assertTrue(config.contains("\"tag\":\"direct\""))
        assertTrue(config.contains("172.16.0.0/12"))
        assertTrue(config.contains("192.168.0.0/16"))
        assertTrue(config.contains("127.0.0.0/8"))
        assertTrue(privateRule >= 0 && dnsRule > privateRule)
        assertFalse(config.contains("geoip:private"))
    }

    @Test
    fun ipEdgeDoesNotPinADialHostResolver() {
        val config = MciNativeXrayConfig.build()

        assertFalse(config.contains("full:104.26.13.100"))
        assertFalse(config.contains("full:panel.example.com"))
    }
}
