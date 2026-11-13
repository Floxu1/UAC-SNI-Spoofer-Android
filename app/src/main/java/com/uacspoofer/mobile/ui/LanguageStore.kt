package com.uacspoofer.mobile.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app language. The system locale is left alone; the drawer and settings read this flow. */
internal class LanguageStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val language: StateFlow<DrawerLanguage> = mutable.asStateFlow()

    fun snapshot(): DrawerLanguage = mutable.value

    fun set(language: DrawerLanguage) {
        if (mutable.value == language) return
        prefs.edit().putString(KEY, language.name).apply()
        mutable.value = language
    }

    private fun read(): DrawerLanguage =
        if (prefs.getString(KEY, DrawerLanguage.PERSIAN.name) == DrawerLanguage.ENGLISH.name) {
            DrawerLanguage.ENGLISH
        } else {
            DrawerLanguage.PERSIAN
        }

    companion object {
        private const val PREFS = "app_preferences"
        private const val KEY = "language"

        @Volatile private var instance: LanguageStore? = null

        fun get(context: Context): LanguageStore = instance ?: synchronized(this) {
            instance ?: LanguageStore(context.applicationContext).also { instance = it }
        }
    }
}
