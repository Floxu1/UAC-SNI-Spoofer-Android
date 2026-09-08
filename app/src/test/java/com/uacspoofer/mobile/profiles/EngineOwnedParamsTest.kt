package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subscriptions in the wild carry a fixed cipher list (`cs`) and a fixed fragment layout
 * (`fm`). The parser used to reject any unknown key, which threw away otherwise valid
 * configs, so these are now accepted and left to the engine.
 */
class EngineOwnedParamsTest {
    private val trojanWithCsAndFm =
        "trojan://humanity@1.2.3.4:8443?security=tls&type=ws&host=www.pleadcourt.org" +
            "&path=%2Fassignment&sni=www.pleadcourt.org&alpn=http%2F1.1&fp=unsafe" +
            "&cs=TLS_AES_256_GCM_SHA384%3ATLS_CHACHA20_POLY1305_SHA256" +
            "&fm=%7B%22tcp%22%3A%20%5B%7B%22type%22%3A%20%22fragment%22%7D%5D%7D" +
            "#ES%20%7C%208FF61D"

    @Test
    fun trojanWithCipherListAndFragmentSpecIsAccepted() {
        val profile = ProfileUriParser.parse(trojanWithCsAndFm)

        assertEquals(ProxyProtocol.TROJAN, profile.protocol)
        assertEquals("humanity", profile.credential)
        assertEquals("ws", profile.network)
        assertEquals("www.pleadcourt.org", profile.sni)
        assertEquals("www.pleadcourt.org", profile.host)
        assertEquals("/assignment", profile.path)
        assertTrue(profile.alpn.contains("http/1.1"))
        assertEquals("ES | 8FF61D", profile.name)
    }

    @Test
    fun unknownFingerprintFallsBackToAWorkingUtlsProfile() {
        val profile = ProfileUriParser.parse(trojanWithCsAndFm)

        // `unsafe` is not a uTLS profile Xray-core accepts; shipping it verbatim breaks TLS
        assertEquals("chrome", profile.fingerprint)
    }

    @Test
    fun theSameParametersAreAcceptedOnVlessAndXhttp() {
        val vless = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:2086" +
            "?security=tls&type=xhttp&mode=auto&sni=a.example.com&host=a.example.com" +
            "&path=%2Fx&alpn=h2&fp=unsafe&cs=TLS_AES_256_GCM_SHA384&fm=%7B%7D#XH"

        val profile = ProfileUriParser.parse(vless)

        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertTrue(ProfileNetworks.isXhttp(profile.network))
        assertEquals("chrome", profile.fingerprint)
    }

    @Test
    fun aTrulyUnknownParameterIsStillRejected() {
        val bogus = "trojan://p@1.2.3.4:443?security=tls&type=ws&host=h&totallyUnknownKey=1"

        val failure = runCatching { ProfileUriParser.parse(bogus) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
