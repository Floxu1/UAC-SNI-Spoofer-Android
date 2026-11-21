package com.uacspoofer.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

class ThemeTokenCodecTest {
    @Test
    fun storesAllThemeValuesAndRestoresBothBaseStyles() {
        val fieldNames = NeonTokens::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        for (base in listOf(NeonTokens(), SolarisTokens)) {
            val custom = base.copy(
                bgTop = Color(0x804B326D),
                buttonScale = 1.175426f,
                titleWeight = 700,
                bgGlowFollowState = false,
            )
            val json = ThemeTokenCodec.encode(custom)
            assertEquals(fieldNames, json.keys().asSequence().toSet())
            assertEquals(custom, ThemeTokenCodec.decode(JSONObject(json.toString()), base))
        }
    }

    @Test
    fun missingValuesUseTheSavedThemesBaseDefaults() {
        val json = JSONObject().put("titleSize", 25.75)
        assertEquals(SolarisTokens.copy(titleSize = 25.75f), ThemeTokenCodec.decode(json, SolarisTokens))
    }

    @Test
    fun savedPageColorsOverrideOnlyTheirPaletteRoles() {
        val colors = PageThemeColors(
            pageSurface = Color(0xFF124A63),
            settingsSurface = Color(0x80452165),
            drawerSurface = Color.Transparent,
            connectedAccent = Color(0xFFFFAA33),
        )
        val custom = SolarisTokens.copy(pageColors = colors)
        val reopened = ThemeTokenCodec.decode(JSONObject(ThemeTokenCodec.encode(custom).toString()), SolarisTokens)
        val palette = paletteFor(AppTheme.SOLARIS, reopened)
        assertEquals(colors.pageSurface, palette.surface)
        assertEquals(colors.settingsSurface, palette.settingsSurface)
        assertEquals(Color.Transparent, palette.drawerSurface)
        assertEquals(colors.connectedAccent, palette.connected)
        assertEquals(paletteFor(AppTheme.SOLARIS, SolarisTokens).textPrimary, palette.textPrimary)
    }
}
