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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.TrafficStatsStore
import java.util.Locale

@Composable
internal fun TrafficStatsRow(
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val stats by TrafficStatsStore.stats.collectAsState()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
    ) {
        TrafficStatCard(
            title = "Download",
            totalBytes = stats.downloadBytes,
            bytesPerSecond = stats.downloadBytesPerSecond,
            icon = Icons.Rounded.ArrowDownward,
            accent = accent,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        TrafficStatCard(
            title = "Upload",
            totalBytes = stats.uploadBytes,
            bytesPerSecond = stats.uploadBytesPerSecond,
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
    totalBytes: Long,
    bytesPerSecond: Long,
    icon: ImageVector,
    accent: Color,
    compact: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(if (compact) 18.dp else 21.dp)
    val total = formatBytes(totalBytes)
    val rate = formatBytes(bytesPerSecond)
    Row(
        modifier = modifier
            .height(if (compact) 76.dp else 88.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xE512202E), Color(0xB5091724))),
                shape,
            )
            .border(1.dp, accent.copy(alpha = 0.44f), shape)
            .padding(horizontal = if (compact) 8.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 31.dp else 36.dp)
                .background(accent.copy(alpha = 0.11f), CircleShape)
                .border(1.dp, accent.copy(alpha = 0.24f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(if (compact) 18.dp else 21.dp))
        }
        Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = UacColors.TextSecondary,
                fontSize = if (compact) 10.5.sp else 12.sp,
                maxLines = 1,
            )
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

private data class FormattedBytes(val amount: String, val unit: String)

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
