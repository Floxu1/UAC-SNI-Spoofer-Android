package com.uacspoofer.mobile.engine.direct

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DirectEngineStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<DirectEngineSettings> = mutable.asStateFlow()

    fun snapshot(): DirectEngineSettings = mutable.value

    @Synchronized
    fun save(value: DirectEngineSettings) {
        val v = value.validated()
        prefs.edit()
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
        mutable.value = v
    }

    fun resetToDefaults() = save(DirectEngineSettings.DEFAULT)

    private fun load(): DirectEngineSettings {
        val d = DirectEngineSettings.DEFAULT
        fun s(k: String, def: String) = prefs.getString(k, def) ?: def
        return DirectEngineSettings(
            variant = s("variant", d.variant),
            helloLengths = s("helloLengths", d.helloLengths),
            helloDelays = s("helloDelays", d.helloDelays),
            helloMaxSplit = prefs.getInt("helloMaxSplit", d.helloMaxSplit),
            secondStageEnabled = prefs.getBoolean("secondStageEnabled", d.secondStageEnabled),
            secondLengths = s("secondLengths", d.secondLengths),
            secondDelays = s("secondDelays", d.secondDelays),
            secondMaxSplit = prefs.getInt("secondMaxSplit", d.secondMaxSplit),
            tcpFragmentEnabled = prefs.getBoolean("tcpFragmentEnabled", d.tcpFragmentEnabled),
            tcpLengths = s("tcpLengths", d.tcpLengths),
            tcpDelays = s("tcpDelays", d.tcpDelays),
            tcpMaxSplit = prefs.getInt("tcpMaxSplit", d.tcpMaxSplit),
            udpNoise = prefs.getBoolean("udpNoise", d.udpNoise),
            blockQuic = prefs.getBoolean("blockQuic", d.blockQuic),
            ipv6 = prefs.getBoolean("ipv6", d.ipv6),
            iranDirect = prefs.getBoolean("iranDirect", d.iranDirect),
            unfilteredDns = s("unfilteredDns", d.unfilteredDns),
            domesticDns = s("domesticDns", d.domesticDns),
        ).validated()
    }

    companion object {
        private const val PREFS = "engine_direct_v1"
        @Volatile private var instance: DirectEngineStore? = null
        fun get(context: Context): DirectEngineStore = instance ?: synchronized(this) {
            instance ?: DirectEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
