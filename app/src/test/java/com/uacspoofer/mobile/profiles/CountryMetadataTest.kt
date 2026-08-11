package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryMetadataTest {
    @Test
    fun resolvesValidatedIsoCountries() {
        assertEquals("Germany", CountryMetadata.resolve("DE", null).countryName)
        assertEquals("FI", CountryMetadata.resolve(null, "Finland").countryCode)
        assertEquals("NL", CountryMetadata.resolve("nl", null).countryCode)
    }

    @Test
    fun rejectsUnknownOrNonIsoCountryCodes() {
        assertFalse(CountryMetadata.resolve("ZZ", null).isKnown)
        assertFalse(CountryMetadata.resolve(null, "Unknown location").isKnown)
        assertTrue(CountryMetadata.resolve("DE", null).flagEmoji?.isNotBlank() == true)
    }
}
