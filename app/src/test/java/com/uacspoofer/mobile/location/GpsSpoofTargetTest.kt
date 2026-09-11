package com.uacspoofer.mobile.location

import com.uacspoofer.mobile.engine.EngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSpoofTargetTest {
    @Test
    fun xrayUsesProfileCountry() {
        assertEquals(
            "FI",
            GpsSpoofTarget.countryCode(
                engine = EngineMode.XRAY_CF,
                profileCountry = "fi",
                torExit = "de",
                powExit = "us",
                exitIpCountry = "NL",
            ),
        )
    }

    @Test
    fun torUsesSelectedExitThenIp() {
        assertEquals(
            "DE",
            GpsSpoofTarget.countryCode(
                engine = EngineMode.TOR_WEBTUNNEL,
                profileCountry = "FI",
                torExit = "de",
                powExit = "us",
                exitIpCountry = "NL",
            ),
        )
        assertEquals(
            "NL",
            GpsSpoofTarget.countryCode(
                engine = EngineMode.TOR_WEBTUNNEL,
                profileCountry = "FI",
                torExit = "",
                powExit = "us",
                exitIpCountry = "nl",
            ),
        )
    }

    @Test
    fun powUsesSelectedExitThenIp() {
        assertEquals(
            "US",
            GpsSpoofTarget.countryCode(
                engine = EngineMode.UAC_POW,
                profileCountry = "FI",
                torExit = "de",
                powExit = "us",
                exitIpCountry = "SG",
            ),
        )
        assertEquals(
            "SG",
            GpsSpoofTarget.countryCode(
                engine = EngineMode.UAC_POW,
                profileCountry = "FI",
                torExit = "de",
                powExit = "",
                exitIpCountry = "sg",
            ),
        )
    }

    @Test
    fun unknownOrMissingCountryStaysEmpty() {
        assertNull(
            GpsSpoofTarget.countryCode(
                engine = EngineMode.XRAY_CF,
                profileCountry = null,
                torExit = "",
                powExit = "",
                exitIpCountry = "Germany",
            ),
        )
        assertNull(GpsSpoofCoordinates.forCode("XX"))
        assertNull(GpsSpoofCoordinates.forCode(""))
    }

    @Test
    fun knownHubsStayOnTheExpectedContinent() {
        val frankfurt = GpsSpoofCoordinates.forCode("DE")
        val amsterdam = GpsSpoofCoordinates.forCode("nl")
        assertNotNull(frankfurt)
        assertNotNull(amsterdam)
        assertTrue(frankfurt!!.latitude in 47.0..55.0)
        assertTrue(frankfurt.longitude in 5.0..15.0)
        assertTrue(amsterdam!!.latitude in 50.0..54.0)
        assertTrue(amsterdam.longitude in 3.0..8.0)
        assertEquals("Germany", GpsSpoofTarget.displayName("de", persian = false))
    }
}
