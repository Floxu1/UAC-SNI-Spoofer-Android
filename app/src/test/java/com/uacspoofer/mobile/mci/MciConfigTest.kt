package com.uacspoofer.mobile.mci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MciConfigTest {
    @Test
    fun protectedWindowsConstantsArePinned() {
        assertEquals("104.18.1.1", MciConfig.PRIMARY_EDGE.address)
        assertEquals("172.66.0.1", MciConfig.FALLBACK_EDGE.address)
        assertEquals(100, MciConfig.IRANCELL_EDGE.finalmaskMaxSplit)
        assertEquals(443, MciConfig.PRIMARY_EDGE.port)
        assertEquals("www.speedtest.net", MciConfig.PATTERN_FAKE_SNI)
        assertEquals("www.ignitelimit.com", MciConfig.TLS_SERVER_NAME)
        assertEquals("/assignment", MciConfig.WEBSOCKET_PATH)
        assertEquals("humanity", MciConfig.TROJAN_PASSWORD)
    }

    @Test
    fun xrayConfigMatchesWindowsBuiltinMciOutbound() {
        val config = MciXrayCore.buildConfig()

        assertTrue(config.contains("\"address\":\"104.18.1.1\""))
        assertTrue(config.contains("\"serverName\":\"www.ignitelimit.com\""))
        assertTrue(config.contains("\"path\":\"/assignment\""))
        assertTrue(config.contains("\"finalmask\""))
        assertTrue(config.contains("\"packets\":\"tlshello\""))
        assertTrue(config.contains("\"length\":\"5\""))
        assertTrue(config.contains("\"delay\":\"0\""))
        assertTrue(config.contains("\"maxSplit\":\"2\""))
        assertTrue(MciXrayCore.buildConfig(MciConfig.IRANCELL_EDGE).contains("\"maxSplit\":\"100\""))
        assertEquals("tlshello", MciFragmenter.finalMask.packet)
        assertEquals(5, MciFragmenter.finalMask.length)
        assertEquals(0, MciFragmenter.finalMask.delayMs)
        assertEquals(2, MciFragmenter.finalMask.maxSplit)
        assertFalse(config.contains("\"network\":\"udp\",\"port\":\"443\""))
        assertFalse(config.contains(MciConfig.PATTERN_FAKE_SNI))
    }
}


