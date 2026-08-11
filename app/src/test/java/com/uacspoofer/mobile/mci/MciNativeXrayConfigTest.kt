package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.settings.AdvancedSettingsData
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
}
