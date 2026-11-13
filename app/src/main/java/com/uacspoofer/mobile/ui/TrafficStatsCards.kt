package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.ui.theme.AppTheme
import com.uacspoofer.mobile.ui.theme.UacThemeState
import com.uacspoofer.mobile.ui.theme.styleTokens
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.neonDp
import com.uacspoofer.mobile.ui.theme.neonSp
import com.uacspoofer.mobile.vpn.MonthlyTrafficStore
import com.uacspoofer.mobile.vpn.TrafficStatsStore
import java.util.Locale

@Composable
internal fun TrafficStatsRow(
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
    ) {
        TrafficStatCard(
            title = homeText("Download", "دانلود"),
            download = true,
            icon = Icons.Rounded.ArrowDownward,
            accent = accent,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        TrafficStatCard(
            title = homeText("Upload", "آپلود"),
            download = false,
            icon = Icons.Rounded.ArrowUpward,
            accent = accent,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrafficStatCard(
    title: String,
    download: Boolean,
    icon: ImageVector,
    accent: Color,
    compact: Boolean,
    modifier: Modifier,
) {
    val statsState = TrafficStatsStore.stats.collectAsState()
    val totalBytes by remember(download) {
        derivedStateOf {
            if (download) statsState.value.downloadBytes else statsState.value.uploadBytes
        }
    }
    val bytesPerSecond by remember(download) {
        derivedStateOf {
            if (download) statsState.value.downloadBytesPerSecond else statsState.value.uploadBytesPerSecond
        }
    }
    val localizedFont = homeLocalizedFont()
    val neon = com.uacspoofer.mobile.ui.theme.UacThemeState.isNeon
    val tuned = styleTokens()
    val shape = RoundedCornerShape(
        if (tuned != null) neonDp(compact, 21f, 18f, tuned.cardCorner) else if (compact) 18.dp else 21.dp,
    )
    val total = formatBytes(totalBytes)
    val rate = formatBytes(bytesPerSecond)
    val cardFill = if (tuned != null) {
        listOf(tuned.cardFillStart, tuned.cardFillEnd)
    } else {
        listOf(Color(0xE512202E), Color(0xB5091724))
    }
    val cardEdge = tuned?.cardBorderColor ?: accent.copy(alpha = 0.44f)
    val iconTint = if (tuned != null && !tuned.cardIconFollowState) tuned.cardIconColor else accent
    Row(
        modifier = modifier
            .height(
                if (tuned != null) {
                    neonDp(compact, 104f, 92f, tuned.cardHeight)
                } else if (compact) {
                    76.dp
                } else {
                    88.dp
                },
            )
            .then(if (tuned != null) Modifier.graphicsLayer { alpha = tuned.cardAlpha } else Modifier)
            .background(Brush.linearGradient(cardFill), shape)
            .border(if (tuned != null) tuned.cardBorder.dp else 1.dp, cardEdge, shape)
            .padding(horizontal = if (tuned != null) neonDp(compact, 11f, 8f, tuned.cardPadH) else if (compact) 8.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (tuned != null) neonDp(compact, 36f, 31f, tuned.cardIconBox) else if (compact) 31.dp else 36.dp)
                .background(accent.copy(alpha = 0.11f), CircleShape)
                .border(1.dp, accent.copy(alpha = 0.24f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = iconTint,
                modifier = Modifier.size(if (tuned != null) neonDp(compact, 21f, 18f, tuned.cardIcon) else if (compact) 18.dp else 21.dp),
            )
        }
        Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val solaris = UacThemeState.theme == AppTheme.SOLARIS
            Text(
                title,
                color = when {
                    solaris -> UacColors.NeonCyan
                    tuned != null -> tuned.cardLabelColor
                    else -> UacColors.TextSecondary
                },
                fontSize = if (tuned != null) neonSp(compact, 12f, 10.5f, tuned.cardLabelSize) else if (compact) 10.5.sp else 12.sp,
                fontFamily = localizedFont,
                fontWeight = if (tuned != null) {
                    FontWeight(if (LocalHomePersian.current) tuned.cardLabelWeightFa else tuned.cardLabelWeight)
                } else if (LocalHomePersian.current) {
                    FontWeight.Medium
                } else {
                    null
                },
                maxLines = 1,
            )
            if (neon && tuned != null) {
                Text(
                    rate.amount,
                    color = tuned.cardValueColor,
                    fontSize = neonSp(compact, 26f, 22f, tuned.cardValueSize),
                    fontWeight = FontWeight(tuned.cardValueWeight),
                    maxLines = 1,
                )
                val fraction = (bytesPerSecond.toFloat() / 400_000f).coerceIn(0.08f, 1f)
                Spacer(Modifier.height(4.dp))
                val barStart = if (solaris) UacColors.NeonCyan else tuned.cardBarStart
                val barEnd = if (solaris) UacColors.ConnectedGreen else tuned.cardBarEnd
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tuned.cardBarHeight.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tuned.cardBarHeight.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.08f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(tuned.cardBarHeight.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Brush.horizontalGradient(listOf(barStart, barEnd))),
                        )
                    }
                    if (solaris) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(6.dp)
                                .background(barEnd, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${total.amount} ${total.unit}",
                    color = tuned.cardTotalColor,
                    fontSize = neonSp(compact, 10f, 9f, tuned.cardTotalSize),
                    maxLines = 1,
                )
            } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    total.amount,
                    color = accent,
                    fontSize = if (compact) 18.sp else 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    total.unit,
                    color = UacColors.TextSecondary,
                    fontSize = if (compact) 9.sp else 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                )
            }
            Text(
                "${rate.amount} ${rate.unit}/s",
                color = UacColors.TextSecondary.copy(alpha = 0.82f),
                fontSize = if (compact) 8.5.sp else 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            }
        }
    }
}

@Composable
internal fun TrafficUsagePanels() {
    val context = LocalContext.current
    val stats by TrafficStatsStore.stats.collectAsState()
    val monthly by remember(context) { MonthlyTrafficStore.get(context) }.usage.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrafficPeriodPanel(
            title = homeText("This session", "این جلسه"),
            downloadBytes = stats.downloadBytes,
            uploadBytes = stats.uploadBytes,
        )
        TrafficPeriodPanel(
            title = homeText("This month", "این ماه"),
            downloadBytes = monthly.downloadBytes,
            uploadBytes = monthly.uploadBytes,
        )
    }
}

internal fun monthlyTrafficLabel(totalBytes: Long): String = formatBytesLabel(totalBytes)

@Composable
private fun TrafficPeriodPanel(
    title: String,
    downloadBytes: Long,
    uploadBytes: Long,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE512202E), shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .background(UacColors.ConnectingCyan),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = homeLocalizedFont(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TrafficDirectionValue(
                    icon = Icons.Rounded.ArrowDownward,
                    bytes = downloadBytes,
                    modifier = Modifier.weight(1f),
                )
                TrafficDirectionValue(
                    icon = Icons.Rounded.ArrowUpward,
                    bytes = uploadBytes,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TrafficDirectionValue(
    icon: ImageVector,
    bytes: Long,
    modifier: Modifier,
) {
    val formatted = formatBytes(bytes)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = UacColors.ConnectingCyan, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            formatted.amount,
            color = UacColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            formatted.unit,
            color = UacColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private data class FormattedBytes(val amount: String, val unit: String)

private fun formatBytesLabel(bytes: Long): String {
    val formatted = formatBytes(bytes)
    return "${formatted.amount} ${formatted.unit}"
}

private fun formatBytes(bytes: Long): FormattedBytes {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1_073_741_824.0 -> FormattedBytes(
            String.format(Locale.US, if (value < 10_737_418_240.0) "%.2f" else "%.1f", value / 1_073_741_824.0),
            "GB",
        )
        value >= 1_048_576.0 -> FormattedBytes(
            String.format(Locale.US, if (value < 104_857_600.0) "%.1f" else "%.0f", value / 1_048_576.0),
            "MB",
        )
        value >= 1_024.0 -> FormattedBytes(
            String.format(Locale.US, if (value < 102_400.0) "%.1f" else "%.0f", value / 1_024.0),
            "KB",
        )
        else -> FormattedBytes(value.toLong().toString(), "B")
    }
}
