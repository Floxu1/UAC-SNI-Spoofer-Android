package com.uacspoofer.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSpeedTestProfileTest {
    @Test
    fun usesActiveProfileWhileConnected() {
        assertEquals(
            "active",
            resolveRouteSpeedTestProfileId(
                selectedId = "selected",
                profileIds = listOf("selected", "active"),
                connected = true,
                activeProfileId = "active",
            ),
        )
    }

    @Test
    fun usesSelectedProfileWhileDisconnected() {
        assertEquals(
            "selected",
            resolveRouteSpeedTestProfileId(
                selectedId = "selected",
                profileIds = listOf("selected", "active"),
                connected = false,
                activeProfileId = "active",
            ),
        )
    }

    @Test
    fun fallsBackToSelectedIfActiveIsMissing() {
        assertEquals(
            "selected",
            resolveRouteSpeedTestProfileId(
                selectedId = "selected",
                profileIds = listOf("selected"),
                connected = true,
                activeProfileId = "gone",
            ),
        )
    }
}
