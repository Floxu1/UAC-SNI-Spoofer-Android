package com.uacspoofer.mobile.engine.tor

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last Tor connect, shown on the Tor settings status card. */
data class TorLastConnect(
    val atMillis: Long = 0L,
    val bridgeEndpoint: String = "",
    val bootstrapMs: Long = 0L,
    val fragment: Boolean = false,
    val warm: Boolean = false,
) {
    fun describe(): String = if (atMillis <= 0L) "" else buildString {
        append(bridgeEndpoint.ifBlank { "bridge" })
        append(" · ").append(bootstrapMs / 1000).append("s")
        if (warm) append(" · warm")
        if (fragment) append(" · frag")
    }
}

class TorEngineStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<TorEngineSettings> = mutableSettings.asStateFlow()
    private val mutableLast = MutableStateFlow(readLast())
    val lastConnect: StateFlow<TorLastConnect> = mutableLast.asStateFlow()

    init {
        AppLogRepository.torLogcatMirror = mutableSettings.value.logcatMirror
    }

    fun snapshot(): TorEngineSettings = mutableSettings.value

    fun save(settings: TorEngineSettings): TorEngineSettings {
        val validated = settings.validated()
        prefs.edit()
            .putString(KEY_BRIDGES, validated.bridgeLines)
            .putInt(KEY_SOCKS, validated.socksPort)
            .putInt(KEY_CONTROL, validated.controlPort)
            .putString(KEY_FRAGMENT_MODE, validated.fragmentMode)
            .putString(KEY_FRAGMENT_PACKET, validated.fragmentPacket)
            .putInt(KEY_FRAGMENT_LENGTH, validated.fragmentLength)
            .putInt(KEY_FRAGMENT_DELAY, validated.fragmentDelayMs)
            .putString(KEY_EXIT_COUNTRY, validated.exitCountryCode)
            .putBoolean(KEY_EXIT_STRICT, validated.exitStrict)
            .putBoolean(KEY_FAST_EXIT, validated.fastExit)
            .putBoolean(KEY_BLOCK_UDP, validated.blockUdp)
            .putBoolean(KEY_REDUCED_PADDING, validated.reducedPadding)
            .putBoolean(KEY_SINGLE_PROCESS, validated.singleProcess)
            .putBoolean(KEY_STAGE_TIMEOUTS, validated.stageTimeouts)
            .putBoolean(KEY_WATCHDOG, validated.watchdog)
            .putBoolean(KEY_BRIDGE_REFRESH, validated.bridgeRefresh)
            .putString(KEY_BRIDGE_REFRESH_URL, validated.bridgeRefreshUrl)
            .putBoolean(KEY_LOGCAT, validated.logcatMirror)
            .putBoolean(KEY_DUAL_GUARDS, validated.dualGuards)
            .putBoolean(KEY_THROUGHPUT, validated.throughputScoring)
            .putBoolean(KEY_BIG_BUFFERS, validated.bigBuffers)
            .putBoolean(KEY_FAST_RELAYS, validated.fastRelays)
            .putBoolean(KEY_MULTI_CIRCUIT, validated.multiCircuit)
            .putBoolean(KEY_SLOW_SWAP, validated.slowCircuitSwap)
            .putInt(KEY_TUN_MTU, validated.tunMtu)
            .apply()
        mutableSettings.value = validated
        AppLogRepository.torLogcatMirror = validated.logcatMirror
        return validated
    }

    fun lastGoodBridge(networkKey: String): String =
        prefs.getString(lastGoodKey(networkKey), "").orEmpty()

    fun saveLastGoodBridge(networkKey: String, bridgeLine: String) {
        val trimmed = bridgeLine.trim()
        if (networkKey.isBlank() || trimmed.isEmpty()) return
        prefs.edit().putString(lastGoodKey(networkKey), trimmed).apply()
    }

    fun saveLastConnect(last: TorLastConnect) {
        prefs.edit()
            .putLong(KEY_LAST_AT, last.atMillis)
            .putString(KEY_LAST_BRIDGE, last.bridgeEndpoint)
            .putLong(KEY_LAST_MS, last.bootstrapMs)
            .putBoolean(KEY_LAST_FRAG, last.fragment)
            .putBoolean(KEY_LAST_WARM, last.warm)
            .apply()
        mutableLast.value = last
    }

    /** PT refused TOR_PT_PROXY once: Auto fragment skips the local proxy from now on. */
    var ptProxyUnsupported: Boolean
        get() = prefs.getBoolean(KEY_PT_PROXY_UNSUPPORTED, false)
        set(value) { prefs.edit().putBoolean(KEY_PT_PROXY_UNSUPPORTED, value).apply() }

    var refreshedBridges: String
        get() = prefs.getString(KEY_REFRESHED, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_REFRESHED, value).apply() }


    /** Cached fast middle relays (comma-separated $FP) and when they were picked. */
    var fastRelayList: String
        get() = prefs.getString(KEY_FAST_RELAY_LIST, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_FAST_RELAY_LIST, value).putLong(KEY_FAST_RELAY_AT, System.currentTimeMillis()).apply() }
    val fastRelayAt: Long get() = prefs.getLong(KEY_FAST_RELAY_AT, 0L)

    var lastRefreshAt: Long
        get() = prefs.getLong(KEY_REFRESHED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_REFRESHED_AT, value).apply() }

    private fun read(): TorEngineSettings = TorEngineSettings(
        bridgeLines = prefs.getString(KEY_BRIDGES, "").orEmpty(),
        socksPort = prefs.getInt(KEY_SOCKS, TorEngineSettings.SOCKS_PORT),
        controlPort = prefs.getInt(KEY_CONTROL, TorEngineSettings.CONTROL_PORT),
        fragmentMode = prefs.getString(KEY_FRAGMENT_MODE, null)
            ?: if (prefs.getBoolean(KEY_FRAGMENT, true)) TorEngineSettings.FRAGMENT_AUTO else TorEngineSettings.FRAGMENT_OFF,
        fragmentPacket = prefs.getString(KEY_FRAGMENT_PACKET, "tlshello").orEmpty(),
        fragmentLength = prefs.getInt(KEY_FRAGMENT_LENGTH, 5),
        fragmentDelayMs = prefs.getInt(KEY_FRAGMENT_DELAY, 0),
        exitCountryCode = prefs.getString(KEY_EXIT_COUNTRY, TorExitCountry.AUTOMATIC).orEmpty(),
        exitStrict = prefs.getBoolean(KEY_EXIT_STRICT, false),
        fastExit = prefs.getBoolean(KEY_FAST_EXIT, false),
        blockUdp = prefs.getBoolean(KEY_BLOCK_UDP, true),
        reducedPadding = prefs.getBoolean(KEY_REDUCED_PADDING, true),
        singleProcess = prefs.getBoolean(KEY_SINGLE_PROCESS, true),
        stageTimeouts = prefs.getBoolean(KEY_STAGE_TIMEOUTS, true),
        watchdog = prefs.getBoolean(KEY_WATCHDOG, true),
        bridgeRefresh = prefs.getBoolean(KEY_BRIDGE_REFRESH, false),
        bridgeRefreshUrl = prefs.getString(KEY_BRIDGE_REFRESH_URL, "").orEmpty(),
        logcatMirror = prefs.getBoolean(KEY_LOGCAT, true),
        dualGuards = prefs.getBoolean(KEY_DUAL_GUARDS, true),
        throughputScoring = prefs.getBoolean(KEY_THROUGHPUT, true),
        bigBuffers = prefs.getBoolean(KEY_BIG_BUFFERS, true),
        fastRelays = prefs.getBoolean(KEY_FAST_RELAYS, true),
        multiCircuit = prefs.getBoolean(KEY_MULTI_CIRCUIT, true),
        slowCircuitSwap = prefs.getBoolean(KEY_SLOW_SWAP, true),
        tunMtu = prefs.getInt(KEY_TUN_MTU, 0),
    ).validated()

    private fun readLast(): TorLastConnect = TorLastConnect(
        atMillis = prefs.getLong(KEY_LAST_AT, 0L),
        bridgeEndpoint = prefs.getString(KEY_LAST_BRIDGE, "").orEmpty(),
        bootstrapMs = prefs.getLong(KEY_LAST_MS, 0L),
        fragment = prefs.getBoolean(KEY_LAST_FRAG, false),
        warm = prefs.getBoolean(KEY_LAST_WARM, false),
    )

    private fun lastGoodKey(networkKey: String): String = "$KEY_LAST_GOOD:$networkKey"

    companion object {
        internal const val PREFS = "tor_engine_v1"
        private const val KEY_BRIDGES = "bridge_lines"
        private const val KEY_SOCKS = "socks_port"
        private const val KEY_CONTROL = "control_port"
        private const val KEY_FRAGMENT = "fragment_enabled"
        private const val KEY_FRAGMENT_MODE = "fragment_mode"
        private const val KEY_FRAGMENT_PACKET = "fragment_packet"
        private const val KEY_FRAGMENT_LENGTH = "fragment_length"
        private const val KEY_FRAGMENT_DELAY = "fragment_delay"
        private const val KEY_EXIT_COUNTRY = "exit_country"
        private const val KEY_EXIT_STRICT = "exit_strict"
        private const val KEY_FAST_EXIT = "fast_exit"
        private const val KEY_BLOCK_UDP = "block_udp"
        private const val KEY_REDUCED_PADDING = "reduced_padding"
        private const val KEY_SINGLE_PROCESS = "single_process"
        private const val KEY_STAGE_TIMEOUTS = "stage_timeouts"
        private const val KEY_WATCHDOG = "watchdog"
        private const val KEY_BRIDGE_REFRESH = "bridge_refresh"
        private const val KEY_BRIDGE_REFRESH_URL = "bridge_refresh_url"
        private const val KEY_LOGCAT = "logcat_mirror"
        private const val KEY_DUAL_GUARDS = "dual_guards"
        private const val KEY_THROUGHPUT = "throughput_scoring"
        private const val KEY_BIG_BUFFERS = "big_buffers"
        private const val KEY_FAST_RELAYS = "fast_relays"
        private const val KEY_MULTI_CIRCUIT = "multi_circuit"
        private const val KEY_SLOW_SWAP = "slow_circuit_swap"
        private const val KEY_FAST_RELAY_LIST = "fast_relay_list"
        private const val KEY_FAST_RELAY_AT = "fast_relay_at"
        private const val KEY_TUN_MTU = "tun_mtu"
        private const val KEY_LAST_GOOD = "last_good_bridge"
        private const val KEY_LAST_AT = "last_connect_at"
        private const val KEY_LAST_BRIDGE = "last_connect_bridge"
        private const val KEY_LAST_MS = "last_connect_ms"
        private const val KEY_LAST_FRAG = "last_connect_frag"
        private const val KEY_LAST_WARM = "last_connect_warm"
        private const val KEY_PT_PROXY_UNSUPPORTED = "pt_proxy_unsupported"
        private const val KEY_REFRESHED = "refreshed_bridges"
        private const val KEY_REFRESHED_AT = "refreshed_at"

        @Volatile private var instance: TorEngineStore? = null

        fun get(context: Context): TorEngineStore = instance ?: synchronized(this) {
            instance ?: TorEngineStore(context.applicationContext).also { instance = it }
        }
    }
}