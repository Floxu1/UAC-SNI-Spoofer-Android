package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uacspoofer.mobile.logging.AppLogEntry
import com.uacspoofer.mobile.logging.LogLevel
import com.uacspoofer.mobile.profiles.ProfileLibrary
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.ConnectionMetrics
import com.uacspoofer.mobile.vpn.ExitIpInfoState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val DialogSurface = Color(0xFF0A1623)
private val DialogInnerSurface = Color(0xFF0D1C2B)
private val DialogBorder = Color(0x5536516D)
private val DialogBlue = Color(0xFF45B7FF)

@Composable
internal fun HomeLogsDialog(
    visible: Boolean,
    entries: List<AppLogEntry>,
    onDismissRequest: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(visible, entries.lastOrNull()?.id) {
        if (visible && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    AnimatedHomeMetricDialog(
        visible = visible,
        title = "All logs",
        subtitle = "${entries.size} current entries",
        icon = Icons.Outlined.Description,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f),
    ) {
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No log entries", color = UacColors.TextSecondary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DialogInnerSurface, RoundedCornerShape(16.dp))
                    .border(1.dp, DialogBorder.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(entries, key = { it.id }) { entry -> HomeDialogLogRow(entry) }
            }
        }
    }
}

@Composable
internal fun HomeCountryDialog(
    visible: Boolean,
    state: ExitIpInfoState,
    onRefresh: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = "My IP Information",
        subtitle = "Public exit details through Xray",
        icon = Icons.Outlined.Public,
        onDismissRequest = onDismissRequest,
    ) {
        val info = state.info
        if (info == null && state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    color = UacColors.ConnectedGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("Reading the active VPN exit…", color = UacColors.TextSecondary, fontSize = 12.sp)
            }
        } else if (info == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp)
                    .background(DialogInnerSurface, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Exit information is not ready", color = UacColors.TextPrimary, fontSize = 14.sp)
                state.errorMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFF8C98), fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, null, tint = DialogBlue, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(5.dp))
                    Text("Try again", color = DialogBlue, fontSize = 11.sp)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DialogInnerSurface, RoundedCornerShape(16.dp))
                    .border(1.dp, DialogBorder.copy(alpha = 0.52f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 15.dp, vertical = 8.dp),
            ) {
                    MetricDetailRow("IP address", info.ipAddress)
                    MetricDetailRow("ISP", info.isp.ifBlank { "Not reported" })
                    MetricDetailRow("City", info.city.ifBlank { "Not reported" })
                    MetricDetailRow("Region", info.region.ifBlank { "Not reported" })
                    MetricDetailRow("Country", info.country.ifBlank { info.countryCode.ifBlank { "Not reported" } })
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${info.provider} • ${formatTime(info.fetchedAtMs)} • Xray SOCKS",
                    color = UacColors.TextSecondary.copy(alpha = 0.78f),
                    fontSize = 9.5.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            color = DialogBlue,
                            strokeWidth = 1.6.dp,
                            modifier = Modifier.size(15.dp),
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, null, tint = DialogBlue, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.size(5.dp))
                    Text("Refresh", color = DialogBlue, fontSize = 11.sp)
                }
            }
            state.errorMessage?.let {
                Text(
                    text = "Latest refresh: $it",
                    color = Color(0xFFFF8C98),
                    fontSize = 9.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun HomePingDialog(
    visible: Boolean,
    metrics: ConnectionMetrics,
    onDismissRequest: () -> Unit,
) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = "Ping details",
        subtitle = "Live HTTPS route measurement",
        icon = Icons.Outlined.Speed,
        onDismissRequest = onDismissRequest,
    ) {
        val latency = metrics.latencyMs
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DialogInnerSurface, RoundedCornerShape(16.dp))
                .border(1.dp, DialogBorder.copy(alpha = 0.52f), RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (metrics.isMeasuringLatency) {
                CircularProgressIndicator(color = DialogBlue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(9.dp))
                Text("Testing route…", color = DialogBlue, fontSize = 12.sp)
            } else {
                Text(
                    text = latency?.let { "$it ms" } ?: "—",
                    color = latencyColorForDialog(latency),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = latencyQualityForDialog(latency),
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DialogInnerSurface.copy(alpha = 0.74f), RoundedCornerShape(16.dp))
                .padding(horizontal = 15.dp, vertical = 7.dp),
        ) {
            MetricDetailRow("Average", metrics.averageLatencyMs.asMillis())
            MetricDetailRow("Minimum", metrics.minimumLatencyMs.asMillis())
            MetricDetailRow("Maximum", metrics.maximumLatencyMs.asMillis())
            MetricDetailRow("Jitter", metrics.jitterMs.asMillis())
            MetricDetailRow("Samples", metrics.sampleCount.takeIf { it > 0 }?.toString() ?: "—")
            MetricDetailRow("Measured", metrics.measuredAtMs?.let(::formatTime) ?: "Waiting for sample")
            MetricDetailRow("Method", "HTTPS payload through Xray tunnel")
        }
    }
}

@Composable
internal fun HomeConfigsDialog(
    visible: Boolean,
    library: ProfileLibrary,
    activeProfileId: String?,
    latencies: Map<String, Long>,
    onSelect: (ProxyProfile) -> Unit,
    onManage: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = "Configurations",
        subtitle = "${library.allProfiles.size} available • tap to select",
        icon = Icons.Outlined.Description,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(DialogInnerSurface, RoundedCornerShape(16.dp))
                .border(1.dp, DialogBorder.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(library.allProfiles, key = ProxyProfile::id) { profile ->
                HomeConfigDialogRow(
                    profile = profile,
                    selected = profile.id == library.selectedId,
                    active = profile.id == activeProfileId,
                    latencyMs = latencies[profile.id],
                    onClick = { onSelect(profile) },
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        TextButton(
            onClick = onManage,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Outlined.Tune, null, tint = DialogBlue, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(6.dp))
            Text("Manage configurations", color = DialogBlue, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeConfigDialogRow(
    profile: ProxyProfile,
    selected: Boolean,
    active: Boolean,
    latencyMs: Long?,
    onClick: () -> Unit,
) {
    val accent = if (active) UacColors.ConnectedGreen else DialogBlue
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected || active) accent.copy(alpha = 0.085f) else Color(0xA90A1623),
                shape,
            )
            .border(
                1.dp,
                if (selected || active) accent.copy(alpha = 0.36f) else DialogBorder.copy(alpha = 0.38f),
                shape,
            )
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeConfigProtocolBadge(profile, selected || active)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    color = UacColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (profile.isBuiltIn) {
                    Spacer(Modifier.size(5.dp))
                    Icon(Icons.Outlined.Lock, null, tint = UacColors.TextSecondary, modifier = Modifier.size(12.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${profile.protocol.name} • ${profile.network.uppercase()} / ${profile.security.uppercase()}",
                color = UacColors.TextSecondary,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active || selected || latencyMs != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                active -> "Connected"
                selected -> "Selected for next connection"
                            else -> ""
                        },
                        color = accent,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    latencyMs?.let {
                        Text("$it ms", color = UacColors.ConnectedGreen, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (selected) {
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier.size(27.dp).background(accent.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
            Icon(Icons.Outlined.Check, "Selected", tint = accent, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun HomeConfigProtocolBadge(profile: ProxyProfile, emphasized: Boolean) {
    val color = when (profile.protocol) {
        ProxyProtocol.VLESS -> Color(0xFF8D7CFF)
        ProxyProtocol.VMESS -> Color(0xFFFFB454)
        ProxyProtocol.TROJAN -> DialogBlue
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(color.copy(alpha = if (emphasized) 0.18f else 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (profile.country.isKnown) {
            CountryFlagIcon(profile.country, size = 27.dp)
        } else {
            Text(
                when (profile.protocol) {
                    ProxyProtocol.VLESS -> "V"
                    ProxyProtocol.VMESS -> "M"
                    ProxyProtocol.TROJAN -> "T"
                },
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AnimatedHomeMetricDialog(
    visible: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onDismissRequest: () -> Unit,
    expanded: Boolean = false,
    panelModifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    var panelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            delay(16L)
            panelVisible = true
        } else if (mounted) {
            panelVisible = false
            delay(DIALOG_EXIT_MS.toLong())
            if (!visible) mounted = false
        }
    }
    if (!mounted) return

    val scrimInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val scrimAlpha by animateFloatAsState(
            targetValue = if (panelVisible) 0.74f else 0f,
            animationSpec = tween(if (panelVisible) 180 else DIALOG_EXIT_MS),
            label = "home-metric-scrim",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismissRequest,
                )
                .padding(horizontal = 20.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = panelVisible,
                enter = fadeIn(tween(190)) +
                    scaleIn(tween(230, easing = FastOutSlowInEasing), initialScale = 0.96f) +
                    slideInVertically(tween(230, easing = FastOutSlowInEasing)) { it / 18 },
                exit = fadeOut(tween(DIALOG_EXIT_MS)) +
                    scaleOut(tween(DIALOG_EXIT_MS), targetScale = 0.975f) +
                    slideOutVertically(tween(DIALOG_EXIT_MS)) { it / 28 },
            ) {
                Surface(
                    modifier = panelModifier
                        .widthIn(max = 420.dp)
                        .clickable(
                            interactionSource = panelInteraction,
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(24.dp),
                    color = DialogSurface,
                    border = BorderStroke(1.dp, DialogBorder),
                    shadowElevation = 18.dp,
                ) {
                    Column(
                        modifier = (if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                            .padding(18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(DialogBlue.copy(alpha = 0.13f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(icon, null, tint = DialogBlue, modifier = Modifier.size(21.dp))
                            }
                            Spacer(Modifier.size(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, color = UacColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(subtitle, color = UacColors.TextSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = onDismissRequest, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Close, "Close", tint = UacColors.TextSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = UacColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(0.38f))
        Text(
            text = value,
            color = UacColors.TextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.62f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeDialogLogRow(entry: AppLogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> Color(0xFF8295AA)
        LogLevel.INFO -> DialogBlue
        LogLevel.WARNING -> Color(0xFFFFC857)
        LogLevel.ERROR -> Color(0xFFFF6574)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.025f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(5.dp)
                .background(levelColor, CircleShape),
        )
        Spacer(Modifier.size(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.timestamp}  ${entry.source.label}  ${entry.level.label}",
                color = levelColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.message,
                color = Color(0xFFD7E1ED),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

private fun Long?.asMillis(): String = this?.let { "$it ms" } ?: "—"

private fun formatTime(timestampMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))

private fun latencyQualityForDialog(latencyMs: Long?): String = when {
    latencyMs == null -> "No successful sample yet"
    latencyMs <= 120L -> "Excellent"
    latencyMs <= 250L -> "Good"
    latencyMs <= 500L -> "Fair"
    else -> "Slow"
}

private fun latencyColorForDialog(latencyMs: Long?): Color = when {
    latencyMs == null -> UacColors.TextSecondary
    latencyMs <= 250L -> UacColors.ConnectedGreen
    latencyMs <= 500L -> Color(0xFFFFC857)
    else -> Color(0xFFFF7483)
}

private const val DIALOG_EXIT_MS = 170
