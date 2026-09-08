package com.uacspoofer.mobile.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import com.uacspoofer.mobile.R

internal val LocalHomePersian = staticCompositionLocalOf { false }

private val VazirmatnUiFd = FontFamily(
    Font(R.font.vazirmatn_ui_fd_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_ui_fd_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_ui_fd_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_ui_fd_bold, FontWeight.Bold),
)

/** Forces a Persian paragraph, even inside an LTR Compose layout. */
private const val RLM = '\u200F'

/** Left-to-right isolate so English terms stay one unit inside RTL. */
private const val LRI = '\u2068'
private const val PDI = '\u2069'

@Composable
internal fun homeText(english: String, persian: String): String =
    if (LocalHomePersian.current) localizePersian(persian) else english

@Composable
internal fun homeLocalizedFont(): FontFamily? =
    if (LocalHomePersian.current) VazirmatnUiFd else null

@Composable
internal fun homeLocalizedTextStyle(base: TextStyle = LocalTextStyle.current): TextStyle {
    val persian = LocalHomePersian.current
    val font = homeLocalizedFont()
    return base.copy(
        fontFamily = font ?: base.fontFamily,
        textDirection = if (persian) TextDirection.Rtl else base.textDirection,
        textAlign = if (persian) TextAlign.End else base.textAlign,
    )
}

internal fun homeLtr(value: String): String = "$LRI$value$PDI"

internal fun localizePersian(text: String): String {
    val isolated = isolateUnwrappedLtrRuns(text.replace("\u200C", "\u2060\u200C\u2060"))
    return if (isolated.startsWith(RLM) || isolated.startsWith('\u2067') || isolated.startsWith('\u202B')) {
        isolated
    } else {
        "$RLM$isolated"
    }
}

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
                    output.append(LRI).append(text, start, index).append(PDI)
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
    isAsciiLetterOrDigit() || this in "/+_#@%&=\\|-()[]{}'\""
