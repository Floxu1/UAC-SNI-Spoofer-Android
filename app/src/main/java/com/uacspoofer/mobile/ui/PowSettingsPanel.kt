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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.uacspoofer.mobile.engine.pow.PowLearnedPathSnapshot
import com.uacspoofer.mobile.engine.pow.PowPathMemory
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
                subtitle = homeText("How it connects", "چطوری وصل بشه؟"),
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
            homeText("Auto (Recommended)", "خودکار (پیشنهادی)"),
            homeText("Let the app pick the best one", "بسپار به خودش، بهترین راه رو پیدا می‌کنه"),
        ),
        PowTransportChoice(
            "masque",
            "MASQUE",
            homeText("Often the fastest", "معمولا سریع‌تره"),
        ),
        PowTransportChoice(
            "wireguard",
            "WireGuard",
            homeText("Works on most networks", "روی بیشتر اینترنت‌ها خوب جواب میده"),
        ),
        PowTransportChoice(
            "gool",
            "WoW",
            homeText("Extra layer if others fail", "اگه بقیه وصل نشد اینو امتحان کن"),
        ),
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!AetherNative.available) {
            Text(
                homeText(
                    AetherNative.unavailableReason,
                    "این گوشی از UAC PoW پشتیبانی نمی‌کنه.",
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
            SectionLabel(homeText("Connection mode", "روش اتصال"))
            Text(
                homeText(
                    "Auto is best for most people. It tries each way until one works. Pick one by hand only if you know what you're doing.",
                    "خودکار برای بیشتر آدما بهترینه. خودش دونه دونه امتحان می‌کنه تا وصل بشه. فقط اگه می‌دونی داری چی کار می‌کنی دستی انتخاب کن.",
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
            Text(
                homeText("Search", "سرچ"),
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    PowCoreConfig.SCAN_TURBO to homeText("Fast", "سریع"),
                    PowCoreConfig.SCAN_BALANCED to homeText("Balanced", "بالانس"),
                    PowCoreConfig.SCAN_THOROUGH to homeText("Deep", "عمیق"),
                ).forEach { (id, label) ->
                    val selected = settings.scanMode == id
                    Text(
                        text = label,
                        color = if (selected) Color.White else UacColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selected) accent else Color.White.copy(alpha = 0.04f))
                            .border(
                                1.dp,
                                if (selected) accent else Color.White.copy(alpha = 0.12f),
                                RoundedCornerShape(11.dp),
                            )
                            .clickable { store.save(settings.copy(scanMode = id)) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
            Text(
                homeText("Only the first connect.", "فقط وصل اول."),
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PowLearnedPathsCard(store = store, accent = accent)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ToolCardBrush, ToolCardShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(homeText("Can't connect?", "وصل نمیشه؟"))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        homeText("Try harder to get through", "سخت‌تر تلاش کن رد بشی"),
                        color = UacColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        homeText(
                            "Turn this on only if nothing connects. It sends data in smaller pieces to get past filters, but it will be a bit slower. Only works with MASQUE.",
                            "فقط وقتی هیچ‌جوره وصل نمیشی روشنش کن. اطلاعات رو ریز ریز می‌فرسته تا از فیلتر رد بشه ولی یکم کندتر و پینگش بیشتر میشه. فقط روی MASQUE جواب میده.",
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
                    "Will be used next time you connect.",
                    "دفعه بعد که وصل بشی اعمال میشه.",
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
private fun PowLearnedPathsCard(
    store: PowEngineStore,
    accent: Color,
) {
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(revision) {
        runCatching { store.learnedPaths() }.getOrElse { PowLearnedPathSnapshot.EMPTY }
    }
    var expanded by remember { mutableStateOf(false) }
    var forgotten by remember { mutableStateOf(false) }
    val summary = snapshot.summary().ifBlank { homeText("None yet", "هنوز خالی") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    homeText("Learned paths", "مسیرهای یادگرفته"),
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!expanded) {
                    Text(
                        summary,
                        color = UacColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = UacColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (snapshot.isEmpty) {
                    Text(
                        homeText(
                            "After a successful connect, the winning hop shows up here.",
                            "بعد از وصل موفق، مسیر برنده اینجا می‌آید.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                } else {
                    snapshot.globalOuter?.let { outer ->
                        PowMemoryLine(
                            homeText("Outer", "لایه بیرونی"),
                            PowCoreConfig.outerLabel(outer),
                        )
                    }
                    snapshot.networks.take(3).forEach { row ->
                        val outer = row.outer?.let(PowCoreConfig::outerLabel) ?: "—"
                        val rtt = row.rttMs?.let { " · ${it}ms" }.orEmpty()
                        PowMemoryLine(PowPathMemory.networkLabel(row.networkKey), "$outer$rtt")
                    }
                    snapshot.masqueLastPeer?.let { peer ->
                        val hop = when (snapshot.globalOuter) {
                            "wireguard" -> "WireGuard"
                            "gool" -> "WoW"
                            else -> "MASQUE"
                        }
                        PowMemoryLine(hop, peer)
                    }
                    snapshot.wowLastPeer?.let { PowMemoryLine("WoW", it) }
                    if (snapshot.masqueCachedGateways.isNotEmpty()) {
                        val extra = snapshot.masqueCachedGateways.size - 2
                        val value = buildString {
                            append(snapshot.masqueCachedGateways.take(2).joinToString(" · "))
                            if (extra > 0) append(" +$extra")
                        }
                        PowMemoryLine(homeText("Cached", "کش"), value)
                    }
                    Text(
                        homeText("Forget", "پاک کردن"),
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                store.forgetLearnedPaths()
                                revision += 1
                                forgotten = true
                            }
                            .padding(vertical = 4.dp),
                    )
                }
                if (forgotten && snapshot.isEmpty) {
                    Text(
                        homeText("Cleared.", "پاک شد."),
                        color = UacColors.ConnectedGreen,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PowMemoryLine(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = UacColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            value,
            color = UacColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
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
