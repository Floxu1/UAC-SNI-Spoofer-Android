package com.uacspoofer.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutingPlanTest {
    private val routing = AppRoutingPreferences
    private val own = "com.uacspoofer.mobile"

    @Test
    fun allAppsOnlyExcludesTheVpnApp() {
        val plan = routing.planFor(own, AppRoutingSettings(AppRoutingMode.ALL_APPS, setOf("com.instagram.android")))
        assertNull(plan.allowed)
        assertEquals(listOf(own), plan.disallowed)
    }

    @Test
    fun bypassPutsOwnAppAndSelectionOnTheDisallowedList() {
        val plan = routing.planFor(
            own,
            AppRoutingSettings(
                AppRoutingMode.BYPASS_SELECTED,
                setOf("com.instagram.android", own, "  ", "com.telegram.messenger"),
            ),
        )
        assertNull(plan.allowed)
        assertEquals(listOf(own, "com.instagram.android", "com.telegram.messenger"), plan.disallowed)
    }

    @Test
    fun vpnOnlyUsesAllowedListAndNeverMixesDisallowed() {
        val plan = routing.planFor(
            own,
            AppRoutingSettings(AppRoutingMode.VPN_ONLY_SELECTED, setOf("org.mozilla.firefox", own)),
        )
        assertEquals(listOf("org.mozilla.firefox"), plan.allowed)
        assertNull(plan.disallowed)
    }

    @Test
    fun emptyVpnOnlyFallsBackToExcludingTheVpnApp() {
        val plan = routing.planFor(own, AppRoutingSettings(AppRoutingMode.VPN_ONLY_SELECTED, emptySet()))
        assertNull(plan.allowed)
        assertEquals(listOf(own), plan.disallowed)
    }

    @Test
    fun planNeverMixesAllowedAndDisallowed() {
        val settings = listOf(
            AppRoutingSettings(AppRoutingMode.ALL_APPS, emptySet()),
            AppRoutingSettings(AppRoutingMode.BYPASS_SELECTED, setOf("a.b")),
            AppRoutingSettings(AppRoutingMode.VPN_ONLY_SELECTED, setOf("a.b")),
            AppRoutingSettings(AppRoutingMode.VPN_ONLY_SELECTED, emptySet()),
        )
        settings.forEach { current ->
            val plan = routing.planFor(own, current)
            assertTrue(plan.allowed == null || plan.disallowed == null)
        }
    }
}
