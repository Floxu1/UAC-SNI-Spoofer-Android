package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MciXrayBatchConfigTest {
    @Test
    fun batchConfigKeepsRoutesIsolatedByInboundAndOutboundTag() {
        val routes = listOf(
            MciXrayBatchRoute(
                tag = "r0",
                edge = MciEdge("104.18.1.1", 443, "a", 2),
                settings = AdvancedSettingsData.DEFAULT.copy(socksPort = 12001),
            ),
            MciXrayBatchRoute(
                tag = "r1",
                edge = MciEdge("172.66.0.1", 443, "b", 100),
                settings = AdvancedSettingsData.DEFAULT.copy(
                    socksPort = 12002,
                    dnsResolverUrl = AdaptiveDnsResolvers.GOOGLE.url,
                    finalmaskDelayMs = 5,
                ),
            ),
        )

        val config = MciXrayConfigBuilder.buildBatch(routes, ProxyProfile.UAC_SNI_BUILT_IN)

        assertTrue(config.contains("\"tag\":\"in-r0\",\"listen\":\"127.0.0.1\",\"port\":12001"))
        assertTrue(config.contains("\"tag\":\"in-r1\",\"listen\":\"127.0.0.1\",\"port\":12002"))
        assertTrue(config.contains("\"tag\":\"out-r0\""))
        assertTrue(config.contains("\"tag\":\"out-r1\""))
        assertTrue(config.contains("\"inboundTag\":[\"in-r0\"],\"network\":\"tcp,udp\",\"outboundTag\":\"out-r0\""))
        assertTrue(config.contains("\"inboundTag\":[\"in-r1\"],\"network\":\"tcp,udp\",\"outboundTag\":\"out-r1\""))
        assertTrue(config.contains("\"loglevel\":\"warning\""))
        assertFalse(config.contains("\"dns\":"))
    }

    @Test
    fun batchConfigRejectsDuplicateSocksPorts() {
        val settings = AdvancedSettingsData.DEFAULT.copy(socksPort = 12001)
        val routes = listOf(
            MciXrayBatchRoute("r0", MciConfig.PRIMARY_EDGE, settings),
            MciXrayBatchRoute("r1", MciConfig.FALLBACK_EDGE, settings),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MciXrayConfigBuilder.buildBatch(routes, ProxyProfile.UAC_SNI_BUILT_IN)
        }
    }

    @Test
    fun proxyConfigIgnoresMtuButNativeTunConfigUsesIt() {
        val mtu1280 = AdvancedSettingsData.DEFAULT.copy(tunMtu = 1280)
        val mtu1400 = AdvancedSettingsData.DEFAULT.copy(tunMtu = 1400)

        assertEquals(
            MciXrayCore.buildConfig(settings = mtu1280),
            MciXrayCore.buildConfig(settings = mtu1400),
        )
        val native1280 = MciNativeXrayConfig.build(settings = mtu1280)
        val native1400 = MciNativeXrayConfig.build(settings = mtu1400)
        assertNotEquals(native1280, native1400)
        assertTrue(native1280.contains("\"MTU\":1280"))
        assertTrue(native1400.contains("\"MTU\":1400"))
    }
}
