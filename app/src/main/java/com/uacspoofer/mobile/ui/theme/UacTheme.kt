package com.uacspoofer.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
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
    val settingsSurface: Color,
    val drawerSurface: Color = Color.Unspecified,
    val surfaceOverride: Color = Color.Unspecified,
    val dividerOverride: Color = Color.Unspecified,
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
    settingsSurface = Color(0xE6121C28),
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
    settingsSurface = Color(0xE6140824),
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
    settingsSurface = Color(0xE610142E),
)

internal fun Color.orDefault(default: Color): Color = if (isSpecified) this else default

internal fun paletteFor(theme: AppTheme, tokens: NeonTokens? = styleTokens(theme)): UacPalette {
    val base = when (theme) {
        AppTheme.CLASSIC -> ClassicPalette
        AppTheme.NEON -> NeonPalette
        AppTheme.SOLARIS -> SolarisPalette
    }
    val custom = tokens?.pageColors ?: return base
    return base.copy(
        backgroundTop = custom.pageTop.orDefault(base.backgroundTop),
        backgroundMiddle = custom.pageMiddle.orDefault(base.backgroundMiddle),
        backgroundBottom = custom.pageBottom.orDefault(base.backgroundBottom),
        surface = custom.pageSurface.orDefault(base.surface),
        surfaceOverride = custom.pageSurface,
        settingsSurface = custom.settingsSurface.orDefault(base.settingsSurface),
        drawerSurface = custom.drawerSurface,
        textPrimary = custom.pageText.orDefault(base.textPrimary),
        textSecondary = custom.pageMutedText.orDefault(base.textSecondary),
        cardBorder = custom.pageBorder.orDefault(base.cardBorder),
        divider = custom.pageDivider.orDefault(base.divider),
        dividerOverride = custom.pageDivider,
        disconnected = custom.idleAccent.orDefault(base.disconnected),
        connecting = custom.connectingAccent.orDefault(base.connecting),
        connected = custom.connectedAccent.orDefault(base.connected),
        disconnecting = custom.disconnectingAccent.orDefault(base.disconnecting),
        error = custom.errorAccent.orDefault(base.error),
        neonMagenta = custom.pageAccent.orDefault(base.neonMagenta),
        neonCyan = custom.secondaryAccent.orDefault(base.neonCyan),
        neonViolet = custom.tertiaryAccent.orDefault(base.neonViolet),
    )
}

internal object UacThemeState {
    var theme by mutableStateOf(AppTheme.CLASSIC)
    val palette by derivedStateOf(structuralEqualityPolicy()) { paletteFor(theme) }
    val isNeon: Boolean get() = palette.neon
}

object UacColors {
    val BackgroundTop: Color get() = UacThemeState.palette.backgroundTop
    val BackgroundMiddle: Color get() = UacThemeState.palette.backgroundMiddle
    val BackgroundBottom: Color get() = UacThemeState.palette.backgroundBottom
    val Surface: Color get() = UacThemeState.palette.surface
    val SurfaceOverride: Color get() = UacThemeState.palette.surfaceOverride
    val DividerOverride: Color get() = UacThemeState.palette.dividerOverride
    val SettingsSurface: Color get() = UacThemeState.palette.settingsSurface
    val DrawerSurface: Color get() = UacThemeState.palette.drawerSurface
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
    remember(context) { AppThemeStore.get(context) }
    val palette = UacThemeState.palette
    val scheme = darkColorScheme(
        primary = palette.connected,
        secondary = palette.disconnected,
        background = palette.backgroundTop,
        surface = palette.surface,
        onPrimary = palette.backgroundTop,
        onBackground = palette.textPrimary,
        onSurface = palette.textPrimary,
        onSurfaceVariant = palette.textSecondary,
        surfaceVariant = palette.settingsSurface,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface,
        outline = palette.cardBorder,
        outlineVariant = palette.divider,
        error = palette.error,
    )
    ProvideFixedFontScale {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
