package com.uacspoofer.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySettingsTest {
    @Test
    fun presetsIncreaseSavingsFromTheExistingDefaults() {
        assertEquals(BatterySettings(), BatteryPreset.BALANCED.settings)
        assertEquals(listOf(0, 50, 100), BatteryPreset.entries.map { it.settings.score(it.settings.lightDirectChecks) })
        assertFalse(BatteryPreset.SAVER.settings.lightDirectChecks)
    }

    @Test
    fun eachSwitchChangesTheDisplayedScore() {
        val defaults = BatterySettings()
        listOf(
            defaults.copy(lightDirectChecks = true),
            defaults.copy(slowBackgroundStats = true),
            defaults.copy(slowNotification = true),
            defaults.copy(staticHomeEffects = true),
        ).forEach { assertEquals(25, it.score(it.lightDirectChecks)) }
    }

    @Test
    fun scoreOnlyIncludesOptionsForTheActiveEngine() {
        val settings = BatterySettings(lightDirectChecks = true, slowNotification = true)
        assertEquals(33, settings.score(null))
        assertEquals(25, settings.score(false))
        assertEquals(50, settings.score(true))
    }

    @Test
    fun presetsOutsideDirectKeepItsSavedOption() {
        val directEnabled = BatterySettings(lightDirectChecks = true)
        assertTrue(BatteryPreset.BALANCED.forEngine(directEnabled, direct = false).lightDirectChecks)
        assertFalse(BatteryPreset.MAX_SAVER.forEngine(BatterySettings(), direct = false).lightDirectChecks)
    }
}
