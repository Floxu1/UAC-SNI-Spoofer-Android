package com.uacspoofer.mobile.mci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MciConfigTest {
    @Test
    fun protectedWindowsConstantsArePinned() {
        assertEquals("104.26.13.100", MciConfig.PRIMARY_EDGE.address)
        assertEquals("104.26.14.200", MciConfig.FALLBACK_EDGE.address)
        assertEquals(100, MciConfig.IRANCELL_EDGE.finalmaskMaxSplit)
        assertEquals(443, MciConfig.PRIMARY_EDGE.port)
        assertEquals("www.speedtest.net", MciConfig.PATTERN_FAKE_SNI)
        assertEquals("jp3.yohototo.top", MciConfig.TLS_SERVER_NAME)
        assertEquals("/tyxyws", MciConfig.WEBSOCKET_PATH)
        assertEquals("4e4f1f70-5e56-4a76-bb9d-db087f4690c4", MciConfig.TROJAN_PASSWORD)
    }

    @Test
    fun xrayConfigMatchesWindowsBuiltinMciOutbound() {
        val config = MciXrayCore.buildConfig()

        assertTrue(config.contains("\"address\":\"104.26.13.100\""))
        assertTrue(config.contains("\"serverName\":\"jp3.yohototo.top\""))
        assertTrue(config.contains("\"protocol\":\"vless\""))
        assertTrue(config.contains("\"fingerprint\":\"unsafe\""))
        assertTrue(config.contains("\"cipherSuites\":"))
        assertTrue(config.contains("\"path\":\"/tyxyws\""))
        assertTrue(config.contains("\"lengths\":[\"0\",\"104\",\"1\"]"))
        assertTrue(config.contains("\"packets\":\"tlshello\""))
        assertTrue(config.contains("\"maxSplit\":\"0\""))
        assertTrue(MciXrayCore.buildConfig(MciConfig.IRANCELL_EDGE).contains("\"address\":\"104.26.14.40\""))
        assertEquals("tlshello", MciFragmenter.finalMask.packet)
        assertEquals(5, MciFragmenter.finalMask.length)
        assertEquals(0, MciFragmenter.finalMask.delayMs)
        assertEquals(2, MciFragmenter.finalMask.maxSplit)
        assertFalse(config.contains("\"network\":\"udp\",\"port\":\"443\""))
        assertFalse(config.contains(MciConfig.PATTERN_FAKE_SNI))
    }
}


