package com.uacspoofer.mobile.engine.pow

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class PowEngineStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean(KEY_H2_QUALITY_MIGRATION, false)) {
            prefs.edit()
                .putBoolean(KEY_H2_FRAGMENT, false)
                .putBoolean(KEY_H2_QUALITY_MIGRATION, true)
                .apply()
        }
    }

    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<PowEngineSettings> = mutableSettings.asStateFlow()

    fun snapshot(): PowEngineSettings = mutableSettings.value

    fun save(settings: PowEngineSettings): PowEngineSettings {
        val validated = settings.validated()
        prefs.edit()
            .putString(KEY_EXIT_COUNTRY, validated.exitCountryCode)
            .putString(KEY_OUTER, validated.outerTransport)
            .putString(KEY_OBFUSCATION, validated.obfuscationProfile)
            .putBoolean(KEY_H2_FRAGMENT, validated.h2Fragmentation)
            .apply()
        mutableSettings.value = validated
        return validated
    }

    fun rememberedOuterProtocol(): String? =
        prefs.getString(KEY_OUTER_PROTOCOL, null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in PowCoreConfig.OUTER_LADDER }

    fun saveOuterProtocol(protocol: String) {
        val clean = protocol.trim().lowercase()
        if (clean !in PowCoreConfig.OUTER_LADDER) return
        prefs.edit().putString(KEY_OUTER_PROTOCOL, clean).apply()
    }

    fun rememberedStrategyIndex(shape: String, ladderSize: Int): Int {
        val storedShape = prefs.getString(KEY_STRATEGY_SHAPE, null)
        if (storedShape != shape) {
            prefs.edit().remove(KEY_STRATEGY_INDEX).putString(KEY_STRATEGY_SHAPE, shape).apply()
            return 0
        }
        return prefs.getInt(KEY_STRATEGY_INDEX, 0).coerceIn(0, (ladderSize - 1).coerceAtLeast(0))
    }

    fun saveStrategyWinner(index: Int, shape: String) {
        prefs.edit()
            .putInt(KEY_STRATEGY_INDEX, index)
            .putString(KEY_STRATEGY_SHAPE, shape)
            .apply()
    }

    fun availableRegions(): List<String> =
        prefs.getString(KEY_AVAILABLE_REGIONS, "")
            .orEmpty()
            .split(',')
            .map { it.trim().uppercase(Locale.US) }
            .filter(PowRegions::isCode)

    fun rememberAvailableRegions(codes: List<String>) {
        val clean = codes.map { it.trim().uppercase(Locale.US) }.filter(PowRegions::isCode).distinct().sorted()
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_AVAILABLE_REGIONS, clean.joinToString(",")).apply()
    }

    private fun read(): PowEngineSettings = PowEngineSettings(
        exitCountryCode = prefs.getString(KEY_EXIT_COUNTRY, "").orEmpty(),
        outerTransport = prefs.getString(KEY_OUTER, PowCoreConfig.OUTER_AUTO).orEmpty(),
        obfuscationProfile = prefs.getString(KEY_OBFUSCATION, "balanced").orEmpty(),
        h2Fragmentation = prefs.getBoolean(KEY_H2_FRAGMENT, false),
    ).validated()

    companion object {
        private const val PREFS = "uac_pow_engine_v1"
        private const val KEY_EXIT_COUNTRY = "exit_country"
        private const val KEY_OUTER = "outer_transport"
        private const val KEY_OBFUSCATION = "obfuscation_profile"
        private const val KEY_H2_FRAGMENT = "h2_fragmentation"
        private const val KEY_H2_QUALITY_MIGRATION = "h2_fragment_off_for_quality"
        private const val KEY_OUTER_PROTOCOL = "outer_protocol"
        private const val KEY_STRATEGY_INDEX = "psiphon_winning_strategy_chained"
        private const val KEY_STRATEGY_SHAPE = "psiphon_winning_strategy_chained_shape"
        private const val KEY_AVAILABLE_REGIONS = "psiphon_available_regions"

        @Volatile private var instance: PowEngineStore? = null

        fun get(context: Context): PowEngineStore = instance ?: synchronized(this) {
            instance ?: PowEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
