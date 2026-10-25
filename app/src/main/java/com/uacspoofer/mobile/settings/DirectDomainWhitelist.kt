package com.uacspoofer.mobile.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User domains that always leave the phone directly (all engines), independent of
 * the Iran-direct toggle. Shared prefs; normalized hostnames only.
 */
class DirectDomainWhitelist private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val domains: StateFlow<List<String>> = mutable.asStateFlow()

    fun snapshot(): List<String> = mutable.value

    fun hasAny(): Boolean = mutable.value.isNotEmpty()

    @Synchronized
    fun add(raw: String): Boolean {
        val host = normalize(raw) ?: return false
        val next = (mutable.value + host).distinct().sorted()
        if (next == mutable.value) return false
        persist(next)
        return true
    }

    @Synchronized
    fun remove(host: String) {
        val key = normalize(host) ?: host.trim().lowercase()
        persist(mutable.value.filterNot { it == key })
    }

    private fun read(): List<String> {
        val raw = prefs.getString(KEY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n', ',', ';')
            .mapNotNull { normalize(it) }
            .distinct()
            .sorted()
    }

    private fun persist(value: List<String>) {
        prefs.edit().putString(KEY, value.joinToString("\n")).apply()
        mutable.value = value
    }

    companion object {
        private const val PREFS = "direct_domain_whitelist_v1"
        private const val KEY = "domains"

        @Volatile private var instance: DirectDomainWhitelist? = null

        fun get(context: Context): DirectDomainWhitelist =
            instance ?: synchronized(this) {
                instance ?: DirectDomainWhitelist(context.applicationContext).also { instance = it }
            }

        /** Empty until [get] has been called once (UI / VPN start). */
        fun snapshotOrEmpty(): List<String> = instance?.snapshot().orEmpty()

        /** Strip scheme/path/port; reject junk. Returns null if not a usable hostname. */
        fun normalize(raw: String): String? {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val slash = s.indexOf('/')
            if (slash >= 0) s = s.substring(0, slash)
            val colon = s.indexOf(':')
            if (colon >= 0) s = s.substring(0, colon)
            s = s.trim().trimEnd('.')
            if (s.length < 3 || s.length > 253) return null
            if ('.' !in s) return null
            if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            if (s.startsWith('.') || s.endsWith('.') || s.startsWith('-') || ".." in s) return null
            return s
        }

        /**
         * Xray domain rule entries per host.
         * domain: covers the bare host and all subdomains (www.adliran.ir etc.).
         * full: covers the exact host when sniffing yields a bare name.
         */
        fun xrayDomainEntries(hosts: List<String>): List<String> {
            val out = ArrayList<String>(hosts.size * 2)
            for (host in hosts) {
                out += "domain:$host"
                out += "full:$host"
            }
            return out
        }

        fun xrayDomainsJson(hosts: List<String>): String {
            fun q(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
            return xrayDomainEntries(hosts).joinToString(",") { "\"${q(it)}\"" }
        }
    }
}
