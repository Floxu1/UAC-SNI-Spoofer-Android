package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUriParserTest {
    @Test
    fun parsesTrojanWebSocketIdentity() {
        val profile = ProfileUriParser.parse(
            "trojan://secret@example.com:443?security=tls&type=ws&host=cdn.example.com&path=%2Fedge&sni=cdn.example.com#Fast%20node",
            id = "one",
        )

        assertEquals(ProxyProtocol.TROJAN, profile.protocol)
        assertEquals("secret", profile.credential)
        assertEquals("127.0.0.1", profile.serverHost)
        assertEquals(40443, profile.serverPort)
        assertEquals("cdn.example.com", profile.sni)
        assertEquals("/edge", profile.path)
        assertEquals("Fast node", profile.name)
    }

    @Test
    fun parsesVlessHttpUpgradeAndRoundTrips() {
        val uri = "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@origin.example:443?encryption=none&security=tls&type=httpupgrade&host=cdn.example&path=%2Fvpnhu&sni=cdn.example&fp=chrome#VLESS"
        val profile = ProfileUriParser.parse(uri, id = "two")
        val roundTrip = ProfileUriParser.parse(ProfileUriParser.canonicalUri(profile), id = "two")

        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("httpupgrade", profile.network)
        assertEquals("/vpnhu", profile.path)
        assertEquals(profile.copy(rawUri = ""), roundTrip.copy(rawUri = ""))
        assertEquals("127.0.0.1", profile.serverHost)
        assertEquals(40443, profile.serverPort)
    }

    @Test
    fun rejectsMissingCredentialAndUnsupportedSecurity() {
        val missing = runCatching { ProfileUriParser.parse("trojan://example.com:443?security=tls&type=ws") }
        val reality = runCatching {
            ProfileUriParser.parse("vless://30980fc4-8789-42df-80d1-0c8e5cd26881@example.com:443?security=reality&type=tcp")
        }

        assertTrue(missing.exceptionOrNull()?.message.orEmpty().contains("password", ignoreCase = true))
        assertTrue(reality.exceptionOrNull()?.message.orEmpty().contains("TLS", ignoreCase = true))
    }

    @Test
    fun acceptsDesktopBuiltinCompatibilityParameters() {
        val profile = ProfileUriParser.parse(
            "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@127.0.0.1:40443?path=%2Fvpnhu&security=tls&encryption=none&insecure=1&host=cdn.veilvpn.fans&fp=chrome&type=httpupgrade&allowInsecure=1&sni=cdn.veilvpn.fans#uacSpoofer%206",
        )

        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("httpupgrade", profile.network)
        assertTrue(profile.allowInsecure)
        assertEquals("cdn.veilvpn.fans", profile.host)
    }

    @Test
    fun countryMetadataIsResolvedAndPersistsInCanonicalUri() {
        val profile = ProfileUriParser.parse(
            "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@example.com:443?encryption=none&security=tls&type=ws&sni=cdn.example&cc=DE#Short",
            id = "country",
        )
        val restored = ProfileUriParser.parse(ProfileUriParser.canonicalUri(profile), id = "country")

        assertEquals("DE", profile.country.countryCode)
        assertEquals("Germany", profile.country.countryName)
        assertEquals(profile.country, restored.country)
    }

    @Test
    fun unknownCountryUsesNeutralMetadataAndLongNamesStayBounded() {
        val longName = "x".repeat(160)
        val profile = ProfileUriParser.parse(
            "trojan://secret@example.com:443?security=tls&type=ws&sni=cdn.example#$longName",
        )

        assertEquals(CountryMetadata.UNKNOWN, profile.country)
        assertEquals(80, profile.name.length)
    }

    @Test
    fun parsesVlessXhttpWithModeAndExtraAndRoundTrips() {
        val extra = """{"headers":{"X-Pad":"1"},"xmux":{"maxConcurrency":16}}"""
        val uri = "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@origin.example:443" +
            "?encryption=none&security=tls&type=xhttp&host=cdn.example&path=%2Fxh" +
            "&sni=cdn.example&fp=chrome&alpn=h2&mode=stream-up&packetEncoding=xudp" +
            "&extra=${java.net.URLEncoder.encode(extra, "UTF-8").replace("+", "%20")}#XHTTP"
        val profile = ProfileUriParser.parse(uri, id = "xhttp")
        val roundTrip = ProfileUriParser.parse(ProfileUriParser.canonicalUri(profile), id = "xhttp")

        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("xhttp", profile.network)
        assertEquals("/xh", profile.path)
        assertEquals("stream-up", profile.xhttpMode)
        assertEquals("xudp", profile.packetEncoding)
        assertEquals("h2", profile.alpn)
        assertTrue(profile.xhttpExtra.contains("\"maxConcurrency\":16"))
        assertEquals(profile.copy(rawUri = ""), roundTrip.copy(rawUri = ""))
    }

    @Test
    fun acceptsSplitHttpAliasAsXhttp() {
        val profile = ProfileUriParser.parse(
            "vless://30980fc4-8789-42df-80d1-0c8e5cd26881@origin.example:443" +
                "?encryption=none&security=tls&type=splithttp&sni=cdn.example&path=%2F#Split",
        )
        assertEquals("xhttp", profile.network)
        assertEquals("h2", profile.alpn)
    }
}
