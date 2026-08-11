package com.uacspoofer.mobile.profiles

import java.util.Locale


data class CountryMetadata(
    val countryCode: String?,
    val countryName: String,
) {
    val isKnown: Boolean get() = countryCode != null

    val flagEmoji: String?
        get() = countryCode?.let { code ->
            buildString {
                code.forEach { letter -> appendCodePoint(0x1F1E6 + (letter - 'A')) }
            }
        }

    companion object {
        val UNKNOWN = CountryMetadata(null, "Location unavailable")

        private val isoNames: Map<String, String> by lazy {
            Locale.getISOCountries().associateWith { code ->
                Locale("", code).getDisplayCountry(Locale.ENGLISH)
            }
        }

        fun resolve(code: String?, name: String?): CountryMetadata {
            val rawName = name?.trim().orEmpty()
            val explicitCode = code?.trim()?.uppercase(Locale.ROOT).orEmpty()
            val nameAsCode = rawName.takeIf { it.length == 2 }?.uppercase(Locale.ROOT).orEmpty()
            val resolvedCode = when {
                explicitCode in isoNames -> explicitCode
                nameAsCode in isoNames -> nameAsCode
                rawName.isNotBlank() -> isoNames.entries.firstOrNull {
                    it.value.equals(rawName, ignoreCase = true)
                }?.key
                else -> null
            }
            return resolvedCode?.let { CountryMetadata(it, isoNames.getValue(it)) } ?: UNKNOWN
        }
    }
}
