package com.uacspoofer.mobile.profiles

import android.content.Context


class ProfileLatencyCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(validIds: Set<String>): Map<String, Long> = buildMap {
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(PREFIX)) return@forEach
            val id = key.removePrefix(PREFIX)
            val latency = value as? Long ?: return@forEach
            if (id in validIds && latency > 0L) put(id, latency)
        }
    }

    fun put(profileId: String, latencyMs: Long) {
        if (latencyMs > 0L) prefs.edit().putLong(PREFIX + profileId, latencyMs).apply()
    }

    fun remove(profileId: String) {
        prefs.edit().remove(PREFIX + profileId).apply()
    }

    fun removeAll(profileIds: Set<String>) {
        if (profileIds.isEmpty()) return
        prefs.edit().also { editor -> profileIds.forEach { editor.remove(PREFIX + it) } }.apply()
    }

    private companion object {
        const val PREFS = "profile_real_delay_cache_v1"
        const val PREFIX = "latency:"
    }
}
