package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogLevel
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.ConnectionMetricsStore
import com.uacspoofer.mobile.vpn.ExitIpInfoRepository
import com.uacspoofer.mobile.vpn.ExitIpInfoState
import kotlinx.coroutines.launch

@Composable
internal fun ConnectionAwareFeatureCard(
    state: ConnectionState,
    profile: ProxyProfile,
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val metrics by ConnectionMetricsStore.metrics.collectAsStateWithLifecycle()
    val entries by AppLogRepository.entries.collectAsStateWithLifecycle()
    val exitInfoRepository = remember(context) { ExitIpInfoRepository.get(context) }
    val rawExitInfoState by exitInfoRepository.state.collectAsStateWithLifecycle()
    val exitInfoState = if (rawExitInfoState.profileId == profile.id) {
        rawExitInfoState
    } else {
        ExitIpInfoState(profileId = profile.id, isLoading = state == ConnectionState.CONNECTED)
    }
    val resolvedExitCountry = remember(exitInfoState.info) {
        exitInfoState.info
            ?.let { CountryMetadata.resolve(it.countryCode, it.country) }
            ?.takeIf { it.isKnown }
    }
    val displayedCountry = resolvedExitCountry ?: profile.country
    val errorCount = remember(entries) { entries.count { it.level == LogLevel.ERROR } }
    val scope = rememberCoroutineScope()
    var activeDialog by remember { mutableStateOf<HomeMetricDialog?>(null) }

    LaunchedEffect(state, profile.id) {
        if (state == ConnectionState.CONNECTED) {
            exitInfoRepository.refresh(profile.id)
        } else {
            activeDialog = null
        }
    }

    AnimatedContent(
        targetState = state == ConnectionState.CONNECTED,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(260, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 7 })
                .togetherWith(
                    fadeOut(tween(180)) +
                        slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 9 },
                )
                .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(300) }))
        },
        label = "home-connection-insights",
    ) { connected ->
        if (connected) {
            ConnectedInsightsCard(
                latencyMs = metrics.latencyMs,
                measuringLatency = metrics.isMeasuringLatency,
                country = displayedCountry,
                countryFallback = profile.serverHost,
                logCount = entries.size,
                errorCount = errorCount,
                compact = compact,
                onPingClick = { activeDialog = HomeMetricDialog.PING },
                onCountryClick = {
                    activeDialog = HomeMetricDialog.COUNTRY
                    scope.launch { exitInfoRepository.refresh(profile.id) }
                },
                onLogClick = { activeDialog = HomeMetricDialog.LOGS },
            )
        } else {
            FeatureCard(accent = accent, compact = compact, modifier = Modifier.fillMaxWidth())
        }
    }

    HomePingDialog(
        visible = activeDialog == HomeMetricDialog.PING,
        metrics = metrics,
        onDismissRequest = { activeDialog = null },
    )
    HomeCountryDialog(
        visible = activeDialog == HomeMetricDialog.COUNTRY,
        state = exitInfoState,
        onRefresh = { scope.launch { exitInfoRepository.refresh(profile.id, force = true) } },
        onDismissRequest = { activeDialog = null },
    )
    HomeLogsDialog(
        visible = activeDialog == HomeMetricDialog.LOGS,
        entries = entries,
        onDismissRequest = { activeDialog = null },
    )
}

@Composable
private fun ConnectedInsightsCard(
    latencyMs: Long?,
    measuringLatency: Boolean,
    country: CountryMetadata,
    countryFallback: String,
    logCount: Int,
    errorCount: Int,
    compact: Boolean,
    onPingClick: () -> Unit,
    onCountryClick: () -> Unit,
    onLogClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (compact) 15.dp else 17.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 86.dp else 94.dp)
            .background(UacColors.Surface.copy(alpha = 0.82f), shape)
            .border(0.75.dp, UacColors.CardBorder, shape)
            .padding(horizontal = 5.dp, vertical = if (compact) 7.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InsightItem(
            title = "PING",
            value = if (measuringLatency) "…" else latencyMs?.let { "$it ms" } ?: "—",
            valueColor = latencyColor(latencyMs),
            detail = if (measuringLatency) "Testing route" else latencyQuality(latencyMs),
            detailColor = if (measuringLatency) UacColors.DisconnectedBlue else latencyColor(latencyMs),
            icon = Icons.Outlined.Speed,
            loading = measuringLatency,
            compact = compact,
            onClick = onPingClick,
            modifier = Modifier.weight(1f),
        )
        InsightDivider()
        InsightItem(
            title = "COUNTRY",
            value = country.countryName.takeIf { country.isKnown } ?: "Unknown",
            valueColor = Color.White,
            detail = country.countryCode ?: countryFallback,
            detailColor = UacColors.TextSecondary,
            icon = Icons.Outlined.Public,
            leadingValue = if (country.isKnown) ({ CountryFlagIcon(country, size = 14.dp) }) else null,
            compact = compact,
            onClick = onCountryClick,
            modifier = Modifier.weight(1f),
        )
        InsightDivider()
        InsightItem(
            title = "LOG",
            value = logCount.toString(),
            valueColor = Color.White,
            detail = if (errorCount == 0) "No errors" else "$errorCount errors",
            detailColor = if (errorCount == 0) UacColors.ConnectedGreen else Color(0xFFFF7483),
            icon = Icons.Outlined.Description,
            compact = compact,
            onClick = onLogClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InsightItem(
    title: String,
    value: String,
    valueColor: Color,
    detail: String,
    detailColor: Color,
    icon: ImageVector,
    loading: Boolean = false,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    leadingValue: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    color = UacColors.DisconnectedBlue,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(icon, null, tint = UacColors.DisconnectedBlue, modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.height(1.dp))
        Text(title, color = Color.White, fontSize = if (compact) 8.5.sp else 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            leadingValue?.invoke()
            if (leadingValue != null) Spacer(Modifier.width(4.dp))
            Text(
                value,
                color = valueColor,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(5.dp).background(detailColor, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(detail, color = detailColor, fontSize = if (compact) 8.sp else 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class HomeMetricDialog {
    PING,
    COUNTRY,
    LOGS,
}

@Composable
private fun InsightDivider() {
    Box(Modifier.fillMaxHeight(0.62f).width(1.dp).background(Color.White.copy(alpha = 0.10f)))
}

private fun latencyQuality(latencyMs: Long?): String = when {
    latencyMs == null -> "Measuring"
    latencyMs <= 120L -> "Excellent"
    latencyMs <= 250L -> "Good"
    latencyMs <= 500L -> "Fair"
    else -> "Slow"
}

private fun latencyColor(latencyMs: Long?): Color = when {
    latencyMs == null -> UacColors.TextSecondary
    latencyMs <= 250L -> UacColors.ConnectedGreen
    latencyMs <= 500L -> Color(0xFFFFC857)
    else -> Color(0xFFFF7483)
}
