package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
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

    @Test
    fun trojanWsAlpnSlashPairUsesHttp11FirstInRouteSpeedStyleConfig() {
        val profile = ProfileUriParser.parse(
            "trojan://secret@origin.example:443?security=tls&type=ws&host=cdn.example&path=%2Fws&sni=cdn.example&alpn=h2%2Fhttp1.1#Trojan",
        )
        val directCompat = MciXrayRuntimeOptions(
            identityOverride = profile.runtimeIdentity(AdvancedSettingsData.DEFAULT),
            finalmaskEnabled = false,
            preserveEmptyAlpn = true,
            preserveTransportFields = true,
        )
        val omitted = MciXrayCore.buildConfig(
            edge = MciConfig.PRIMARY_EDGE,
            profile = profile,
            runtimeOptions = directCompat.copy(identityOverride = profile.runtimeIdentity(AdvancedSettingsData.DEFAULT).copy(alpn = "")),
        )
        assertFalse(omitted.contains("\"alpn\""))

        val preserved = MciXrayCore.buildConfig(
            edge = MciConfig.PRIMARY_EDGE,
            profile = profile,
            runtimeOptions = directCompat,
        )
        assertTrue(preserved.contains("\"alpn\":[\"h2\",\"http/1.1\"]"))

        val explicit = MciXrayCore.buildConfig(
            edge = MciConfig.PRIMARY_EDGE,
            profile = profile,
            runtimeOptions = directCompat.copy(preserveEmptyAlpn = false),
        )
        assertTrue(explicit.contains("\"protocol\":\"trojan\""))
        assertTrue(explicit.contains("\"alpn\":[\"http/1.1\",\"h2\"]"))
    }

    @Test
    fun xhttpIdentityEmitsXhttpSettingsAndDisablesMux() {
        val profile = ProfileUriParser.parse(
            "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@origin.example:443" +
                "?encryption=none&security=tls&type=xhttp&host=cdn.example&path=%2Fxh" +
                "&sni=cdn.example&mode=packet-up&alpn=h2#XHTTP",
        )
        val config = MciXrayCore.buildConfig(
            edge = MciConfig.PRIMARY_EDGE,
            settings = AdvancedSettingsData.DEFAULT.copy(muxEnabled = true),
            profile = profile,
        )

        assertTrue(config.contains("\"network\":\"xhttp\""))
        assertTrue(config.contains("\"xhttpSettings\""))
        assertTrue(config.contains("\"path\":\"/xh\""))
        assertTrue(config.contains("\"mode\":\"packet-up\""))
        assertTrue(config.contains("\"alpn\":[\"h2\"]"))
        assertTrue(config.contains("\"mux\":{\"enabled\":false"))
        assertFalse(config.contains("\"httpupgradeSettings\""))
    }
}
