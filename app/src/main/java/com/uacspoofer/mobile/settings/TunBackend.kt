package com.uacspoofer.mobile.settings

import android.content.Context

enum class TunBackend(val id: String, val label: String) {
    HEV("hev", "HEV"),
    ZEPTUN("zeptun", "Zeptun");

    companion object {
        fun fromId(id: String?): TunBackend = entries.firstOrNull { it.id == id } ?: HEV
    }
}

internal class TunBackendStore(context: Context) {
    private val prefs = context.getSharedPreferences("tun_backend", Context.MODE_PRIVATE)

    fun selected(): TunBackend = TunBackend.fromId(prefs.getString("selected", null))

    fun select(backend: TunBackend) {
        prefs.edit().putString("selected", backend.id).apply()
    }
}
