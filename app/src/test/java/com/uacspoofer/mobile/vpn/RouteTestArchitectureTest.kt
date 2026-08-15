package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.settings.AdvancedSettingsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTestArchitectureTest {
    @Test
    fun fullCloudflareMatrixHasOneThousandLogicalRoutes() {
        assertEquals(1_000, RouteTestArchitecture.MAX_LOGICAL_CANDIDATES)
        assertEquals(5, RouteTestArchitecture.tuningProfiles(AdvancedSettingsData()).size)
    }

    @Test
    fun mtuMatrixKeepsCurrentValueAndHasFourUniqueValues() {
        val values = RouteTestArchitecture.mtuValues(1_280)
        assertEquals(4, values.size)
        assertEquals(4, values.distinct().size)
        assertTrue(1_280 in values)
    }

    @Test
    fun nonDefaultMtuIsNeverDropped() {
        val values = RouteTestArchitecture.mtuValues(1_320)
        assertEquals(1_320, values.first())
        assertEquals(4, values.size)
    }
}
