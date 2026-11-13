package com.uacspoofer.mobile.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppTheme {
    CLASSIC,
    NEON,
    SOLARIS,
}

class AppThemeStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val theme: StateFlow<AppTheme> = mutable.asStateFlow()

    fun set(theme: AppTheme) {
        if (mutable.value == theme) return
        prefs.edit().putString(KEY, theme.name).apply()
        mutable.value = theme
        UacThemeState.theme = theme
        UacThemeState.palette = paletteFor(theme)
    }

    private fun read(): AppTheme {
        val stored = prefs.getString(KEY, AppTheme.CLASSIC.name)
        return AppTheme.entries.firstOrNull { it.name == stored } ?: AppTheme.CLASSIC
    }

    companion object {
        private const val PREFS = "uac_app_theme"
        private const val KEY = "theme"

        @Volatile
        private var instance: AppThemeStore? = null

        fun get(context: Context): AppThemeStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AppThemeStore(context.applicationContext).also {
                    UacThemeState.theme = it.theme.value
                    UacThemeState.palette = paletteFor(it.theme.value)
                    instance = it
                }
            }
        }
    }
}

