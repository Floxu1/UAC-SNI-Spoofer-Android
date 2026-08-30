package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.logging.AppLogEntry
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogLevel
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.ui.theme.UacColors

private val LogSurface = Color(0xD90A1623)
private val LogBorder = Color(0x4036516D)
private val LogBlue = Color(0xFF299EFF)

@Composable
internal fun LiveLogsScreen(onMenuClick: () -> Unit) {
    val logs by AppLogRepository.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var autoScroll by remember { mutableStateOf(true) }
    var selectedSource by remember { mutableStateOf<LogSource?>(null) }
    val visibleLogs = remember(logs, selectedSource) {
        selectedSource?.let { source -> logs.filter { it.source == source } } ?: logs
    }

    LaunchedEffect(visibleLogs.lastOrNull()?.id, autoScroll) {
        if (autoScroll && visibleLogs.isNotEmpty()) {
            listState.animateScrollToItem(visibleLogs.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(UacColors.BackgroundTop, UacColors.BackgroundMiddle, UacColors.BackgroundBottom),
                ),
            )
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        WideSplitColumn(
            headerPadding = 18.dp,
            header = {
                Spacer(Modifier.height(8.dp))
                LogsHeader(onMenuClick = onMenuClick)
            },
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Live Logs",
                color = UacColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Real-time app, VPN service and Xray activity",
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(14.dp))
            LogSummaryBar(
                count = visibleLogs.size,
                autoScroll = autoScroll,
                onAutoScroll = { autoScroll = !autoScroll },
                onCopy = {
                    clipboard.setText(AnnotatedString(AppLogRepository.snapshotText()))
                    AppLogRepository.info(LogSource.APP, "Log snapshot copied")
                },
                onClear = { AppLogRepository.clear() },
            )
            Spacer(Modifier.height(10.dp))
            SourceFilters(selectedSource = selectedSource, onSelect = { selectedSource = it })
            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LogSurface)
                    .border(1.dp, LogBorder, RoundedCornerShape(18.dp)),
            ) {
                if (visibleLogs.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ListAlt,
                            contentDescription = null,
                            tint = UacColors.TextSecondary.copy(alpha = 0.55f),
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No log entries", color = UacColors.TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(visibleLogs, key = { it.id }) { entry -> LogRow(entry) }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Memory buffer • newest ${logs.size}/2000 entries",
                color = UacColors.TextSecondary.copy(alpha = 0.72f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun LogsHeader(onMenuClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RemoteIconButton(
            onClick = onMenuClick,
            modifier = Modifier.openDrawerOnDpadLeft(onMenuClick),
        ) {
            Icon(Icons.Rounded.Menu, "Open navigation menu", tint = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF25F58A), CircleShape),
            )
            Spacer(Modifier.size(7.dp))
            Text("LIVE", color = Color(0xFF25F58A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LogSummaryBar(
    count: Int,
    autoScroll: Boolean,
    onAutoScroll: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0D1B2A))
            .border(1.dp, LogBorder, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("EVENT STREAM", color = LogBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("$count entries", color = UacColors.TextSecondary, fontSize = 11.sp)
        }
        LogActionButton(
            icon = if (autoScroll) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            description = if (autoScroll) "Pause auto-scroll" else "Resume auto-scroll",
            active = autoScroll,
            onClick = onAutoScroll,
        )
        LogActionButton(Icons.Rounded.ContentCopy, "Copy logs", onClick = onCopy)
        LogActionButton(Icons.Rounded.DeleteSweep, "Clear logs", onClick = onClear)
    }
}

@Composable
private fun LogActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    RemoteIconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(
            icon,
            description,
            tint = if (active) LogBlue else Color(0xFF9EB1C7),
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun SourceFilters(selectedSource: LogSource?, onSelect: (LogSource?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LogFilterChip("ALL", selectedSource == null, { onSelect(null) }, Modifier.weight(1f))
        LogSource.entries.forEach { source ->
            LogFilterChip(source.label, selectedSource == source, { onSelect(source) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LogFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) LogBlue.copy(alpha = 0.18f) else Color(0xFF0A1522),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LogBlue.copy(alpha = 0.65f) else LogBorder,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) Color(0xFF9ED4FF) else UacColors.TextSecondary,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LogRow(entry: AppLogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> Color(0xFF8295AA)
        LogLevel.INFO -> Color(0xFF45B7FF)
        LogLevel.SUCCESS -> Color(0xFF25E49A)
        LogLevel.WARNING -> Color(0xFFFFC857)
        LogLevel.ERROR -> Color(0xFFFF5261)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.022f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(5.dp)
                .background(levelColor, CircleShape),
        )
        Spacer(Modifier.size(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.timestamp,
                    color = UacColors.TextSecondary.copy(alpha = 0.78f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.size(7.dp))
                Text(
                    entry.source.label,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                entry.message,
                color = Color(0xFFD7E1ED),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
