package com.uacspoofer.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.uacspoofer.mobile.R

internal val LocalHomePersian = staticCompositionLocalOf { false }

private val VazirmatnUiFd = FontFamily(
    Font(R.font.vazirmatn_ui_fd_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_ui_fd_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_ui_fd_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_ui_fd_bold, FontWeight.Bold),
)

@Composable
internal fun homeText(english: String, persian: String): String =
    if (LocalHomePersian.current) {
        isolateUnwrappedLtrRuns(persian.replace("\u200C", "\u2060\u200C\u2060"))
    } else {
        english
    }

@Composable
internal fun homeLocalizedFont(): FontFamily? =
    if (LocalHomePersian.current) VazirmatnUiFd else null

internal fun homeLtr(value: String): String = "\u2066$value\u2069"

internal fun isolateUnwrappedLtrRuns(text: String): String {
    val output = StringBuilder(text.length + 12)
    var explicitIsolationDepth = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when (char) {
            '\u2066', '\u2067', '\u2068' -> {
                explicitIsolationDepth++
                output.append(char)
                index++
            }
            '\u2069' -> {
                explicitIsolationDepth = (explicitIsolationDepth - 1).coerceAtLeast(0)
                output.append(char)
                index++
            }
            else -> {
                if (explicitIsolationDepth == 0 && char.isAsciiLetterOrDigit()) {
                    val start = index
                    index++
                    while (index < text.length) {
                        val next = text[index]
                        if (next.isAsciiTechnicalChar()) {
                            index++
                            continue
                        }
                        if (next == ' ') {
                            var lookAhead = index + 1
                            while (lookAhead < text.length && text[lookAhead] == ' ') lookAhead++
                            if (lookAhead < text.length && text[lookAhead].isAsciiLetterOrDigit()) {
                                index = lookAhead
                                continue
                            }
                        }
                        break
                    }
                    output.append('\u2066').append(text, start, index).append('\u2069')
                } else {
                    output.append(char)
                    index++
                }
            }
        }
    }
    return output.toString()
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun Char.isAsciiTechnicalChar(): Boolean =
    isAsciiLetterOrDigit() || this in ".,;:!?/+_#@%&=\\|-()[]{}'\""
