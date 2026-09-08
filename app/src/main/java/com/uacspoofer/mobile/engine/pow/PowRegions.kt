package com.uacspoofer.mobile.engine.pow

import android.content.Context
import java.util.Locale

object PowRegions {
    const val AUTOMATIC = ""

    private val BUNDLED = mapOf(
        "AT" to "Austria",
        "AU" to "Australia",
        "BE" to "Belgium",
        "CA" to "Canada",
        "CH" to "Switzerland",
        "CZ" to "Czechia",
        "DE" to "Germany",
        "DK" to "Denmark",
        "ES" to "Spain",
        "FI" to "Finland",
        "FR" to "France",
        "GB" to "United Kingdom",
        "ID" to "Indonesia",
        "IE" to "Ireland",
        "IN" to "India",
        "IT" to "Italy",
        "JP" to "Japan",
        "NL" to "Netherlands",
        "NO" to "Norway",
        "PL" to "Poland",
        "RO" to "Romania",
        "RS" to "Serbia",
        "SE" to "Sweden",
        "SG" to "Singapore",
        "US" to "United States",
    )

    private val EXTRA_NAMES = mapOf(
        "AE" to "United Arab Emirates",
        "AR" to "Argentina",
        "BG" to "Bulgaria",
        "BR" to "Brazil",
        "CL" to "Chile",
        "EE" to "Estonia",
        "GR" to "Greece",
        "HK" to "Hong Kong",
        "HU" to "Hungary",
        "IL" to "Israel",
        "IS" to "Iceland",
        "KR" to "South Korea",
        "LT" to "Lithuania",
        "LV" to "Latvia",
        "MD" to "Moldova",
        "MX" to "Mexico",
        "MY" to "Malaysia",
        "NZ" to "New Zealand",
        "PT" to "Portugal",
        "SK" to "Slovakia",
        "TR" to "Türkiye",
        "TW" to "Taiwan",
        "UA" to "Ukraine",
        "ZA" to "South Africa",
    )

    val RECOMMENDED: List<String> = listOf("de", "nl", "us", "fr", "gb", "ca", "se", "sg", "jp", "au")

    fun isCode(code: String): Boolean {
        val key = code.trim().uppercase(Locale.US)
        return key.length == 2 && key.all { it in 'A'..'Z' }
    }

    fun normalize(code: String?): String {
        val key = code?.trim()?.uppercase(Locale.US).orEmpty()
        return if (isCode(key)) key.lowercase(Locale.US) else AUTOMATIC
    }

    fun egressRegion(code: String?): String? {
        val normalized = normalize(code)
        return normalized.takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
    }

    fun name(code: String, locale: Locale = Locale.ENGLISH): String {
        val key = normalize(code).uppercase(Locale.US)
        if (key.isEmpty()) return ""
        val mapped = BUNDLED[key] ?: EXTRA_NAMES[key]
        if (mapped != null && locale.language == "en") return mapped
        return Locale("", key).getDisplayCountry(locale).ifBlank { mapped ?: key }
    }

    fun matches(code: String, query: String): Boolean {
        val needle = query.trim()
        if (needle.isBlank()) return true
        if (code.contains(needle, ignoreCase = true)) return true
        val english = name(code, Locale.ENGLISH)
        val persian = name(code, Locale("fa"))
        return english.contains(needle, ignoreCase = true) ||
            persian.contains(needle, ignoreCase = true)
    }

    fun matchesAutomatic(query: String): Boolean {
        val needle = query.trim()
        if (needle.isBlank()) return true
        val haystacks = listOf("auto", "automatic", "best", "pow", "psiphon", "خودکار", "اتوماتیک", "بهترین")
        return haystacks.any { it.contains(needle, ignoreCase = true) || needle.contains(it, ignoreCase = true) }
    }

    fun options(cached: Collection<String>): List<String> {
        val live = cached.map { it.trim().uppercase(Locale.US) }.filter(::isCode)
        return (BUNDLED.keys + live).distinct().map { it.lowercase(Locale.US) }.sortedBy { name(it) }
    }

    fun remember(context: Context, codes: List<String>) {
        PowEngineStore.get(context).rememberAvailableRegions(codes)
    }
}
