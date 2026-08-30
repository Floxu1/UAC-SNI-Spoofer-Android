package com.uacspoofer.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeGuideTest {
    @Test
    fun firstLaunchShowsEngineGuide() {
        assertEquals(
            HomeGuideStep.Engine,
            HomeGuide.step(
                engineSeen = false,
                countrySeen = false,
                torMode = false,
                drawerOpen = false,
            ),
        )
    }

    @Test
    fun afterEngineGuideTorHomeShowsCountry() {
        assertEquals(
            HomeGuideStep.Country,
            HomeGuide.step(
                engineSeen = true,
                countrySeen = false,
                torMode = true,
                drawerOpen = false,
            ),
        )
    }

    @Test
    fun switchingToTorDuringEngineGuideGoesToCountry() {
        assertEquals(
            HomeGuideStep.Country,
            HomeGuide.step(
                engineSeen = false,
                countrySeen = false,
                torMode = true,
                drawerOpen = false,
            ),
        )
    }

    @Test
    fun firstLaunchAlreadyOnTorShowsCountry() {
        assertEquals(
            HomeGuideStep.Country,
            HomeGuide.step(
                engineSeen = false,
                countrySeen = false,
                torMode = true,
                drawerOpen = false,
            ),
        )
    }

    @Test
    fun countryGuideWaitsUntilTor() {
        assertNull(
            HomeGuide.step(
                engineSeen = true,
                countrySeen = false,
                torMode = false,
                drawerOpen = false,
            ),
        )
    }

    @Test
    fun seenGuidesDoNotReturn() {
        assertNull(
            HomeGuide.step(
                engineSeen = true,
                countrySeen = true,
                torMode = true,
                drawerOpen = false,
            ),
        )
    }

    @Test
    fun drawerHidesGuide() {
        assertNull(
            HomeGuide.step(
                engineSeen = false,
                countrySeen = false,
                torMode = false,
                drawerOpen = true,
            ),
        )
    }
}
