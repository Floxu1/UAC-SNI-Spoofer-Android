package com.uacspoofer.mobile.engine.tor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TorEngineStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<TorEngineSettings> = mutableSettings.asStateFlow()

    fun snapshot(): TorEngineSettings = mutableSettings.value

    fun save(settings: TorEngineSettings): TorEngineSettings {
        val validated = settings.validated()
        prefs.edit()
            .putString(KEY_BRIDGES, validated.bridgeLines)
            .putInt(KEY_SOCKS, validated.socksPort)
            .putInt(KEY_CONTROL, validated.controlPort)
            .putBoolean(KEY_FRAGMENT, validated.fragmentEnabled)
            .putString(KEY_FRAGMENT_PACKET, validated.fragmentPacket)
            .putInt(KEY_FRAGMENT_LENGTH, validated.fragmentLength)
            .putInt(KEY_FRAGMENT_DELAY, validated.fragmentDelayMs)
            .putString(KEY_EXIT_COUNTRY, validated.exitCountryCode)
            .putBoolean(KEY_EXIT_STRICT, validated.exitStrict)
            .apply()
        mutableSettings.value = validated
        return validated
    }

    fun lastGoodBridge(networkKey: String): String =
        prefs.getString(lastGoodKey(networkKey), "").orEmpty()

    fun saveLastGoodBridge(networkKey: String, bridgeLine: String) {
        val trimmed = bridgeLine.trim()
        if (networkKey.isBlank() || trimmed.isEmpty()) return
        prefs.edit().putString(lastGoodKey(networkKey), trimmed).apply()
    }

    private fun read(): TorEngineSettings = TorEngineSettings(
        bridgeLines = prefs.getString(KEY_BRIDGES, "").orEmpty(),
        socksPort = prefs.getInt(KEY_SOCKS, TorEngineSettings.SOCKS_PORT),
        controlPort = prefs.getInt(KEY_CONTROL, TorEngineSettings.CONTROL_PORT),
        fragmentEnabled = prefs.getBoolean(KEY_FRAGMENT, true),
        fragmentPacket = prefs.getString(KEY_FRAGMENT_PACKET, "tlshello").orEmpty(),
        fragmentLength = prefs.getInt(KEY_FRAGMENT_LENGTH, 5),
        fragmentDelayMs = prefs.getInt(KEY_FRAGMENT_DELAY, 0),
        exitCountryCode = prefs.getString(KEY_EXIT_COUNTRY, TorExitCountry.AUTOMATIC).orEmpty(),
        exitStrict = prefs.getBoolean(KEY_EXIT_STRICT, false),
    ).validated()

    private fun lastGoodKey(networkKey: String): String = "$KEY_LAST_GOOD:$networkKey"

    companion object {
        private const val PREFS = "tor_engine_v1"
        private const val KEY_BRIDGES = "bridge_lines"
        private const val KEY_SOCKS = "socks_port"
        private const val KEY_CONTROL = "control_port"
        private const val KEY_FRAGMENT = "fragment_enabled"
        private const val KEY_FRAGMENT_PACKET = "fragment_packet"
        private const val KEY_FRAGMENT_LENGTH = "fragment_length"
        private const val KEY_FRAGMENT_DELAY = "fragment_delay"
        private const val KEY_EXIT_COUNTRY = "exit_country"
        private const val KEY_EXIT_STRICT = "exit_strict"
        private const val KEY_LAST_GOOD = "last_good_bridge"

        @Volatile private var instance: TorEngineStore? = null

        fun get(context: Context): TorEngineStore = instance ?: synchronized(this) {
            instance ?: TorEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
