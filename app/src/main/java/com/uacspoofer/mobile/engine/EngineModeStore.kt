package com.uacspoofer.mobile.engine

import android.content.Context
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EngineModeStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(loadInitialMode())
    val mode: StateFlow<EngineMode> = mutableMode.asStateFlow()

    private fun loadInitialMode(): EngineMode {
        val stored = EngineMode.fromStored(prefs.getString(KEY_MODE, null))
        val introApplied = prefs.getBoolean(EnginePowIntro.FLAG_KEY, false)
        var resolved = EnginePowIntro.resolve(stored, introApplied)
        // Engine 4 stays off the rail until unlocked; never boot into a hidden mode.
        if (resolved.isDirect && !Engine4UnlockStore.get(appContext).snapshot()) {
            resolved = EngineMode.UAC_POW
        }
        if (!introApplied || resolved != stored) {
            prefs.edit()
                .putString(KEY_MODE, resolved.id)
                .putBoolean(EnginePowIntro.FLAG_KEY, true)
                .apply()
            if (!resolved.isTor) TorStatusStore.reset()
            if (!resolved.isPow) com.uacspoofer.mobile.engine.pow.PowStatusStore.reset()
            if (!resolved.isDirect) com.uacspoofer.mobile.engine.direct.DirectStatusStore.reset()
        }
        return resolved
    }

    fun snapshot(): EngineMode = mutableMode.value

    @Synchronized
    fun setMode(mode: EngineMode): EngineModeChangeResult {
        if (mode == mutableMode.value) return EngineModeChangeResult.APPLIED
        if (!canChangeEngineMode(ConnectionStateStore.state.value)) {
            return EngineModeChangeResult.BLOCKED_WHILE_ACTIVE
        }
        val target = if (mode.isDirect && !Engine4UnlockStore.get(appContext).snapshot()) {
            EngineMode.UAC_POW
        } else {
            mode
        }
        if (target == mutableMode.value) return EngineModeChangeResult.APPLIED
        prefs.edit().putString(KEY_MODE, target.id).apply()
        mutableMode.value = target
        if (!target.isTor) TorStatusStore.reset()
        if (!target.isPow) com.uacspoofer.mobile.engine.pow.PowStatusStore.reset()
        if (!target.isDirect) com.uacspoofer.mobile.engine.direct.DirectStatusStore.reset()
        return EngineModeChangeResult.APPLIED
    }

    companion object {
        private const val PREFS = "connection_engine_v1"
        private const val KEY_MODE = "engine_mode"

        @Volatile private var instance: EngineModeStore? = null

        fun get(context: Context): EngineModeStore = instance ?: synchronized(this) {
            instance ?: EngineModeStore(context.applicationContext).also { instance = it }
        }
    }
}
