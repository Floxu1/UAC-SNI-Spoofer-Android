package com.uacspoofer.mobile.location

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsSpoofStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    fun snapshot(): Boolean = mutableEnabled.value

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == mutableEnabled.value) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        mutableEnabled.value = enabled
    }

    companion object {
        private const val PREFS = "gps_spoof_v1"
        private const val KEY_ENABLED = "enabled"

        @Volatile private var instance: GpsSpoofStore? = null

        fun get(context: Context): GpsSpoofStore = instance ?: synchronized(this) {
            instance ?: GpsSpoofStore(context.applicationContext).also { instance = it }
        }
    }
}
