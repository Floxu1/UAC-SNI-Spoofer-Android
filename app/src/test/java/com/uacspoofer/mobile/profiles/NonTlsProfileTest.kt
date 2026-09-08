package com.uacspoofer.mobile.profiles

import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.mci.MciNativeXrayConfig
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain configs carry no TLS layer and hide behind an `http` disguise on raw TCP. They used to
 * be rejected outright, which threw away whole subscriptions.
 */
class NonTlsProfileTest {
    private val plainVless =
        "vless://66e8e94f-fef3-5517-2b8e-686b107fbb3e@162.159.198.1:2050?encryption=none" +
            "&security=none&type=tcp&headerType=http&host=knox.cdn-apple.com" +
            "&path=%2FJoin-JavidnamanIran-on-Telegram#SE"

    @Test
    fun plainTcpWithHttpDisguiseIsAccepted() {
        val profile = ProfileUriParser.parse(plainVless)

        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("none", profile.security)
        assertEquals("tcp", profile.network)
        assertEquals("http", profile.headerType)
        assertEquals("knox.cdn-apple.com", profile.host)
        assertEquals("/Join-JavidnamanIran-on-Telegram", profile.path)
    }

    @Test
    fun theDisguiseSurvivesASaveAndReload() {
        val profile = ProfileUriParser.parse(plainVless)

        val reloaded = ProfileUriParser.parse(ProfileUriParser.canonicalUri(profile))

        assertEquals("none", reloaded.security)
        assertEquals("http", reloaded.headerType)
        assertEquals(profile.host, reloaded.host)
        assertEquals(profile.path, reloaded.path)
    }

    @Test
    fun aPlainConfigEmitsNoTlsLayerAndNoFragmenter() {
        val profile = ProfileUriParser.parse(plainVless)

        val config = MciNativeXrayConfig.build(
            edge = MciConfig.PRIMARY_EDGE,
            settings = AdvancedSettingsData.DEFAULT,
            profile = profile,
        )

        assertTrue(config.contains("\"security\":\"none\""))
        assertFalse(config.contains("tlsSettings"))
        // fragmentation splits a TLS ClientHello, which a plain config never sends
        assertFalse(config.contains("finalmask"))
    }

    @Test
    fun theHttpDisguiseCarriesTheFrontedHostAndPath() {
        val profile = ProfileUriParser.parse(plainVless)

        val config = MciNativeXrayConfig.build(
            edge = MciConfig.PRIMARY_EDGE,
            settings = AdvancedSettingsData.DEFAULT,
            profile = profile,
        )

        assertTrue(config.contains("\"type\":\"http\""))
        assertTrue(config.contains("\"Host\":[\"knox.cdn-apple.com\"]"))
        assertTrue(config.contains("\"path\":[\"/Join-JavidnamanIran-on-Telegram\"]"))
    }

    @Test
    fun tlsConfigsAreCompletelyUnaffected() {
        val tls = "trojan://pw@1.2.3.4:443?security=tls&type=ws&host=a.example.com" +
            "&path=%2Fx&sni=a.example.com&alpn=http%2F1.1&fp=chrome#TLS"

        val profile = ProfileUriParser.parse(tls)
        val config = MciNativeXrayConfig.build(
            edge = MciConfig.PRIMARY_EDGE,
            settings = AdvancedSettingsData.DEFAULT,
            profile = profile,
        )

        assertEquals("tls", profile.security)
        assertEquals("", profile.headerType)
        assertTrue(config.contains("tlsSettings"))
        assertTrue(config.contains("finalmask"))
    }

    @Test
    fun realitySecurityIsStillRejected() {
        val reality = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
            "?security=reality&type=tcp&sni=a.example.com#R"

        val failure = runCatching { ProfileUriParser.parse(reality) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun httpDisguiseIsRefusedOnTransportsThatCannotCarryIt() {
        val wsWithHttpHeader = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
            "?security=none&type=ws&headerType=http&host=a.example.com#W"

        val failure = runCatching { ProfileUriParser.parse(wsWithHttpHeader) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun hysteria2IsReportedAsUnsupportedRatherThanSilentlyBroken() {
        val hysteria = "hysteria2://key@crm.example.org:989?sni=crm.example.org&obfs=salamander#HY"

        val failure = runCatching { ProfileUriParser.parse(hysteria) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
