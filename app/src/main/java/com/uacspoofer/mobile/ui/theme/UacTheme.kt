package com.uacspoofer.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.ProvideFixedFontScale

internal data class UacPalette(
    val neon: Boolean,
    val backgroundTop: Color,
    val backgroundMiddle: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val disconnected: Color,
    val connecting: Color,
    val connected: Color,
    val disconnecting: Color,
    val error: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val cardBorder: Color,
    val divider: Color,
    val buttonCenter: Color,
    val buttonEdge: Color,
    val buttonInnerRing: Color,
    val neonMagenta: Color,
    val neonCyan: Color,
    val neonViolet: Color,
)

private val ClassicPalette = UacPalette(
    neon = false,
    backgroundTop = Color(0xFF020913),
    backgroundMiddle = Color(0xFF04101C),
    backgroundBottom = Color(0xFF071421),
    surface = Color(0xFF101C29),
    disconnected = Color(0xFF299EFF),
    connecting = Color(0xFF27D7FF),
    connected = Color(0xFF25F58A),
    disconnecting = Color(0xFFFFB44A),
    error = Color(0xFFFF3344),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8D99A6),
    cardBorder = Color(0x20FFFFFF),
    divider = Color(0x24FFFFFF),
    buttonCenter = Color(0xFF172536),
    buttonEdge = Color(0xFF07111D),
    buttonInnerRing = Color(0xFF40536A),
    neonMagenta = Color(0xFF25F58A),
    neonCyan = Color(0xFF27D7FF),
    neonViolet = Color(0xFF299EFF),
)

private val NeonPalette = UacPalette(
    neon = true,
    backgroundTop = Color(0xFF07010F),
    backgroundMiddle = Color(0xFF0E0420),
    backgroundBottom = Color(0xFF160628),
    surface = Color(0xFF1A0B2E),
    disconnected = Color(0xFF8B7CFF),
    connecting = Color(0xFF22E7FF),
    connected = Color(0xFFFF2BD6),
    disconnecting = Color(0xFFC084FC),
    error = Color(0xFFFF4D6A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA894C4),
    cardBorder = Color(0x8CFF2BD6),
    divider = Color(0x33FFFFFF),
    buttonCenter = Color(0xFF12081C),
    buttonEdge = Color(0xFF07010F),
    buttonInnerRing = Color(0xFF3A2458),
    neonMagenta = Color(0xFFFF2BD6),
    neonCyan = Color(0xFF22E7FF),
    neonViolet = Color(0xFF9B5CFF),
)

private val SolarisPalette = NeonPalette.copy(
    backgroundTop = Color(0xF7050217),
    backgroundMiddle = Color(0xFF070F33),
    backgroundBottom = Color(0xFF0D0E15),
    surface = Color(0xFF10142E),
    disconnected = Color(0xFF90C8F8),
    connecting = Color(0xFF90C8F8),
    connected = Color(0xFFF06808),
    disconnecting = Color(0xFFE86810),
    textSecondary = Color(0xFFA894C4),
    cardBorder = Color(0x66F06808),
    buttonCenter = Color(0xFF12081C),
    buttonEdge = Color(0xFF070F33),
    neonMagenta = Color(0xFFF06808),
    neonCyan = Color(0xFF90C8F8),
    neonViolet = Color(0xFF90C8F8),
)

internal fun paletteFor(theme: AppTheme): UacPalette = when (theme) {
    AppTheme.CLASSIC -> ClassicPalette
    AppTheme.NEON -> NeonPalette
    AppTheme.SOLARIS -> SolarisPalette
}

internal object UacThemeState {
    var theme by mutableStateOf(AppTheme.NEON)
    var palette by mutableStateOf(ClassicPalette)
    val isNeon: Boolean get() = palette.neon
}

object UacColors {
    val BackgroundTop: Color get() = UacThemeState.palette.backgroundTop
    val BackgroundMiddle: Color get() = UacThemeState.palette.backgroundMiddle
    val BackgroundBottom: Color get() = UacThemeState.palette.backgroundBottom
    val Surface: Color get() = UacThemeState.palette.surface
    val DisconnectedBlue: Color get() = UacThemeState.palette.disconnected
    val ConnectingCyan: Color get() = UacThemeState.palette.connecting
    val ConnectedGreen: Color get() = UacThemeState.palette.connected
    val DisconnectingAmber: Color get() = UacThemeState.palette.disconnecting
    val ErrorRed: Color get() = UacThemeState.palette.error
    val TextPrimary: Color get() = UacThemeState.palette.textPrimary
    val TextSecondary: Color get() = UacThemeState.palette.textSecondary
    val CardBorder: Color get() = UacThemeState.palette.cardBorder
    val Divider: Color get() = UacThemeState.palette.divider
    val ButtonCenter: Color get() = UacThemeState.palette.buttonCenter
    val ButtonEdge: Color get() = UacThemeState.palette.buttonEdge
    val ButtonInnerRing: Color get() = UacThemeState.palette.buttonInnerRing
    val NeonMagenta: Color get() = UacThemeState.palette.neonMagenta
    val NeonCyan: Color get() = UacThemeState.palette.neonCyan
    val NeonViolet: Color get() = UacThemeState.palette.neonViolet
}

data class UiStateColors(val accent: Color)

fun colorsFor(state: ConnectionState): UiStateColors = when (state) {
    ConnectionState.DISCONNECTED -> UiStateColors(UacColors.DisconnectedBlue)
    ConnectionState.CONNECTING -> UiStateColors(UacColors.ConnectingCyan)
    ConnectionState.CONNECTED -> UiStateColors(UacColors.ConnectedGreen)
    ConnectionState.DISCONNECTING -> UiStateColors(UacColors.DisconnectingAmber)
    ConnectionState.ERROR -> UiStateColors(UacColors.ErrorRed)
}

@Composable
fun UacSniSpooferTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = androidx.compose.runtime.remember(context) { AppThemeStore.get(context) }
    val theme by store.theme.collectAsStateWithLifecycle()
    val palette = paletteFor(theme)
    SideEffect {
        if (UacThemeState.theme != theme) UacThemeState.theme = theme
        if (UacThemeState.palette != palette) UacThemeState.palette = palette
    }
    val scheme = darkColorScheme(
        primary = palette.connected,
        secondary = palette.disconnected,
        background = palette.backgroundTop,
        surface = palette.surface,
        onPrimary = palette.backgroundTop,
        onBackground = palette.textPrimary,
        onSurface = palette.textPrimary,
        error = palette.error,
    )
    ProvideFixedFontScale {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
