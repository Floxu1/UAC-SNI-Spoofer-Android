package com.uacspoofer.mobile.settings

import android.content.Context
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BatterySettings(
    val lightDirectChecks: Boolean = false,
    val slowBackgroundStats: Boolean = false,
    val slowNotification: Boolean = false,
    val staticHomeEffects: Boolean = false,
) {
    fun score(connectionCheck: Boolean?): Int {
        val applicable = listOfNotNull(connectionCheck, slowBackgroundStats, slowNotification, staticHomeEffects)
        return (100.0 * applicable.count { it } / applicable.size).roundToInt()
    }
}

internal enum class BatteryPreset(val settings: BatterySettings) {
    BALANCED(BatterySettings()),
    SAVER(BatterySettings(slowBackgroundStats = true, slowNotification = true)),
    MAX_SAVER(BatterySettings(
        lightDirectChecks = true,
        slowBackgroundStats = true,
        slowNotification = true,
        staticHomeEffects = true,
    ));

    fun forEngine(current: BatterySettings, direct: Boolean): BatterySettings =
        if (direct) settings else settings.copy(lightDirectChecks = current.lightDirectChecks)
}

internal class BatterySettingsStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("battery_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(
        BatterySettings(
            lightDirectChecks = prefs.getBoolean("light_direct_checks", false),
            slowBackgroundStats = prefs.getBoolean("slow_background_stats", false),
            slowNotification = prefs.getBoolean("slow_notification", false),
            staticHomeEffects = prefs.getBoolean("static_home_effects", false),
        ),
    )
    val settings = mutableSettings.asStateFlow()

    fun snapshot(): BatterySettings = settings.value

    @Synchronized
    fun save(value: BatterySettings) {
        prefs.edit()
            .putBoolean("light_direct_checks", value.lightDirectChecks)
            .putBoolean("slow_background_stats", value.slowBackgroundStats)
            .putBoolean("slow_notification", value.slowNotification)
            .putBoolean("static_home_effects", value.staticHomeEffects)
            .apply()
        mutableSettings.value = value
    }

    companion object {
        @Volatile private var instance: BatterySettingsStore? = null

        fun get(context: Context): BatterySettingsStore =
            instance ?: synchronized(this) {
                instance ?: BatterySettingsStore(context).also { instance = it }
            }
    }
}
