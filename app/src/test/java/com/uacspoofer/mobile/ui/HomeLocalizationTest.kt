package com.uacspoofer.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLocalizationTest {
    @Test
    fun persianPrefixForcesRtlAndKeepsEnglishIslands() {
        val mixed = localizePersian("پرش TLS در WARP")
        assertTrue(mixed.startsWith("\u200F"))
        assertTrue(mixed.contains("\u2068TLS\u2069"))
        assertTrue(mixed.contains("\u2068WARP\u2069"))
        val tlsAt = mixed.indexOf("\u2068TLS\u2069")
        val warpAt = mixed.indexOf("\u2068WARP\u2069")
        val porshAt = mixed.indexOf("پرش")
        assertTrue(porshAt in 1 until tlsAt)
        assertTrue(tlsAt < mixed.indexOf("در"))
        assertTrue(mixed.indexOf("در") < warpAt)
    }

    @Test
    fun adjacentEnglishStaysOneIsland() {
        val mixed = localizePersian("پرش WARP TLS")
        assertTrue(mixed.contains("\u2068WARP TLS\u2069"))
        assertFalse(mixed.contains("\u2068WARP\u2069"))
    }

    @Test
    fun sentencePeriodIsNotSwallowedByEnglish() {
        val mixed = localizePersian("از اتصال بعدی UAC PoW اعمال می‌شود.")
        assertTrue(mixed.contains("\u2068UAC PoW\u2069"))
        assertTrue(mixed.endsWith("."))
        assertFalse(mixed.contains("\u2068UAC PoW.\u2069"))
    }

    @Test
    fun isolateDoesNotInventQuotes() {
        val mixed = localizePersian("تکه کردن پرش MASQUE")
        assertFalse(mixed.contains("`"))
        assertFalse(mixed.contains("\""))
        assertEquals(mixed.indexOf("تکه"), 1)
    }
}
