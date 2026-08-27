package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionConfigParserTest {
    private val vless = "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@edge.example:443?encryption=none&security=tls&type=ws&host=cdn.example&path=%2Fws&sni=cdn.example#One"
    private val trojan = "trojan://secret@edge2.example:443?security=tls&type=ws&host=cdn2.example&path=%2Ft&sni=cdn2.example#Two"

    @Test
    fun extractsUrisFromJunkAndNormalizesToLocalSniEndpoint() {
        val result = SubscriptionConfigParser.parse("header junk\n$vless some text\n$trojan\nfooter")

        assertEquals(2, result.profiles.size)
        assertTrue(result.profiles.all { it.serverHost == "127.0.0.1" && it.serverPort == 40443 })
        assertEquals(setOf(ProxyProtocol.VLESS, ProxyProtocol.TROJAN), result.profiles.map { it.protocol }.toSet())
    }

    @Test
    fun decodesWholeBase64Subscription() {
        val encoded = Base64Codec.encode("$vless\n$trojan".toByteArray(Charsets.UTF_8))
        val result = SubscriptionConfigParser.parse(encoded)

        assertEquals(2, result.profiles.size)
        assertTrue(result.decodedPayloadCount >= 1)
    }

    @Test
    fun coercesRealityAndHtmlEscapedParametersIntoSniModel() {
        val raw = "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@edge.example:443?security=reality&amp;type=xhttp&amp;sni=cdn.example&amp;pbk=ignored#Reality"
        val result = SubscriptionConfigParser.parse(raw)

        assertEquals(1, result.profiles.size)
        assertEquals("tls", result.profiles.single().security)
        assertEquals("xhttp", result.profiles.single().network)
        assertEquals("cdn.example", result.profiles.single().sni)
    }

    @Test
    fun capsLargeInputWithoutExpandingTheUiListForever() {
        val raw = (1..40).joinToString("\n") { index -> vless.replace("#One", "#Node$index") }
        val result = SubscriptionConfigParser.parse(raw, maxProfiles = 12)
        assertEquals(12, result.profiles.size)
        assertTrue(result.truncated)
    }
}
