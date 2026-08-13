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
        assertFalse(config.contains("unsafe"))
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
}
