package com.uacspoofer.mobile.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AppTheme {
    CLASSIC,
    NEON,
    SOLARIS,
}

internal data class SavedAppTheme(
    val id: String,
    val name: String,
    val base: AppTheme,
    val tokens: NeonTokens,
)

class AppThemeStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val theme: StateFlow<AppTheme> = mutable.asStateFlow()
    private val mutableSaved = MutableStateFlow(readSaved())
    internal val savedThemes = mutableSaved.asStateFlow()
    private val mutableSelectedId = MutableStateFlow(prefs.getString(SAVED_ID, null)?.let { id ->
        mutableSaved.value.firstOrNull { it.id == id && it.base == mutable.value }?.id
    })
    internal val selectedId = mutableSelectedId.asStateFlow()

    fun set(theme: AppTheme) {
        prefs.edit().putString(KEY, theme.name).remove(SAVED_ID).apply()
        applySelection(theme, null)
    }

    internal fun selectSaved(id: String) {
        val saved = mutableSaved.value.firstOrNull { it.id == id } ?: return
        prefs.edit().putString(KEY, saved.base.name).putString(SAVED_ID, saved.id).apply()
        applySelection(saved.base, saved)
    }

    internal fun saveTheme(name: String, base: AppTheme, tokens: NeonTokens): Boolean {
        val title = name.trim()
        if (title.isBlank() || title.length > MAX_NAME_LENGTH || base == AppTheme.CLASSIC ||
            mutableSaved.value.any { it.name.equals(title, ignoreCase = true) }
        ) return false
        val saved = SavedAppTheme(UUID.randomUUID().toString(), title, base, tokens)
        saveThemes(mutableSaved.value + saved, saved)
        return true
    }

    internal fun updateSelectedTheme(tokens: NeonTokens) {
        val current = mutableSaved.value.firstOrNull { it.id == mutableSelectedId.value } ?: return
        val updated = current.copy(tokens = tokens)
        saveThemes(mutableSaved.value.map { if (it.id == updated.id) updated else it }, updated)
    }

    private fun saveThemes(themes: List<SavedAppTheme>, selected: SavedAppTheme) {
        val json = JSONArray()
        for (item in themes) {
            json.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("base", item.base.name).put("tokens", ThemeTokenCodec.encode(item.tokens)))
        }
        prefs.edit().putString(SAVED_THEMES, json.toString())
            .putString(KEY, selected.base.name).putString(SAVED_ID, selected.id).apply()
        mutableSaved.value = themes
        applySelection(selected.base, selected)
    }

    private fun applySelection(theme: AppTheme, saved: SavedAppTheme?) {
        val tokens = saved?.tokens
        when (theme) {
            AppTheme.NEON -> NeonLive.tokens = tokens ?: NeonTokens()
            AppTheme.SOLARIS -> SolarisLive.tokens = tokens ?: SolarisTokens
            AppTheme.CLASSIC -> Unit
        }
        mutable.value = theme
        mutableSelectedId.value = saved?.id
        UacThemeState.theme = theme
    }

    private fun readSaved(): List<SavedAppTheme> {
        val json = runCatching { JSONArray(prefs.getString(SAVED_THEMES, "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val base = when (item.optString("base")) {
                AppTheme.NEON.name -> AppTheme.NEON
                AppTheme.SOLARIS.name -> AppTheme.SOLARIS
                else -> return@mapNotNull null
            }
            val tokens = item.optJSONObject("tokens") ?: return@mapNotNull null
            SavedAppTheme(id, name, base, ThemeTokenCodec.decode(tokens,
                if (base == AppTheme.SOLARIS) SolarisTokens else NeonTokens()))
        }
    }

    private fun read(): AppTheme {
        val stored = prefs.getString(KEY, AppTheme.CLASSIC.name)
        if (stored == "FIRE") return AppTheme.CLASSIC
        return AppTheme.entries.firstOrNull { it.name == stored } ?: AppTheme.CLASSIC
    }

    companion object {
        private const val PREFS = "uac_app_theme"
        private const val KEY = "theme"
        private const val SAVED_THEMES = "saved_themes"
        private const val SAVED_ID = "saved_theme_id"
        internal const val MAX_NAME_LENGTH = 48

        @Volatile
        private var instance: AppThemeStore? = null

        fun get(context: Context): AppThemeStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AppThemeStore(context.applicationContext).also {
                    it.applySelection(it.theme.value, it.savedThemes.value.firstOrNull { saved ->
                        saved.id == it.selectedId.value
                    })
                    instance = it
                }
            }
        }
    }
}

