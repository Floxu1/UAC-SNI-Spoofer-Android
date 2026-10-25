package com.uacspoofer.mobile.engine

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Engine 4 (UAC Direct) stays hidden until unlocked with a 2s long-press on the
 * Support screen version card. Same gesture locks it again.
 */
class Engine4UnlockStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableUnlocked = MutableStateFlow(prefs.getBoolean(KEY_UNLOCKED, false))
    val unlocked: StateFlow<Boolean> = mutableUnlocked.asStateFlow()

    fun snapshot(): Boolean = mutableUnlocked.value

    @Synchronized
    fun setUnlocked(value: Boolean, showToast: Boolean = true): Boolean {
        if (mutableUnlocked.value == value) {
            if (showToast) toast(value)
            return value
        }
        prefs.edit().putBoolean(KEY_UNLOCKED, value).apply()
        mutableUnlocked.value = value
        if (!value) {
            val engineStore = EngineModeStore.get(appContext)
            if (engineStore.snapshot().isDirect) {
                engineStore.setMode(EngineMode.UAC_POW)
            }
        }
        if (showToast) toast(value)
        return value
    }

    @Synchronized
    fun toggle(showToast: Boolean = true): Boolean = setUnlocked(!snapshot(), showToast)

    private fun toast(unlockedNow: Boolean) {
        val text = if (unlockedNow) {
            "موتور Direct فعال شد · Direct unlocked"
        } else {
            "موتور Direct مخفی شد · Direct locked"
        }
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS = "engine4_unlock_v1"
        private const val KEY_UNLOCKED = "unlocked"

        @Volatile private var instance: Engine4UnlockStore? = null

        fun get(context: Context): Engine4UnlockStore = instance ?: synchronized(this) {
            instance ?: Engine4UnlockStore(context.applicationContext).also { instance = it }
        }
    }
}
