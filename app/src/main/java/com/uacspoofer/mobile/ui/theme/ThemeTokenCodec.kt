package com.uacspoofer.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

internal object ThemeTokenCodec {
    fun encode(tokens: NeonTokens): JSONObject = JSONObject().also { json ->
        Fields(json, reading = false).transfer(tokens)
    }

    fun decode(json: JSONObject, base: NeonTokens): NeonTokens =
        Fields(json, reading = true).transfer(base)

    private class Fields(private val json: JSONObject, private val reading: Boolean) {
        private fun value(key: String, default: Int): Int =
            if (reading) json.optInt(key, default) else default.also { json.put(key, it) }

        private fun value(key: String, default: Float): Float =
            if (reading) {
                json.optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() } ?: default
            } else {
                default.also { json.put(key, it.toDouble()) }
            }

        private fun value(key: String, default: Boolean): Boolean =
            if (reading) json.optBoolean(key, default) else default.also { json.put(key, it) }

        private fun value(key: String, default: Color): Color =
            if (reading) {
                (json.opt(key) as? Number)?.toInt()?.let { Color(it) } ?: default
            } else {
                default.also { json.put(key, if (it.isSpecified) it.toArgb() else JSONObject.NULL) }
            }

        // Storage keys survive R8 and missing fields use the selected base theme's defaults.
        fun transfer(base: NeonTokens): NeonTokens = base.copy(
            bgTop = value("bgTop", base.bgTop),
            bgMid = value("bgMid", base.bgMid),
            bgBottom = value("bgBottom", base.bgBottom),
            bgGlowAlpha = value("bgGlowAlpha", base.bgGlowAlpha),
            bgGlowMidAlpha = value("bgGlowMidAlpha", base.bgGlowMidAlpha),
            bgGlowFollowState = value("bgGlowFollowState", base.bgGlowFollowState),
            bgGlow = value("bgGlow", base.bgGlow),
            titleSize = value("titleSize", base.titleSize),
            titleWeight = value("titleWeight", base.titleWeight),
            titleAlpha = value("titleAlpha", base.titleAlpha),
            titleStart = value("titleStart", base.titleStart),
            titleMid = value("titleMid", base.titleMid),
            titleEnd = value("titleEnd", base.titleEnd),
            titleGlow = value("titleGlow", base.titleGlow),
            titleGlowAlpha = value("titleGlowAlpha", base.titleGlowAlpha),
            titleGlowBlur = value("titleGlowBlur", base.titleGlowBlur),
            titlePad = value("titlePad", base.titlePad),
            underlineWidth = value("underlineWidth", base.underlineWidth),
            underlineHeight = value("underlineHeight", base.underlineHeight),
            underlineStart = value("underlineStart", base.underlineStart),
            underlineMid = value("underlineMid", base.underlineMid),
            buttonScale = value("buttonScale", base.buttonScale),
            buttonAlpha = value("buttonAlpha", base.buttonAlpha),
            glowExtra = value("glowExtra", base.glowExtra),
            ringWidth = value("ringWidth", base.ringWidth),
            ringCoreWidth = value("ringCoreWidth", base.ringCoreWidth),
            ringCoreAlpha = value("ringCoreAlpha", base.ringCoreAlpha),
            ringCore = value("ringCore", base.ringCore),
            gradientAngle = value("gradientAngle", base.gradientAngle),
            gradientStart = value("gradientStart", base.gradientStart),
            gradientEnd = value("gradientEnd", base.gradientEnd),
            hairlineCount = value("hairlineCount", base.hairlineCount),
            hairlineStart = value("hairlineStart", base.hairlineStart),
            hairlineGap = value("hairlineGap", base.hairlineGap),
            hairlineWidth = value("hairlineWidth", base.hairlineWidth),
            hairlineAlpha1 = value("hairlineAlpha1", base.hairlineAlpha1),
            hairlineAlpha2 = value("hairlineAlpha2", base.hairlineAlpha2),
            hairlineAlpha3 = value("hairlineAlpha3", base.hairlineAlpha3),
            hairlineAlpha4 = value("hairlineAlpha4", base.hairlineAlpha4),
            glowBlur = value("glowBlur", base.glowBlur),
            glowBlurConnected = value("glowBlurConnected", base.glowBlurConnected),
            glowAlpha = value("glowAlpha", base.glowAlpha),
            glowSpread = value("glowSpread", base.glowSpread),
            glowDisconnected = value("glowDisconnected", base.glowDisconnected),
            connectOrbitMs = value("connectOrbitMs", base.connectOrbitMs),
            connectSweepMin = value("connectSweepMin", base.connectSweepMin),
            connectSweepMax = value("connectSweepMax", base.connectSweepMax),
            connectNearBlur = value("connectNearBlur", base.connectNearBlur),
            connectFarBlur = value("connectFarBlur", base.connectFarBlur),
            connectNearAlpha = value("connectNearAlpha", base.connectNearAlpha),
            connectFarAlpha = value("connectFarAlpha", base.connectFarAlpha),
            connectHeadRadius = value("connectHeadRadius", base.connectHeadRadius),
            innerGlowBlur = value("innerGlowBlur", base.innerGlowBlur),
            innerGlowAlpha = value("innerGlowAlpha", base.innerGlowAlpha),
            innerGlowInset = value("innerGlowInset", base.innerGlowInset),
            centerInner = value("centerInner", base.centerInner),
            centerOuter = value("centerOuter", base.centerOuter),
            iconScale = value("iconScale", base.iconScale),
            iconIdle = value("iconIdle", base.iconIdle),
            iconActive = value("iconActive", base.iconActive),
            labelSize = value("labelSize", base.labelSize),
            labelWeight = value("labelWeight", base.labelWeight),
            labelWeightFa = value("labelWeightFa", base.labelWeightFa),
            labelIdle = value("labelIdle", base.labelIdle),
            labelActive = value("labelActive", base.labelActive),
            labelGlowAlpha = value("labelGlowAlpha", base.labelGlowAlpha),
            labelGlowBlur = value("labelGlowBlur", base.labelGlowBlur),
            statusSize = value("statusSize", base.statusSize),
            statusWeight = value("statusWeight", base.statusWeight),
            statusAlpha = value("statusAlpha", base.statusAlpha),
            statusUseAccent = value("statusUseAccent", base.statusUseAccent),
            statusColor = value("statusColor", base.statusColor),
            hintSize = value("hintSize", base.hintSize),
            hintWeight = value("hintWeight", base.hintWeight),
            hintColor = value("hintColor", base.hintColor),
            hintAlpha = value("hintAlpha", base.hintAlpha),
            hintPad = value("hintPad", base.hintPad),
            rowWidth = value("rowWidth", base.rowWidth),
            rowHeight = value("rowHeight", base.rowHeight),
            rowPadH = value("rowPadH", base.rowPadH),
            rowAlpha = value("rowAlpha", base.rowAlpha),
            dotSize = value("dotSize", base.dotSize),
            dotColor = value("dotColor", base.dotColor),
            selectedLabelSize = value("selectedLabelSize", base.selectedLabelSize),
            selectedLabelWeight = value("selectedLabelWeight", base.selectedLabelWeight),
            selectedLabelWeightFa = value("selectedLabelWeightFa", base.selectedLabelWeightFa),
            selectedLabelColor = value("selectedLabelColor", base.selectedLabelColor),
            selectedValueSize = value("selectedValueSize", base.selectedValueSize),
            selectedValueWeight = value("selectedValueWeight", base.selectedValueWeight),
            selectedValueColor = value("selectedValueColor", base.selectedValueColor),
            selectedChevron = value("selectedChevron", base.selectedChevron),
            trafficWidth = value("trafficWidth", base.trafficWidth),
            cardHeight = value("cardHeight", base.cardHeight),
            cardCorner = value("cardCorner", base.cardCorner),
            cardBorder = value("cardBorder", base.cardBorder),
            cardBorderColor = value("cardBorderColor", base.cardBorderColor),
            cardBorderStart = value("cardBorderStart", base.cardBorderStart),
            cardBorderEnd = value("cardBorderEnd", base.cardBorderEnd),
            cardGlowColorStart = value("cardGlowColorStart", base.cardGlowColorStart),
            cardGlowColorEnd = value("cardGlowColorEnd", base.cardGlowColorEnd),
            cardGlowBlur = value("cardGlowBlur", base.cardGlowBlur),
            cardGlowAlpha = value("cardGlowAlpha", base.cardGlowAlpha),
            cardGlowSpread = value("cardGlowSpread", base.cardGlowSpread),
            cardFillStart = value("cardFillStart", base.cardFillStart),
            cardFillEnd = value("cardFillEnd", base.cardFillEnd),
            cardPadH = value("cardPadH", base.cardPadH),
            cardAlpha = value("cardAlpha", base.cardAlpha),
            cardIconBox = value("cardIconBox", base.cardIconBox),
            cardIcon = value("cardIcon", base.cardIcon),
            cardIconFollowState = value("cardIconFollowState", base.cardIconFollowState),
            cardIconColor = value("cardIconColor", base.cardIconColor),
            cardLabelSize = value("cardLabelSize", base.cardLabelSize),
            cardLabelWeight = value("cardLabelWeight", base.cardLabelWeight),
            cardLabelWeightFa = value("cardLabelWeightFa", base.cardLabelWeightFa),
            cardLabelColor = value("cardLabelColor", base.cardLabelColor),
            cardValueSize = value("cardValueSize", base.cardValueSize),
            cardValueWeight = value("cardValueWeight", base.cardValueWeight),
            cardValueColor = value("cardValueColor", base.cardValueColor),
            cardBarHeight = value("cardBarHeight", base.cardBarHeight),
            cardBarStart = value("cardBarStart", base.cardBarStart),
            cardBarEnd = value("cardBarEnd", base.cardBarEnd),
            cardTotalSize = value("cardTotalSize", base.cardTotalSize),
            cardTotalColor = value("cardTotalColor", base.cardTotalColor),
            insightWidth = value("insightWidth", base.insightWidth),
            insightHeight = value("insightHeight", base.insightHeight),
            insightCorner = value("insightCorner", base.insightCorner),
            insightBorder = value("insightBorder", base.insightBorder),
            insightBorderColor = value("insightBorderColor", base.insightBorderColor),
            insightFill = value("insightFill", base.insightFill),
            insightAlpha = value("insightAlpha", base.insightAlpha),
            insightIcon = value("insightIcon", base.insightIcon),
            insightIconColor = value("insightIconColor", base.insightIconColor),
            insightLabelSize = value("insightLabelSize", base.insightLabelSize),
            insightLabelWeight = value("insightLabelWeight", base.insightLabelWeight),
            insightValueSize = value("insightValueSize", base.insightValueSize),
            insightValueWeight = value("insightValueWeight", base.insightValueWeight),
            insightDetailSize = value("insightDetailSize", base.insightDetailSize),
            insightGood = value("insightGood", base.insightGood),
            insightMid = value("insightMid", base.insightMid),
            insightBad = value("insightBad", base.insightBad),
            railWidth = value("railWidth", base.railWidth),
            railItem = value("railItem", base.railItem),
            railItemOn = value("railItemOn", base.railItemOn),
            railPad = value("railPad", base.railPad),
            railGap = value("railGap", base.railGap),
            railCorner = value("railCorner", base.railCorner),
            railShadow = value("railShadow", base.railShadow),
            railFillTop = value("railFillTop", base.railFillTop),
            railFillBottom = value("railFillBottom", base.railFillBottom),
            railBorder = value("railBorder", base.railBorder),
            railBorderColor = value("railBorderColor", base.railBorderColor),
            railSelected = value("railSelected", base.railSelected),
            railIdleAlpha = value("railIdleAlpha", base.railIdleAlpha),
            railIcon = value("railIcon", base.railIcon),
            railLabelSize = value("railLabelSize", base.railLabelSize),
            railLabelWeight = value("railLabelWeight", base.railLabelWeight),
            railAlpha = value("railAlpha", base.railAlpha),
            menuButton = value("menuButton", base.menuButton),
            menuIcon = value("menuIcon", base.menuIcon),
            menuColor = value("menuColor", base.menuColor),
            menuAlpha = value("menuAlpha", base.menuAlpha),
            waveHeight = value("waveHeight", base.waveHeight),
            waveStart = value("waveStart", base.waveStart),
            waveEnd = value("waveEnd", base.waveEnd),
            waveGlowBlur = value("waveGlowBlur", base.waveGlowBlur),
            waveGlowAlpha = value("waveGlowAlpha", base.waveGlowAlpha),
            waveAlpha = value("waveAlpha", base.waveAlpha),
            pageColors = pageColors(base.pageColors),
        )

        private fun pageColors(base: PageThemeColors): PageThemeColors {
            val nested = if (reading) json.optJSONObject("pageColors") ?: return base
                else JSONObject().also { json.put("pageColors", it) }
            return Fields(nested, reading).transferColors(base)
        }

        private fun transferColors(base: PageThemeColors): PageThemeColors = base.copy(
            pageTop = value("pageTop", base.pageTop),
            pageMiddle = value("pageMiddle", base.pageMiddle),
            pageBottom = value("pageBottom", base.pageBottom),
            pageSurface = value("pageSurface", base.pageSurface),
            settingsSurface = value("settingsSurface", base.settingsSurface),
            drawerSurface = value("drawerSurface", base.drawerSurface),
            pageText = value("pageText", base.pageText),
            pageMutedText = value("pageMutedText", base.pageMutedText),
            pageBorder = value("pageBorder", base.pageBorder),
            pageDivider = value("pageDivider", base.pageDivider),
            idleAccent = value("idleAccent", base.idleAccent),
            connectingAccent = value("connectingAccent", base.connectingAccent),
            connectedAccent = value("connectedAccent", base.connectedAccent),
            disconnectingAccent = value("disconnectingAccent", base.disconnectingAccent),
            errorAccent = value("errorAccent", base.errorAccent),
            pageAccent = value("pageAccent", base.pageAccent),
            secondaryAccent = value("secondaryAccent", base.secondaryAccent),
            tertiaryAccent = value("tertiaryAccent", base.tertiaryAccent),
        )
    }
}
