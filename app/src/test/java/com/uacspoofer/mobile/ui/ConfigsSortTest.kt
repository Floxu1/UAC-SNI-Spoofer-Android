package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.CountryMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigsSortTest {
    private val profiles = listOf(
        profile("a"),
        profile("b"),
        profile("c"),
        profile("d"),
    )

    @Test
    fun defaultOrderIsPreserved() {
        val sorted = sortProfilesByLatency(
            profiles,
            mapOf("a" to 400L, "b" to 100L),
            ConfigLatencySort.DEFAULT,
        )

        assertEquals(listOf("a", "b", "c", "d"), sorted.map(ProxyProfile::id))
    }

    @Test
    fun ascendingPlacesMeasuredProfilesFirstAndKeepsUnknownStable() {
        val sorted = sortProfilesByLatency(
            profiles,
            mapOf("a" to 400L, "b" to 100L, "d" to 250L),
            ConfigLatencySort.LATENCY_ASC,
        )

        assertEquals(listOf("b", "d", "a", "c"), sorted.map(ProxyProfile::id))
    }

    @Test
    fun descendingPlacesMeasuredProfilesFirstAndKeepsUnknownStable() {
        val sorted = sortProfilesByLatency(
            profiles,
            mapOf("a" to 400L, "b" to 100L, "d" to 250L),
            ConfigLatencySort.LATENCY_DESC,
        )

        assertEquals(listOf("a", "d", "b", "c"), sorted.map(ProxyProfile::id))
    }

    @Test
    fun countrySortGroupsKnownCountriesAndUsesLatencyInsideEachCountry() {
        val sorted = sortProfilesByLatency(
            profiles,
            mapOf("a" to 300L, "b" to 100L, "c" to 200L),
            ConfigLatencySort.COUNTRY_ASC,
            mapOf(
                "a" to CountryMetadata.resolve("DE", null),
                "b" to CountryMetadata.resolve("US", null),
                "c" to CountryMetadata.resolve("DE", null),
            ),
        )

        assertEquals(listOf("c", "a", "b", "d"), sorted.map(ProxyProfile::id))
    }

    private fun profile(id: String): ProxyProfile = ProxyProfile.MCI_BUILT_IN.copy(
        id = id,
        name = id,
        isBuiltIn = false,
    )
}
