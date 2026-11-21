package com.uacspoofer.mobile.engine.direct

import android.content.Context
import com.uacspoofer.mobile.settings.NetworkGuardStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DirectEngineStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<DirectEngineSettings> = mutable.asStateFlow()

    fun snapshot(): DirectEngineSettings =
        mutable.value.copy(iranDirect = NetworkGuardStore.get(appContext).snapshot().iranDirect)

    @Synchronized
    fun save(value: DirectEngineSettings) {
        val v = value.copy(adaptiveClientHello = true).validated()
        NetworkGuardStore.get(appContext).setIranDirect(v.iranDirect)
        prefs.edit()
            .remove("googleFronting")
            .remove("echEnabled")
            .remove("adaptiveClientHello")
            .remove("wireGuardOverDirect")
            .putBoolean(UNLOCKED_CONNECTION_METHOD, v.wireGuardOverDirect)
            .remove("scanRangesByLatency")
            .putString("scanMode", v.scanMode.id)
            .putBoolean("autoSelectDns", v.autoSelectDns)
            .putString("exitCountryCode", v.exitCountryCode)
            .putBoolean("fragmentEnabled", v.fragmentEnabled)
            .putString("variant", v.variant)
            .putString("helloLengths", v.helloLengths)
            .putString("helloDelays", v.helloDelays)
            .putInt("helloMaxSplit", v.helloMaxSplit)
            .putBoolean("secondStageEnabled", v.secondStageEnabled)
            .putString("secondLengths", v.secondLengths)
            .putString("secondDelays", v.secondDelays)
            .putInt("secondMaxSplit", v.secondMaxSplit)
            .putBoolean("tcpFragmentEnabled", v.tcpFragmentEnabled)
            .putString("tcpLengths", v.tcpLengths)
            .putString("tcpDelays", v.tcpDelays)
            .putInt("tcpMaxSplit", v.tcpMaxSplit)
            .putBoolean("udpNoise", v.udpNoise)
            .putBoolean("blockQuic", v.blockQuic)
            .putBoolean("ipv6", v.ipv6)
            .putBoolean("iranDirect", v.iranDirect)
            .putString("unfilteredDns", v.unfilteredDns)
            .putString("domesticDns", v.domesticDns)
            .apply()
        mutable.value = v.copy(iranDirect = NetworkGuardStore.get(appContext).snapshot().iranDirect)
    }

    fun resetToDefaults() = save(DirectEngineSettings.DEFAULT)

    private fun load(): DirectEngineSettings {
        val d = DirectEngineSettings.DEFAULT
        fun s(k: String, def: String) = prefs.getString(k, def) ?: def
        fun b(k: String, def: Boolean) = prefs.getBoolean(k, def)
        val iran = runCatching { NetworkGuardStore.get(appContext).snapshot().iranDirect }
            .getOrElse { b("iranDirect", d.iranDirect) }
        return DirectEngineSettings(
            wireGuardOverDirect = b(UNLOCKED_CONNECTION_METHOD, d.wireGuardOverDirect),
            scanMode = DirectScanMode.fromStored(prefs.getString("scanMode", null), b("scanRangesByLatency", false)),
            autoSelectDns = b("autoSelectDns", d.autoSelectDns),
            exitCountryCode = prefs.getString("exitCountryCode", d.exitCountryCode) ?: d.exitCountryCode,
            fragmentEnabled = b("fragmentEnabled", d.fragmentEnabled),
            variant = s("variant", d.variant),
            helloLengths = s("helloLengths", d.helloLengths),
            helloDelays = s("helloDelays", d.helloDelays),
            helloMaxSplit = prefs.getInt("helloMaxSplit", d.helloMaxSplit),
            secondStageEnabled = b("secondStageEnabled", d.secondStageEnabled),
            secondLengths = s("secondLengths", d.secondLengths),
            secondDelays = s("secondDelays", d.secondDelays),
            secondMaxSplit = prefs.getInt("secondMaxSplit", d.secondMaxSplit),
            tcpFragmentEnabled = b("tcpFragmentEnabled", d.tcpFragmentEnabled),
            tcpLengths = s("tcpLengths", d.tcpLengths),
            tcpDelays = s("tcpDelays", d.tcpDelays),
            tcpMaxSplit = prefs.getInt("tcpMaxSplit", d.tcpMaxSplit),
            udpNoise = b("udpNoise", d.udpNoise),
            blockQuic = b("blockQuic", d.blockQuic),
            ipv6 = b("ipv6", d.ipv6),
            iranDirect = iran,
            unfilteredDns = s("unfilteredDns", d.unfilteredDns),
            domesticDns = s("domesticDns", d.domesticDns),
        ).validated()
    }

    companion object {
        private const val PREFS = "engine_direct_v2"
        // Only choices made after the connection menu became hidden may override the default.
        private const val UNLOCKED_CONNECTION_METHOD = "unlockedWireGuardOverDirect"
        @Volatile private var instance: DirectEngineStore? = null
        fun get(context: Context): DirectEngineStore = instance ?: synchronized(this) {
            instance ?: DirectEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
