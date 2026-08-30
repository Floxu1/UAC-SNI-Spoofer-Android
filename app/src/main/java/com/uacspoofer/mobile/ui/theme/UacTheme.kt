package com.uacspoofer.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.ProvideFixedFontScale

object UacColors {
    val BackgroundTop = Color(0xFF020913)
    val BackgroundMiddle = Color(0xFF04101C)
    val BackgroundBottom = Color(0xFF071421)
    val Surface = Color(0xFF101C29)
    val DisconnectedBlue = Color(0xFF299EFF)
    val ConnectingCyan = Color(0xFF27D7FF)
    val ConnectedGreen = Color(0xFF25F58A)
    val DisconnectingAmber = Color(0xFFFFB44A)
    val ErrorRed = Color(0xFFFF3344)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8D99A6)
    val CardBorder = Color(0x20FFFFFF)
    val Divider = Color(0x24FFFFFF)
    val ButtonCenter = Color(0xFF172536)
    val ButtonEdge = Color(0xFF07111D)
    val ButtonInnerRing = Color(0xFF40536A)
}

data class UiStateColors(val accent: Color)

fun colorsFor(state: ConnectionState): UiStateColors = when (state) {
    ConnectionState.DISCONNECTED -> UiStateColors(UacColors.DisconnectedBlue)
    ConnectionState.CONNECTING -> UiStateColors(UacColors.ConnectingCyan)
    ConnectionState.CONNECTED -> UiStateColors(UacColors.ConnectedGreen)
    ConnectionState.DISCONNECTING -> UiStateColors(UacColors.DisconnectingAmber)
    ConnectionState.ERROR -> UiStateColors(UacColors.ErrorRed)
}

private val UacDarkColorScheme = darkColorScheme(
    primary = UacColors.ConnectedGreen,
    secondary = UacColors.DisconnectedBlue,
    background = UacColors.BackgroundTop,
    surface = UacColors.Surface,
    onPrimary = UacColors.BackgroundTop,
    onBackground = UacColors.TextPrimary,
    onSurface = UacColors.TextPrimary,
    error = UacColors.ErrorRed,
)

@Composable
fun UacSniSpooferTheme(content: @Composable () -> Unit) {
    ProvideFixedFontScale {
        MaterialTheme(
            colorScheme = UacDarkColorScheme,
            content = content,
        )
    }
}
