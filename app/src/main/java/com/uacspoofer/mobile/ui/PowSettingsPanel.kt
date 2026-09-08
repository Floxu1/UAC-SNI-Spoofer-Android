package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.pow.AetherNative
import com.uacspoofer.mobile.engine.pow.PowCoreConfig
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.ui.theme.UacColors

private data class PowTransportChoice(
    val id: String,
    val title: String,
    val subtitle: String,
)

@Composable
internal fun PowSettingsScreen(onBack: () -> Unit) {
    val accent = UacColors.DisconnectedBlue
    ToolPageScaffold(
        accent = accent,
        header = {
            ToolPageHeader(
                title = homeText("UAC PoW", "UAC PoW"),
                subtitle = homeText("Outer hop and MASQUE", "لایه بیرونی و MASQUE"),
                icon = Icons.Outlined.Hub,
                accent = accent,
                onMenuClick = onBack,
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                navigationDescription = homeText("Back to settings", "برگشت به تنظیمات"),
            )
        },
    ) {
        item { PowSettingsPanel() }
    }
}

@Composable
private fun PowSettingsPanel() {
    val context = LocalContext.current
    val store = remember(context) { PowEngineStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val accent = UacColors.DisconnectedBlue
    val choices = listOf(
        PowTransportChoice(
            PowCoreConfig.OUTER_AUTO,
            homeText("Auto", "خودکار"),
            homeText("Tries WireGuard, MASQUE, then WoW", "به‌ترتیب هر سه را امتحان می‌کند"),
        ),
        PowTransportChoice(
            "masque",
            "MASQUE",
            homeText("WARP over HTTP/3", "پرش HTTP/3 روی WARP"),
        ),
        PowTransportChoice(
            "wireguard",
            "WireGuard",
            homeText("WARP over WireGuard", "پرش WireGuard روی WARP"),
        ),
        PowTransportChoice(
            "gool",
            "WoW",
            homeText("WARP over WARP", "دو پرش WARP پشت‌سرهم"),
        ),
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!AetherNative.available) {
            Text(
                homeText(
                    AetherNative.unavailableReason,
                    "کتابخانه بومی UAC PoW برای این معماری موجود نیست.",
                ),
                color = UacColors.ErrorRed,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UacColors.ErrorRed.copy(alpha = 0.08f), ToolCardShape)
                    .border(1.dp, UacColors.ErrorRed.copy(alpha = 0.24f), ToolCardShape)
                    .padding(14.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ToolCardBrush, ToolCardShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(homeText("Outer hop", "لایه بیرونی"))
            Text(
                homeText(
                    "Auto tries WireGuard, then MASQUE, then WoW. Pinning one never falls back.",
                    "خودکار به‌ترتیب WireGuard، سپس MASQUE، سپس WoW را امتحان می‌کند. پین کردن به بقیه برنمی‌گردد.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { choice ->
                            PowTransportCard(
                                title = choice.title,
                                subtitle = choice.subtitle,
                                selected = settings.outerTransport == choice.id,
                                accent = accent,
                                onClick = { store.save(settings.copy(outerTransport = choice.id)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ToolCardBrush, ToolCardShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(homeText("If connect fails", "اگر وصل نمی‌شود"))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        homeText("Help getting through filters", "کمک برای رد شدن از فیلتر"),
                        color = UacColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        homeText(
                            "Turn on only if you cannot connect. Traffic is sent in smaller pieces so filters are less likely to block it. Speed and ping usually get worse. Leave it off if you already connect. Only used with MASQUE.",
                            "فقط وقتی روشن کن که وصل نمی‌شود. بسته‌ها ریزتر فرستاده می‌شوند تا فیلتر سخت‌تر بگیرد. معمولاً کندتر می‌شود و پینگ بالا می‌رود. اگر وصل می‌شوی خاموش بماند. فقط روی گزینه MASQUE اثر دارد.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Switch(
                    checked = settings.h2Fragmentation,
                    onCheckedChange = { store.save(settings.copy(h2Fragmentation = it)) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = accent,
                        checkedThumbColor = Color.White,
                    ),
                )
            }
            Text(
                homeText(
                    "Applies on the next connect.",
                    "از اتصال بعدی اعمال می‌شود.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PowTransportCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val persian = LocalHomePersian.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(if (selected) accent else Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                if (selected) accent else Color.White.copy(alpha = 0.12f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = if (persian) Alignment.End else Alignment.Start,
    ) {
        Text(
            title,
            color = if (selected) Color.White else UacColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (persian) TextAlign.End else TextAlign.Start,
        )
        Text(
            subtitle,
            color = if (selected) Color.White.copy(alpha = 0.88f) else UacColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (persian) TextAlign.End else TextAlign.Start,
        )
    }
}
