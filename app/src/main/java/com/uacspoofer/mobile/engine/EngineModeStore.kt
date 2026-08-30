package com.uacspoofer.mobile.engine

import android.content.Context
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EngineModeStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(EngineMode.fromStored(prefs.getString(KEY_MODE, null)))
    val mode: StateFlow<EngineMode> = mutableMode.asStateFlow()

    fun snapshot(): EngineMode = mutableMode.value

    @Synchronized
    fun setMode(mode: EngineMode): EngineModeChangeResult {
        if (mode == mutableMode.value) return EngineModeChangeResult.APPLIED
        if (!canChangeEngineMode(ConnectionStateStore.state.value)) {
            return EngineModeChangeResult.BLOCKED_WHILE_ACTIVE
        }
        prefs.edit().putString(KEY_MODE, mode.id).apply()
        mutableMode.value = mode
        if (mode.isXray) TorStatusStore.reset()
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
