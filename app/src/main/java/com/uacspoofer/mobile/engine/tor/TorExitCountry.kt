package com.uacspoofer.mobile.engine.tor

import java.util.Locale

internal object TorExitCountry {
    const val AUTOMATIC = ""

    val RECOMMENDED: List<String> = listOf(
        "de", "nl", "us", "fr", "gb", "ch", "se", "ca", "at", "pl",
        "fi", "ro", "bg", "cz", "es", "it", "ua", "sg", "jp", "au",
    )

    private val isoCodes: Set<String> by lazy {
        Locale.getISOCountries().map { it.lowercase(Locale.US) }.toSet()
    }

    val allCodes: List<String> by lazy {
        isoCodes.sorted()
    }

    fun normalize(code: String?): String {
        val trimmed = code?.trim()?.lowercase(Locale.US).orEmpty()
        return if (trimmed.length == 2 && trimmed in isoCodes) trimmed else AUTOMATIC
    }

    fun isAutomatic(code: String?): Boolean = normalize(code).isEmpty()

    fun displayName(code: String, locale: Locale): String {
        val normalized = normalize(code)
        if (normalized.isEmpty()) return ""
        return Locale("", normalized.uppercase(Locale.US)).getDisplayCountry(locale).ifBlank {
            normalized.uppercase(Locale.US)
        }
    }

    fun matches(code: String, query: String): Boolean {
        val needle = query.trim()
        if (needle.isBlank()) return true
        if (code.contains(needle, ignoreCase = true)) return true
        val english = displayName(code, Locale.ENGLISH)
        val persian = displayName(code, Locale("fa"))
        return english.contains(needle, ignoreCase = true) ||
            persian.contains(needle, ignoreCase = true)
    }

    fun matchesAutomatic(query: String): Boolean {
        val needle = query.trim()
        if (needle.isBlank()) return true
        val haystacks = listOf("auto", "automatic", "best", "tor", "خودکار", "اتوماتیک", "بهترین")
        return haystacks.any { it.contains(needle, ignoreCase = true) || needle.contains(it, ignoreCase = true) }
    }

    fun controlCommands(countryCode: String, strict: Boolean): List<String> {
        val normalized = normalize(countryCode)
        return if (normalized.isEmpty()) {
            listOf("RESETCONF ExitNodes StrictNodes", "SIGNAL NEWNYM")
        } else {
            val strictBit = if (strict) 1 else 0
            listOf(
                """SETCONF ExitNodes="{$normalized}"""",
                "SETCONF StrictNodes=$strictBit",
                "SIGNAL NEWNYM",
            )
        }
    }

    fun exitNodesConfigured(getConfBody: String, countryCode: String): Boolean {
        val normalized = normalize(countryCode)
        val body = getConfBody.lowercase(Locale.US)
        if (normalized.isEmpty()) {
            return !body.contains("exitnodes={") && !Regex("exitnodes=.+").containsMatchIn(body)
        }
        return body.contains("{$normalized}")
    }
}
