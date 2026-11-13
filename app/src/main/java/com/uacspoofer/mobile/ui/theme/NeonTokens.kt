package com.uacspoofer.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Immutable
data class NeonTokens(
    val bgTop: Color = Color(0xFF21033F),
    val bgMid: Color = Color(0xFF0E0420),
    val bgBottom: Color = Color(0xFF160628),
    val bgGlowAlpha: Float = 0.04f,
    val bgGlowMidAlpha: Float = 0.016f,
    val bgGlowFollowState: Boolean = true,
    val bgGlow: Color = Color(0xFFFF2BD6),
    val titleSize: Float = 23f,
    val titleWeight: Int = 800,
    val titleAlpha: Float = 1f,
    val titleStart: Color = Color(0xFFFF2BD8),
    val titleMid: Color = Color(0xFF49FFF9),
    val titleEnd: Color = Color(0xFFFF2BD6),
    val titleGlow: Color = Color(0xFFFF2BD6),
    val titleGlowAlpha: Float = 0.62f,
    val titleGlowBlur: Float = 16f,
    val titlePad: Float = 64f,
    val underlineWidth: Float = 154f,
    val underlineHeight: Float = 2f,
    val underlineStart: Color = Color(0xFFFF2BD6),
    val underlineMid: Color = Color(0xFFFFFFFF),
    val buttonScale: Float = 1f,
    val buttonAlpha: Float = 1f,
    val glowExtra: Float = 0f,
    val ringWidth: Float = 9.401f,
    val ringCoreWidth: Float = 2.71f,
    val ringCoreAlpha: Float = 0.801f,
    val ringCore: Color = Color(0xFFFFFFFF),
    val gradientAngle: Float = 0f,
    val gradientStart: Color = Color(0xFFFF2BC4),
    val gradientEnd: Color = Color(0xFF38F1F3),
    val hairlineCount: Int = 4,
    val hairlineStart: Float = 6.182f,
    val hairlineGap: Float = 5.394f,
    val hairlineWidth: Float = 3.676f,
    val hairlineAlpha1: Float = 0.128f,
    val hairlineAlpha2: Float = 0.164f,
    val hairlineAlpha3: Float = 0.044f,
    val hairlineAlpha4: Float = 0f,
    val glowBlur: Float = 0f,
    val glowBlurConnected: Float = 56.723f,
    val glowAlpha: Float = 216.139f,
    val glowSpread: Float = 5.581f,
    val glowDisconnected: Float = 0.34f,
    val connectOrbitMs: Float = 2000f,
    val connectSweepMin: Float = 100f,
    val connectSweepMax: Float = 150f,
    val connectNearBlur: Float = 10f,
    val connectFarBlur: Float = 26f,
    val connectNearAlpha: Float = 1f,
    val connectFarAlpha: Float = 0.6f,
    val connectHeadRadius: Float = 2.5f,
    val innerGlowBlur: Float = 7.837f,
    val innerGlowAlpha: Float = 190.323f,
    val innerGlowInset: Float = 0f,
    val centerInner: Color = Color(0xFF12081C),
    val centerOuter: Color = Color(0xF401092A),
    val iconScale: Float = 0.211f,
    val iconIdle: Color = Color(0xFFF7F3FF),
    val iconActive: Color = Color(0xF92BE1E6),
    val labelSize: Float = 13.01f,
    val labelWeight: Int = 600,
    val labelWeightFa: Int = 700,
    val labelIdle: Color = Color(0xFFF6F3FF),
    val labelActive: Color = Color(0xFFFF2BB8),
    val labelGlowAlpha: Float = 0.68f,
    val labelGlowBlur: Float = 12.485f,
    val statusSize: Float = 15.079f,
    val statusWeight: Int = 500,
    val statusAlpha: Float = 1f,
    val statusUseAccent: Boolean = true,
    val statusColor: Color = Color(0xFFFF2BD6),
    val hintSize: Float = 11.5f,
    val hintWeight: Int = 400,
    val hintColor: Color = Color(0xFFA894C4),
    val hintAlpha: Float = 1f,
    val hintPad: Float = 28f,
    val rowWidth: Float = 0.8f,
    val rowHeight: Float = 44f,
    val rowPadH: Float = 12f,
    val rowAlpha: Float = 1f,
    val dotSize: Float = 6.5f,
    val dotColor: Color = Color(0xFFFF2BD6),
    val selectedLabelSize: Float = 11f,
    val selectedLabelWeight: Int = 400,
    val selectedLabelWeightFa: Int = 500,
    val selectedLabelColor: Color = Color(0xFFFF2BD6),
    val selectedValueSize: Float = 13.5f,
    val selectedValueWeight: Int = 500,
    val selectedValueColor: Color = Color(0xFF22E7FF),
    val selectedChevron: Float = 17f,
    val trafficWidth: Float = 0.863f,
    val cardHeight: Float = 104f,
    val cardCorner: Float = 21f,
    val cardBorder: Float = 1f,
    val cardBorderColor: Color = Color(0xE4FF2AD8),
    val cardBorderStart: Color = Color(0xE4FF2AD8),
    val cardBorderEnd: Color = Color(0xE4FF2AD8),
    val cardGlowColorStart: Color = Color(0xE4FF2AD8),
    val cardGlowColorEnd: Color = Color(0xE4FF2AD8),
    val cardGlowBlur: Float = 6.835f,
    val cardGlowAlpha: Float = 0.329f,
    val cardGlowSpread: Float = 7.196f,
    val cardFillStart: Color = Color(0xE6140824),
    val cardFillEnd: Color = Color(0xC0130519),
    val cardPadH: Float = 11f,
    val cardAlpha: Float = 1f,
    val cardIconBox: Float = 36f,
    val cardIcon: Float = 21f,
    val cardIconFollowState: Boolean = true,
    val cardIconColor: Color = Color(0xFFA061C9),
    val cardLabelSize: Float = 12f,
    val cardLabelWeight: Int = 400,
    val cardLabelWeightFa: Int = 500,
    val cardLabelColor: Color = Color(0xFF25EAFF),
    val cardValueSize: Float = 25.939f,
    val cardValueWeight: Int = 900,
    val cardValueColor: Color = Color(0xFFF900C2),
    val cardBarHeight: Float = 3f,
    val cardBarStart: Color = Color(0xFFFF2BD6),
    val cardBarEnd: Color = Color(0xFF22E7FF),
    val cardTotalSize: Float = 10.23f,
    val cardTotalColor: Color = Color(0xFFFFFFFF),
    val insightWidth: Float = 0.86f,
    val insightHeight: Float = 94f,
    val insightCorner: Float = 17f,
    val insightBorder: Float = 0.75f,
    val insightBorderColor: Color = Color(0x8CFF2BD6),
    val insightFill: Color = Color(0xD11A0B2E),
    val insightAlpha: Float = 1f,
    val insightIcon: Float = 21f,
    val insightIconColor: Color = Color(0xFF22E7FF),
    val insightLabelSize: Float = 9f,
    val insightLabelWeight: Int = 600,
    val insightValueSize: Float = 13f,
    val insightValueWeight: Int = 700,
    val insightDetailSize: Float = 8.5f,
    val insightGood: Color = Color(0xFF22E7FF),
    val insightMid: Color = Color(0xFFFF2BD6),
    val insightBad: Color = Color(0xFFFF4D6A),
    val railWidth: Float = 46f,
    val railItem: Float = 38f,
    val railItemOn: Float = 56f,
    val railPad: Float = 4f,
    val railGap: Float = 4f,
    val railCorner: Int = 50,
    val railShadow: Float = 16f,
    val railFillTop: Color = Color(0xE618052D),
    val railFillBottom: Color = Color(0xF010061C),
    val railBorder: Float = 1f,
    val railBorderColor: Color = Color(0xA1A9A9E3),
    val railSelected: Color = Color(0xEEFF1FCF),
    val railIdleAlpha: Float = 0.55f,
    val railIcon: Float = 22f,
    val railLabelSize: Float = 9.5f,
    val railLabelWeight: Int = 600,
    val railAlpha: Float = 1f,
    val menuButton: Float = 42f,
    val menuIcon: Float = 24f,
    val menuColor: Color = Color(0xFFFFFFFF),
    val menuAlpha: Float = 1f,
    val waveHeight: Float = 31.991f,
    val waveStart: Color = Color(0xFFFF2BD8),
    val waveEnd: Color = Color(0xFFEC22FF),
    val waveGlowBlur: Float = 8.102f,
    val waveGlowAlpha: Float = 0.926f,
    val waveAlpha: Float = 1f,
) {
    fun toKotlin(name: String = "NeonTokens"): String {
        fun c(color: Color) = "Color(0x%08X)".format(color.toArgb())
        fun n(value: Float): String {
            val text = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
            return text + "f"
        }
        return buildString {
            appendLine("$name(")
            appendLine("    bgTop = ${c(bgTop)},")
            appendLine("    bgMid = ${c(bgMid)},")
            appendLine("    bgBottom = ${c(bgBottom)},")
            appendLine("    bgGlowAlpha = ${n(bgGlowAlpha)},")
            appendLine("    bgGlowMidAlpha = ${n(bgGlowMidAlpha)},")
            appendLine("    bgGlowFollowState = $bgGlowFollowState,")
            appendLine("    bgGlow = ${c(bgGlow)},")
            appendLine("    titleSize = ${n(titleSize)},")
            appendLine("    titleWeight = $titleWeight,")
            appendLine("    titleAlpha = ${n(titleAlpha)},")
            appendLine("    titleStart = ${c(titleStart)},")
            appendLine("    titleMid = ${c(titleMid)},")
            appendLine("    titleEnd = ${c(titleEnd)},")
            appendLine("    titleGlow = ${c(titleGlow)},")
            appendLine("    titleGlowAlpha = ${n(titleGlowAlpha)},")
            appendLine("    titleGlowBlur = ${n(titleGlowBlur)},")
            appendLine("    titlePad = ${n(titlePad)},")
            appendLine("    underlineWidth = ${n(underlineWidth)},")
            appendLine("    underlineHeight = ${n(underlineHeight)},")
            appendLine("    underlineStart = ${c(underlineStart)},")
            appendLine("    underlineMid = ${c(underlineMid)},")
            appendLine("    buttonScale = ${n(buttonScale)},")
            appendLine("    buttonAlpha = ${n(buttonAlpha)},")
            appendLine("    glowExtra = ${n(glowExtra)},")
            appendLine("    ringWidth = ${n(ringWidth)},")
            appendLine("    ringCoreWidth = ${n(ringCoreWidth)},")
            appendLine("    ringCoreAlpha = ${n(ringCoreAlpha)},")
            appendLine("    ringCore = ${c(ringCore)},")
            appendLine("    gradientAngle = ${n(gradientAngle)},")
            appendLine("    gradientStart = ${c(gradientStart)},")
            appendLine("    gradientEnd = ${c(gradientEnd)},")
            appendLine("    hairlineCount = $hairlineCount,")
            appendLine("    hairlineStart = ${n(hairlineStart)},")
            appendLine("    hairlineGap = ${n(hairlineGap)},")
            appendLine("    hairlineWidth = ${n(hairlineWidth)},")
            appendLine("    hairlineAlpha1 = ${n(hairlineAlpha1)},")
            appendLine("    hairlineAlpha2 = ${n(hairlineAlpha2)},")
            appendLine("    hairlineAlpha3 = ${n(hairlineAlpha3)},")
            appendLine("    hairlineAlpha4 = ${n(hairlineAlpha4)},")
            appendLine("    glowBlur = ${n(glowBlur)},")
            appendLine("    glowBlurConnected = ${n(glowBlurConnected)},")
            appendLine("    glowAlpha = ${n(glowAlpha)},")
            appendLine("    glowSpread = ${n(glowSpread)},")
            appendLine("    glowDisconnected = ${n(glowDisconnected)},")
            appendLine("    connectOrbitMs = ${n(connectOrbitMs)},")
            appendLine("    connectSweepMin = ${n(connectSweepMin)},")
            appendLine("    connectSweepMax = ${n(connectSweepMax)},")
            appendLine("    connectNearBlur = ${n(connectNearBlur)},")
            appendLine("    connectFarBlur = ${n(connectFarBlur)},")
            appendLine("    connectNearAlpha = ${n(connectNearAlpha)},")
            appendLine("    connectFarAlpha = ${n(connectFarAlpha)},")
            appendLine("    connectHeadRadius = ${n(connectHeadRadius)},")
            appendLine("    innerGlowBlur = ${n(innerGlowBlur)},")
            appendLine("    innerGlowAlpha = ${n(innerGlowAlpha)},")
            appendLine("    innerGlowInset = ${n(innerGlowInset)},")
            appendLine("    centerInner = ${c(centerInner)},")
            appendLine("    centerOuter = ${c(centerOuter)},")
            appendLine("    iconScale = ${n(iconScale)},")
            appendLine("    iconIdle = ${c(iconIdle)},")
            appendLine("    iconActive = ${c(iconActive)},")
            appendLine("    labelSize = ${n(labelSize)},")
            appendLine("    labelWeight = $labelWeight,")
            appendLine("    labelWeightFa = $labelWeightFa,")
            appendLine("    labelIdle = ${c(labelIdle)},")
            appendLine("    labelActive = ${c(labelActive)},")
            appendLine("    labelGlowAlpha = ${n(labelGlowAlpha)},")
            appendLine("    labelGlowBlur = ${n(labelGlowBlur)},")
            appendLine("    statusSize = ${n(statusSize)},")
            appendLine("    statusWeight = $statusWeight,")
            appendLine("    statusAlpha = ${n(statusAlpha)},")
            appendLine("    statusUseAccent = $statusUseAccent,")
            appendLine("    statusColor = ${c(statusColor)},")
            appendLine("    hintSize = ${n(hintSize)},")
            appendLine("    hintWeight = $hintWeight,")
            appendLine("    hintColor = ${c(hintColor)},")
            appendLine("    hintAlpha = ${n(hintAlpha)},")
            appendLine("    hintPad = ${n(hintPad)},")
            appendLine("    rowWidth = ${n(rowWidth)},")
            appendLine("    rowHeight = ${n(rowHeight)},")
            appendLine("    rowPadH = ${n(rowPadH)},")
            appendLine("    rowAlpha = ${n(rowAlpha)},")
            appendLine("    dotSize = ${n(dotSize)},")
            appendLine("    dotColor = ${c(dotColor)},")
            appendLine("    selectedLabelSize = ${n(selectedLabelSize)},")
            appendLine("    selectedLabelWeight = $selectedLabelWeight,")
            appendLine("    selectedLabelWeightFa = $selectedLabelWeightFa,")
            appendLine("    selectedLabelColor = ${c(selectedLabelColor)},")
            appendLine("    selectedValueSize = ${n(selectedValueSize)},")
            appendLine("    selectedValueWeight = $selectedValueWeight,")
            appendLine("    selectedValueColor = ${c(selectedValueColor)},")
            appendLine("    selectedChevron = ${n(selectedChevron)},")
            appendLine("    trafficWidth = ${n(trafficWidth)},")
            appendLine("    cardHeight = ${n(cardHeight)},")
            appendLine("    cardCorner = ${n(cardCorner)},")
            appendLine("    cardBorder = ${n(cardBorder)},")
            appendLine("    cardBorderColor = ${c(cardBorderColor)},")
            appendLine("    cardBorderStart = ${c(cardBorderStart)},")
            appendLine("    cardBorderEnd = ${c(cardBorderEnd)},")
            appendLine("    cardGlowColorStart = ${c(cardGlowColorStart)},")
            appendLine("    cardGlowColorEnd = ${c(cardGlowColorEnd)},")
            appendLine("    cardGlowBlur = ${n(cardGlowBlur)},")
            appendLine("    cardGlowAlpha = ${n(cardGlowAlpha)},")
            appendLine("    cardGlowSpread = ${n(cardGlowSpread)},")
            appendLine("    cardFillStart = ${c(cardFillStart)},")
            appendLine("    cardFillEnd = ${c(cardFillEnd)},")
            appendLine("    cardPadH = ${n(cardPadH)},")
            appendLine("    cardAlpha = ${n(cardAlpha)},")
            appendLine("    cardIconBox = ${n(cardIconBox)},")
            appendLine("    cardIcon = ${n(cardIcon)},")
            appendLine("    cardIconFollowState = $cardIconFollowState,")
            appendLine("    cardIconColor = ${c(cardIconColor)},")
            appendLine("    cardLabelSize = ${n(cardLabelSize)},")
            appendLine("    cardLabelWeight = $cardLabelWeight,")
            appendLine("    cardLabelWeightFa = $cardLabelWeightFa,")
            appendLine("    cardLabelColor = ${c(cardLabelColor)},")
            appendLine("    cardValueSize = ${n(cardValueSize)},")
            appendLine("    cardValueWeight = $cardValueWeight,")
            appendLine("    cardValueColor = ${c(cardValueColor)},")
            appendLine("    cardBarHeight = ${n(cardBarHeight)},")
            appendLine("    cardBarStart = ${c(cardBarStart)},")
            appendLine("    cardBarEnd = ${c(cardBarEnd)},")
            appendLine("    cardTotalSize = ${n(cardTotalSize)},")
            appendLine("    cardTotalColor = ${c(cardTotalColor)},")
            appendLine("    insightWidth = ${n(insightWidth)},")
            appendLine("    insightHeight = ${n(insightHeight)},")
            appendLine("    insightCorner = ${n(insightCorner)},")
            appendLine("    insightBorder = ${n(insightBorder)},")
            appendLine("    insightBorderColor = ${c(insightBorderColor)},")
            appendLine("    insightFill = ${c(insightFill)},")
            appendLine("    insightAlpha = ${n(insightAlpha)},")
            appendLine("    insightIcon = ${n(insightIcon)},")
            appendLine("    insightIconColor = ${c(insightIconColor)},")
            appendLine("    insightLabelSize = ${n(insightLabelSize)},")
            appendLine("    insightLabelWeight = $insightLabelWeight,")
            appendLine("    insightValueSize = ${n(insightValueSize)},")
            appendLine("    insightValueWeight = $insightValueWeight,")
            appendLine("    insightDetailSize = ${n(insightDetailSize)},")
            appendLine("    insightGood = ${c(insightGood)},")
            appendLine("    insightMid = ${c(insightMid)},")
            appendLine("    insightBad = ${c(insightBad)},")
            appendLine("    railWidth = ${n(railWidth)},")
            appendLine("    railItem = ${n(railItem)},")
            appendLine("    railItemOn = ${n(railItemOn)},")
            appendLine("    railPad = ${n(railPad)},")
            appendLine("    railGap = ${n(railGap)},")
            appendLine("    railCorner = $railCorner,")
            appendLine("    railShadow = ${n(railShadow)},")
            appendLine("    railFillTop = ${c(railFillTop)},")
            appendLine("    railFillBottom = ${c(railFillBottom)},")
            appendLine("    railBorder = ${n(railBorder)},")
            appendLine("    railBorderColor = ${c(railBorderColor)},")
            appendLine("    railSelected = ${c(railSelected)},")
            appendLine("    railIdleAlpha = ${n(railIdleAlpha)},")
            appendLine("    railIcon = ${n(railIcon)},")
            appendLine("    railLabelSize = ${n(railLabelSize)},")
            appendLine("    railLabelWeight = $railLabelWeight,")
            appendLine("    railAlpha = ${n(railAlpha)},")
            appendLine("    menuButton = ${n(menuButton)},")
            appendLine("    menuIcon = ${n(menuIcon)},")
            appendLine("    menuColor = ${c(menuColor)},")
            appendLine("    menuAlpha = ${n(menuAlpha)},")
            appendLine("    waveHeight = ${n(waveHeight)},")
            appendLine("    waveStart = ${c(waveStart)},")
            appendLine("    waveEnd = ${c(waveEnd)},")
            appendLine("    waveGlowBlur = ${n(waveGlowBlur)},")
            appendLine("    waveGlowAlpha = ${n(waveGlowAlpha)},")
            appendLine("    waveAlpha = ${n(waveAlpha)},")
            append(")")
        }
    }
}

val SolarisTokens = NeonTokens(
    bgTop = Color(0xF7050217),
    bgMid = Color(0xFF070F33),
    bgBottom = Color(0xFF0D0E15),
    bgGlowAlpha = 0.04f,
    bgGlowMidAlpha = 0.016f,
    bgGlowFollowState = true,
    bgGlow = Color(0xFFFF2BD6),
    titleSize = 23f,
    titleWeight = 800,
    titleAlpha = 1f,
    titleStart = Color(0xFFFF562B),
    titleMid = Color(0xFFC4C1CA),
    titleEnd = Color(0xFFFC4108),
    titleGlow = Color(0xFFEA4A13),
    titleGlowAlpha = 0.62f,
    titleGlowBlur = 16f,
    titlePad = 64f,
    underlineWidth = 154f,
    underlineHeight = 2f,
    underlineStart = Color(0xFFFF562B),
    underlineMid = Color(0xFFFFFFFF),
    buttonScale = 1f,
    buttonAlpha = 1f,
    glowExtra = 0f,
    ringWidth = 9.401f,
    ringCoreWidth = 2.71f,
    ringCoreAlpha = 0.801f,
    ringCore = Color(0xFFFFFFFF),
    gradientAngle = 0f,
    gradientStart = Color(0xFFEC6511),
    gradientEnd = Color(0xFFF38238),
    hairlineCount = 4,
    hairlineStart = 6.182f,
    hairlineGap = 5.394f,
    hairlineWidth = 3.676f,
    hairlineAlpha1 = 0.128f,
    hairlineAlpha2 = 0.164f,
    hairlineAlpha3 = 0.044f,
    hairlineAlpha4 = 0f,
    glowBlur = 0f,
    glowBlurConnected = 21.197f,
    glowAlpha = 241.75f,
    glowSpread = -4.447f,
    glowDisconnected = 0.659f,
    innerGlowBlur = 7.837f,
    innerGlowAlpha = 190.323f,
    innerGlowInset = 0f,
    centerInner = Color(0xFF12081C),
    centerOuter = Color(0xF401092A),
    iconScale = 0.211f,
    iconIdle = Color(0xFFF7F3FF),
    iconActive = Color(0xFFF2E3E6),
    labelSize = 13.01f,
    labelWeight = 600,
    labelWeightFa = 700,
    labelIdle = Color(0xFFF6F3FF),
    labelActive = Color(0xFFC9675B),
    labelGlowAlpha = 0.68f,
    labelGlowBlur = 12.485f,
    statusSize = 15.118f,
    statusWeight = 500,
    statusAlpha = 1f,
    statusUseAccent = false,
    statusColor = Color(0xFFFF652B),
    hintSize = 11.5f,
    hintWeight = 400,
    hintColor = Color(0xFFA894C4),
    hintAlpha = 1f,
    hintPad = 28f,
    rowWidth = 0.8f,
    rowHeight = 44f,
    rowPadH = 12f,
    rowAlpha = 1f,
    dotSize = 6.5f,
    dotColor = Color(0xFF959595),
    selectedLabelSize = 11f,
    selectedLabelWeight = 400,
    selectedLabelWeightFa = 500,
    selectedLabelColor = Color(0xFFA194C4),
    selectedValueSize = 13.5f,
    selectedValueWeight = 500,
    selectedValueColor = Color(0xFFFC6328),
    selectedChevron = 17f,
    trafficWidth = 0.863f,
    cardHeight = 104f,
    cardCorner = 21f,
    cardBorder = 1f,
    cardBorderColor = Color(0xE4FF682A),
    cardBorderStart = Color(0xE4FF682A),
    cardBorderEnd = Color(0xE4FF682A),
    cardGlowColorStart = Color(0xE4FF682A),
    cardGlowColorEnd = Color(0xE4FF682A),
    cardGlowBlur = 6.834f,
    cardGlowAlpha = 0.329f,
    cardGlowSpread = 7.028f,
    cardFillStart = Color(0xE6090824),
    cardFillEnd = Color(0xC0080519),
    cardPadH = 11f,
    cardAlpha = 1f,
    cardIconBox = 36f,
    cardIcon = 21f,
    cardIconFollowState = true,
    cardIconColor = Color(0xFF50FF22),
    cardLabelSize = 12f,
    cardLabelWeight = 400,
    cardLabelWeightFa = 500,
    cardLabelColor = Color(0xFF25EAFF),
    cardValueSize = 25.939f,
    cardValueWeight = 900,
    cardValueColor = Color(0xFFFF632B),
    cardBarHeight = 3f,
    cardBarStart = Color(0xFFFF6326),
    cardBarEnd = Color(0xFFFF6022),
    cardTotalSize = 10.23f,
    cardTotalColor = Color(0xFFFFFFFF),
    insightWidth = 0.86f,
    insightHeight = 94f,
    insightCorner = 17f,
    insightBorder = 0.75f,
    insightBorderColor = Color(0x8E6B6B5E),
    insightFill = Color(0xD1040425),
    insightAlpha = 1f,
    insightIcon = 21f,
    insightIconColor = Color(0xFFFF6022),
    insightLabelSize = 9f,
    insightLabelWeight = 600,
    insightValueSize = 13f,
    insightValueWeight = 700,
    insightDetailSize = 8.5f,
    insightGood = Color(0xFF90C8F8),
    insightMid = Color(0xFFF06808),
    insightBad = Color(0xFFFF4D6A),
    railWidth = 46f,
    railItem = 38f,
    railItemOn = 56f,
    railPad = 4f,
    railGap = 4f,
    railCorner = 50,
    railShadow = 16f,
    railFillTop = Color(0xE618052D),
    railFillBottom = Color(0xF010061C),
    railBorder = 1f,
    railBorderColor = Color(0xA1A9A9E3),
    railSelected = Color(0xF7FF631A),
    railIdleAlpha = 0.55f,
    railIcon = 22f,
    railLabelSize = 9.5f,
    railLabelWeight = 600,
    railAlpha = 1f,
    menuButton = 42f,
    menuIcon = 24f,
    menuColor = Color(0xFFFFFFFF),
    menuAlpha = 1f,
    waveHeight = 31.991f,
    waveStart = Color(0xFFFF642B),
    waveEnd = Color(0xFFFF6022),
    waveGlowBlur = 8.102f,
    waveGlowAlpha = 0.926f,
    waveAlpha = 1f,
)

object NeonLive {
    var tokens by mutableStateOf(NeonTokens())

    fun reset() {
        tokens = NeonTokens()
    }

    fun update(block: (NeonTokens) -> NeonTokens) {
        tokens = block(tokens)
    }
}

object SolarisLive {
    var tokens by mutableStateOf(SolarisTokens)

    fun reset() {
        tokens = SolarisTokens
    }

    fun update(block: (NeonTokens) -> NeonTokens) {
        tokens = block(tokens)
    }
}

internal fun styleTokens(): NeonTokens? = when (UacThemeState.theme) {
    AppTheme.SOLARIS -> SolarisLive.tokens
    AppTheme.NEON -> NeonLive.tokens
    AppTheme.CLASSIC -> null
}

internal fun neonSize(compact: Boolean, regular: Float, compactValue: Float, tuned: Float): Float {
    if (regular == 0f) return tuned
    val base = if (compact) compactValue else regular
    return base * tuned / regular
}

internal fun neonDp(compact: Boolean, regular: Float, compactValue: Float, tuned: Float): Dp =
    neonSize(compact, regular, compactValue, tuned).dp

internal fun neonSp(compact: Boolean, regular: Float, compactValue: Float, tuned: Float): TextUnit =
    neonSize(compact, regular, compactValue, tuned).sp
