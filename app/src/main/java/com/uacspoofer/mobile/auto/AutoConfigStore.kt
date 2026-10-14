package com.uacspoofer.mobile.auto

import android.content.Context
import java.io.File

/** Disk state for Auto: per-network ranking and DPI type, ECH keys, and the last good list. */
class AutoConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val listFile = File(context.applicationContext.filesDir, "auto/patterniha-configs.txt")

    @Synchronized
    fun ranking(networkKey: String): List<AutoRankedEntry> = AutoRanking.decode(prefs.getString("rank:$networkKey", null))

    @Synchronized
    fun saveRanking(networkKey: String, entries: List<AutoRankedEntry>) {
        prefs.edit().putString("rank:$networkKey", AutoRanking.encode(entries)).apply()
    }

    @Synchronized
    fun update(networkKey: String, change: (List<AutoRankedEntry>) -> List<AutoRankedEntry>) {
        saveRanking(networkKey, change(ranking(networkKey)))
    }

    fun dpi(networkKey: String, now: Long = System.currentTimeMillis()): DpiType? {
        val raw = prefs.getString("dpi:$networkKey", null) ?: return null
        val at = raw.substringAfter('|', "").toLongOrNull() ?: return null
        if (now - at !in 0..DPI_TTL_MS) return null
        return runCatching { DpiType.valueOf(raw.substringBefore('|')) }.getOrNull()?.takeIf { it != DpiType.UNKNOWN }
    }

    fun speedTestedAt(networkKey: String): Long = prefs.getLong("speed_at:$networkKey", 0L)

    fun markSpeedTested(networkKey: String, now: Long) {
        prefs.edit().putLong("speed_at:$networkKey", now).apply()
    }

    fun saveDpi(networkKey: String, dpi: DpiType, now: Long = System.currentTimeMillis()) {
        prefs.edit().putString("dpi:$networkKey", "${dpi.name}|$now").apply()
    }

    fun echKey(domain: String): Pair<String, Long>? {
        val raw = prefs.getString("ech:${domain.lowercase()}", null) ?: return null
        val key = raw.substringBefore('|')
        val at = raw.substringAfter('|', "").toLongOrNull() ?: return null
        return if (EchDns.isBase64List(key)) key to at else null
    }

    fun putEchKey(domain: String, key: String, now: Long) {
        prefs.edit().putString("ech:${domain.lowercase()}", "$key|$now").apply()
    }

    fun cachedList(): String? = listFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.takeIf(String::isNotBlank)

    fun listFetchedAt(): Long = prefs.getLong("list_fetched_at", 0L)

    fun listEtag(url: String): String? = prefs.getString("etag:$url", null)

    fun saveList(text: String, url: String, etag: String?, now: Long) {
        listFile.parentFile?.mkdirs()
        val tmp = File(listFile.parentFile, "${listFile.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(listFile)) {
            listFile.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        prefs.edit().putLong("list_fetched_at", now).putString("list_source", url)
            .apply { if (etag != null) putString("etag:$url", etag) else remove("etag:$url") }
            .apply()
    }

    fun touchList(now: Long) {
        prefs.edit().putLong("list_fetched_at", now).apply()
    }

    companion object {
        private const val PREFS = "uac_auto_xray_v1"
        const val DPI_TTL_MS = 24L * 60 * 60 * 1000
    }
}