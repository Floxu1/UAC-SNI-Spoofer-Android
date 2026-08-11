package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProxyProfile





internal fun prepareSniMakerProfile(profile: ProxyProfile): ProxyProfile {
    val fallback = buildString {
        append(profile.protocol.wireName.uppercase())
        profile.sni.ifBlank { profile.host }.takeIf(String::isNotBlank)?.let {
            append(" • ")
            append(it)
        }
    }
    return profile.copy(
        name = stripCountryFlags(profile.name).ifBlank { fallback },
        country = CountryMetadata.UNKNOWN,
    )
}


internal fun stripCountryFlags(value: String): String {
    val result = StringBuilder(value.length)
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        when {
            codePoint in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END -> {
                offset += Character.charCount(codePoint)
            }

            codePoint == BLACK_FLAG -> {
                
                offset += Character.charCount(codePoint)
                while (offset < value.length) {
                    val tagged = value.codePointAt(offset)
                    if (tagged !in TAG_START..TAG_END && tagged != CANCEL_TAG) break
                    offset += Character.charCount(tagged)
                }
            }

            else -> {
                result.appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
            }
        }
    }
    return result
        .toString()
        .replace(EMPTY_WRAPPER, " ")
        .replace(REPEATED_WHITESPACE, " ")
        .trim { character -> character.isWhitespace() || character in EDGE_SEPARATORS }
        .take(MAX_DISPLAY_NAME_CHARS)
}



private val EMPTY_WRAPPER = Regex("""\[\s*\]|\(\s*\)|\{\s*\}""")
private val REPEATED_WHITESPACE = Regex("""\s+""")
private const val EDGE_SEPARATORS = "|¦:;•·/\\_-–—"
private const val MAX_DISPLAY_NAME_CHARS = 80
private const val REGIONAL_INDICATOR_START = 0x1F1E6
private const val REGIONAL_INDICATOR_END = 0x1F1FF
private const val BLACK_FLAG = 0x1F3F4
private const val TAG_START = 0xE0020
private const val TAG_END = 0xE007E
private const val CANCEL_TAG = 0xE007F
