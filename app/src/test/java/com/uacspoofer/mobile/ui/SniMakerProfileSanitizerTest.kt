package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SniMakerProfileSanitizerTest {
    @Test
    fun stripsEveryRawCountryFlagFromDisplayName() {
        assertEquals("Berlin Node Premium", stripCountryFlags("🇩🇪 Berlin Node 🇫🇮 Premium"))
        assertEquals("Amsterdam", stripCountryFlags("[🇳🇱] Amsterdam"))
    }

    @Test
    fun preparedProfileStartsWithUnknownCountryAndCleanCanonicalName() {
        val prepared = prepareSniMakerProfile(profile("🇩🇪 Fast node", CountryMetadata.resolve("FI", null)))

        assertEquals("Fast node", prepared.name)
        assertEquals(CountryMetadata.UNKNOWN, prepared.country)
        assertFalse(ProfileUriParser.canonicalUri(prepared).contains("🇩🇪"))
    }

    @Test
    fun usesStableFallbackWhenRawNameIsOnlyAFlag() {
        val prepared = prepareSniMakerProfile(profile("🇩🇪", CountryMetadata.UNKNOWN))

        assertEquals("VLESS • node.example", prepared.name)
    }

    private fun profile(name: String, country: CountryMetadata) = ProxyProfile(
        id = "test-profile",
        name = name,
        protocol = ProxyProtocol.VLESS,
        credential = "00000000-0000-0000-0000-000000000000",
        serverHost = "127.0.0.1",
        serverPort = 40443,
        network = "ws",
        security = "tls",
        sni = "node.example",
        host = "node.example",
        path = "/ws",
        alpn = "http/1.1",
        fingerprint = "chrome",
        country = country,
    )
}
