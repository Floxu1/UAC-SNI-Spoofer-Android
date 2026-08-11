package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProfileXrayConfigTest {
    @Test
    fun vlessIdentityUsesMciEdgeAndPolicyInBothBackends() {
        val profile = ProfileUriParser.parse(
            "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@origin.example:8443?encryption=none&security=tls&type=ws&host=cdn.example&path=%2Fcustom&sni=cdn.example#Mine",
        )
        val settings = AdvancedSettingsData.DEFAULT.copy(finalmaskLength = 9, primaryMaxSplit = 77)
        val executable = MciXrayCore.buildConfig(MciConfig.PRIMARY_EDGE.copy(finalmaskMaxSplit = 77), settings, profile)
        val native = MciNativeXrayConfig.build(MciConfig.PRIMARY_EDGE.copy(finalmaskMaxSplit = 77), settings, profile)

        for (config in listOf(executable, native)) {
            assertTrue(config.contains("\"protocol\":\"vless\""))
            assertTrue(config.contains("\"vnext\""))
            assertTrue(config.contains("30980fc4-8789-42df-80d1-0c8e5cd26881"))
            assertTrue(config.contains("\"address\":\"104.18.1.1\""))
            assertTrue(config.contains("\"serverName\":\"cdn.example\""))
            assertTrue(config.contains("\"path\":\"/custom\""))
            assertTrue(config.contains("\"length\":\"9\""))
            assertTrue(config.contains("\"maxSplit\":\"77\""))
            assertFalse(config.contains("humanity"))
            assertFalse(config.contains("www.ignitelimit.com"))
        }
    }

    @Test
    fun trojanIdentityDoesNotFallbackToBuiltInCredential() {
        val profile = ProfileUriParser.parse(
            "trojan://different-password@origin.example:443?security=tls&type=tcp&sni=tls.example#Trojan",
        )
        val config = MciXrayCore.buildConfig(profile = profile)

        assertTrue(config.contains("\"protocol\":\"trojan\""))
        assertTrue(config.contains("different-password"))
        assertTrue(config.contains("\"network\":\"tcp\""))
        assertFalse(config.contains("humanity"))
    }
}
