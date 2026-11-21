package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.settings.BatteryPreset
import com.uacspoofer.mobile.settings.BatterySettingsStore
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.styleTokens

@Composable
internal fun BatteryManagementScreen(engineMode: EngineMode, onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { BatterySettingsStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val powStore = remember(context, engineMode) { if (engineMode.isPow) PowEngineStore.get(context) else null }
    val powOptimized = powStore?.settings?.collectAsStateWithLifecycle()?.value?.optimizedMode
    val connectionCheck = if (engineMode.isDirect) settings.lightDirectChecks else powOptimized
    val accent = styleTokens()?.gradientEnd ?: UacColors.ConnectingCyan

    PowDirectional {
        ToolPageScaffold(
            accent = accent,
            textStyle = homeLocalizedTextStyle().copy(textAlign = TextAlign.Start),
            header = {
                ToolPageHeader(
                    title = homeText("Battery Management", "مدیریت مصرف باتری"),
                    subtitle = homeText("Changes save automatically", "تغییرات خودکار ذخیره می‌شوند"),
                    icon = Icons.Outlined.BatteryChargingFull,
                    accent = accent,
                    onMenuClick = onMenuClick,
                    navigationDescription = homeText("Open menu", "باز کردن منو"),
                )
            },
        ) {
            item { BatteryScore(settings.score(connectionCheck), if (connectionCheck == null) 3 else 4, accent) }
            item {
                PowCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BatterySectionTitle(homeText("Power mode", "حالت مصرف"))
                        Row(
                            modifier = Modifier.fillMaxWidth().selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BatteryPreset.entries.forEach { preset ->
                                val maxSaver = preset == BatteryPreset.MAX_SAVER
                                val selected = settings == preset.forEngine(settings, engineMode.isDirect) &&
                                    (!maxSaver || powOptimized != false)
                                val shape = RoundedCornerShape(10.dp)
                                Box(
                                    modifier = Modifier.weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(shape)
                                        .background(if (selected) accent.copy(alpha = 0.15f) else UacColors.Surface)
                                        .border(1.dp, if (selected) accent else UacColors.CardBorder, shape)
                                        .selectable(selected, role = Role.RadioButton) {
                                            store.save(preset.forEngine(store.snapshot(), engineMode.isDirect))
                                            if (maxSaver && powStore != null && powOptimized == false) {
                                                powStore.save(powStore.snapshot().copy(optimizedMode = true))
                                            }
                                        }
                                        .padding(horizontal = 5.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = when (preset) {
                                            BatteryPreset.BALANCED -> homeText("Balanced", "متعادل")
                                            BatteryPreset.SAVER -> homeText("Saver", "کم‌مصرف")
                                            BatteryPreset.MAX_SAVER -> homeText("Max saver", "فوق‌کم‌مصرف")
                                        },
                                        color = if (selected) accent else UacColors.TextPrimary,
                                        textAlign = TextAlign.Center,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatterySectionTitle(homeText("Battery-saving settings", "گزینه‌های صرفه‌جویی"))
                    PowCard {
                        if (engineMode.isDirect) {
                            PowSwitchRow(
                                icon = Icons.Outlined.NetworkCheck,
                                title = homeText("Lighter Direct checks", "بررسی سبک اتصال دایرکت"),
                                subtitle = homeText(
                                    "Runs fewer checks while the screen is off.",
                                    "با صفحهٔ خاموش، دایرکت کمتر بررسی می‌شود.",
                                ),
                                checked = settings.lightDirectChecks,
                                accent = accent,
                                onCheckedChange = { store.save(settings.copy(lightDirectChecks = it)) },
                            )
                            PowRowDivider()
                        } else if (powStore != null && powOptimized != null) {
                            PowSwitchRow(
                                icon = Icons.Outlined.NetworkCheck,
                                title = homeText("Lighter PoW checks", "بررسی سبک اتصال PoW"),
                                subtitle = homeText(
                                    "Uses lighter checks from the next connection.",
                                    "بررسی‌های کم‌مصرف‌تر از اتصال بعدی.",
                                ),
                                checked = powOptimized,
                                accent = accent,
                                onCheckedChange = { powStore.save(powStore.snapshot().copy(optimizedMode = it)) },
                            )
                            PowRowDivider()
                        }
                        PowSwitchRow(
                            icon = Icons.Outlined.BarChart,
                            title = homeText("Background traffic stats", "آمار در پس‌زمینه"),
                            subtitle = homeText(
                                "Refreshes traffic stats less often with the screen off.",
                                "وقتی صفحه خاموش است، آمار مصرف دیرتر تازه می‌شود.",
                            ),
                            checked = settings.slowBackgroundStats,
                            accent = accent,
                            onCheckedChange = { store.save(settings.copy(slowBackgroundStats = it)) },
                        )
                        PowRowDivider()
                        PowSwitchRow(
                            icon = Icons.Outlined.NotificationsNone,
                            title = homeText("Lighter VPN notification", "اعلان کم‌مصرف"),
                            subtitle = homeText(
                                "Refreshes the speed in the VPN notification less often.",
                                "عدد سرعت در اعلان اتصال دیرتر تازه می‌شود.",
                            ),
                            checked = settings.slowNotification,
                            accent = accent,
                            onCheckedChange = { store.save(settings.copy(slowNotification = it)) },
                        )
                        PowRowDivider()
                        PowSwitchRow(
                            icon = Icons.Outlined.AutoAwesome,
                            title = homeText("Still home effects", "جلوه‌های ثابت خانه"),
                            subtitle = homeText(
                                "Keeps the dots on the home screen still.",
                                "نقطه‌های پایین صفحهٔ خانه ثابت می‌مانند.",
                            ),
                            checked = settings.staticHomeEffects,
                            accent = accent,
                            onCheckedChange = { store.save(settings.copy(staticHomeEffects = it)) },
                        )
                    }
                }
            }
            item { Box(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun BatteryScore(score: Int, segments: Int, accent: Color) {
    PowCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PowIconTile(Icons.Outlined.BatteryChargingFull, accent)
                Box(Modifier.weight(1f)) {
                    BatterySectionTitle(homeText("Optimization level", "میزان بهینه‌سازی"))
                }
                Text(
                    text = homeText("$score%", homeLtr("$score٪")),
                    color = accent,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().progressSemantics(score / 100f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(segments) { index ->
                    Box(
                        Modifier.weight(1f).height(6.dp).background(
                            color = if (score >= (index + 1) * 100 / segments) accent else UacColors.CardBorder,
                            shape = RoundedCornerShape(3.dp),
                        ),
                    )
                }
            }
            Text(
                text = homeText(
                    "This score shows how many battery-saving settings are on.",
                    "این درصد بر اساس گزینه‌های صرفه‌جوییِ روشن محاسبه می‌شود.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun BatterySectionTitle(text: String) {
    Text(text, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}
