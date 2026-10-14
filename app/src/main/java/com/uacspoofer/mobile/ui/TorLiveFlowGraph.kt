package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.tor.TorFlowBranch
import com.uacspoofer.mobile.engine.tor.TorFlowStage
import com.uacspoofer.mobile.engine.tor.TorFlowStageId
import com.uacspoofer.mobile.engine.tor.TorFlowState
import com.uacspoofer.mobile.engine.tor.TorLiveFlow
import kotlinx.coroutines.delay
import com.uacspoofer.mobile.ui.theme.UacColors

private val FlowRunning = Color(0xFFFFB547)
private val FlowPending = Color(0xFF5B6B80)

private fun TorFlowState.color(): Color = when (this) {
    TorFlowState.DONE -> UacColors.ConnectedGreen
    TorFlowState.RUNNING -> FlowRunning
    TorFlowState.FAILED -> UacColors.ErrorRed
    TorFlowState.SKIPPED -> FlowPending
    TorFlowState.PENDING -> FlowPending
}

@Composable
private fun TorFlowState.label(): String = when (this) {
    TorFlowState.DONE -> homeText("done", "انجام شد")
    TorFlowState.RUNNING -> homeText("running", "در حال اجرا")
    TorFlowState.FAILED -> homeText("failed", "ناموفق")
    TorFlowState.SKIPPED -> homeText("skipped", "رد شد")
    TorFlowState.PENDING -> homeText("waiting", "در انتظار")
}

@Composable
private fun TorFlowStageId.title(): String = when (this) {
    TorFlowStageId.ARCH -> homeText("Architecture check", "بررسی معماری")
    TorFlowStageId.POOL -> homeText("Bridge pool", "ساخت مخزن پل‌ها")
    TorFlowStageId.SCAN -> homeText("Pre-scan", "پیش‌اسکن پل‌ها")
    TorFlowStageId.BATCH -> homeText("Bridge batch", "دسته پل")
    TorFlowStageId.FRAGMENT -> homeText("Fragment proxy", "پروکسی فرگمنت")
    TorFlowStageId.PT -> homeText("PT launch", "اجرای WebTunnel")
    TorFlowStageId.HANDSHAKE -> homeText("TLS / handshake", "دست‌دهی TLS")
    TorFlowStageId.DIRECTORY -> homeText("Directory / consensus", "دایرکتوری و کانسنسوس")
    TorFlowStageId.BOOTSTRAP -> homeText("Bootstrap", "بوت‌استرپ")
    TorFlowStageId.CIRCUITS -> homeText("Circuits", "مدارها")
    TorFlowStageId.TUN -> homeText("tun2socks", "تونل دستگاه")
    TorFlowStageId.THROUGHPUT -> homeText("Throughput test", "تست سرعت")
    TorFlowStageId.WATCHDOG -> homeText("Watchdog", "نگهبان اتصال")
}

@Composable
private fun TorFlowStageId.hint(): String = when (this) {
    TorFlowStageId.ARCH -> homeText("Native PT matches CPU", "سازگاری libwebtunnel با پردازنده")
    TorFlowStageId.POOL -> homeText("last-good, scored, pasted, bundled", "آخرین پل موفق، امتیازدار، دستی، داخلی")
    TorFlowStageId.SCAN -> homeText("Which bridges answer TLS", "کدام پل‌ها جواب TLS می‌دهند")
    TorFlowStageId.BATCH -> homeText("Up to 3 batches", "تا ۳ دسته")
    TorFlowStageId.FRAGMENT -> homeText("Splits ClientHello for DPI", "تکه‌کردن ClientHello برای DPI")
    TorFlowStageId.PT -> homeText("Pluggable transport process", "پروسه انتقال WebTunnel")
    TorFlowStageId.HANDSHAKE -> homeText("10–15%", "۱۰ تا ۱۵٪")
    TorFlowStageId.DIRECTORY -> homeText("15–75%", "۱۵ تا ۷۵٪")
    TorFlowStageId.BOOTSTRAP -> homeText("Tor bootstrap progress", "پیشرفت راه‌اندازی Tor")
    TorFlowStageId.CIRCUITS -> homeText("Guards, conflux, real bridge", "گاردها، کانفلاکس، پل واقعی")
    TorFlowStageId.TUN -> homeText("MTU, buffers, UDP", "MTU، بافر، UDP")
    TorFlowStageId.THROUGHPUT -> homeText("Small download, screen on", "دانلود کوچک وقتی صفحه روشن است")
    TorFlowStageId.WATCHDOG -> homeText("SOCKS check every 60 s", "بررسی SOCKS هر ۶۰ ثانیه")
}

private fun formatMs(ms: Long): String = when {
    ms <= 0L -> ""
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000L}m ${(ms / 1000L) % 60}s"
}

private fun clock(at: Long): String =
    if (at <= 0L) "" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))

@Composable
internal fun TorLiveFlowButton(accent: Color, onClick: () -> Unit) {
    val snap by TorLiveFlow.snapshot.collectAsStateWithLifecycle()
    val anyFailed = snap.stages.values.any { it.state == TorFlowState.FAILED }
    val anyRunning = snap.stages.values.any { it.state == TorFlowState.RUNNING && it.id != TorFlowStageId.WATCHDOG }
    val dot = when {
        snap.startedAt == 0L -> UacColors.TextSecondary
        anyRunning -> FlowRunning
        anyFailed && snap.bridge.isBlank() -> UacColors.ErrorRed
        else -> UacColors.ConnectedGreen
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xE6142231), Color(0xD90B1724))), shape)
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Route, null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                homeText("Live path", "مسیر زنده"),
                color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = homeLocalizedFont(),
            )
            Text(
                homeText("Every step of the Tor connect, live", "همه مراحل اتصال Tor، به‌صورت زنده"),
                color = UacColors.TextSecondary, fontSize = 11.5.sp, fontFamily = homeLocalizedFont(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ChevronLeft, null, tint = UacColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun TorLiveFlowDialog(visible: Boolean, onDismissRequest: () -> Unit) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = homeText("Live path · Tor", "مسیر زنده · Tor"),
        subtitle = homeText("The connect process, step by step", "فرایند اتصال، مرحله به مرحله"),
        icon = Icons.Outlined.Route,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
    ) {
        PowDirectional { TorLiveFlowContent() }
    }
}

@Composable
private fun TorLiveFlowContent() {
    val snap by TorLiveFlow.snapshot.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ticking = snap.stages.values.any { it.state == TorFlowState.RUNNING }
    // Composed only while the overlay is shown, so this 1 s clock never runs in background.
    LaunchedEffect(ticking) {
        while (ticking) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
        now = System.currentTimeMillis()
    }
    var open by remember { mutableStateOf<TorFlowStageId?>(null) }
    val pulse = rememberInfiniteTransition(label = "torFlowPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    val dash by pulse.animateFloat(
        initialValue = 0f, targetValue = 24f,
        animationSpec = infiniteRepeatable(tween(1_100)), label = "dash",
    )

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        FlowHeader(snap.startedAt, snap.endedAt, now, snap.percent, snap.phaseText, snap.bridge)
        Spacer(Modifier.height(12.dp))
        if (snap.startedAt == 0L) {
            Text(
                homeText("Connect with Tor to see the live flow.", "برای دیدن مسیر زنده، با Tor وصل شو."),
                color = UacColors.TextSecondary, fontSize = 12.5.sp, fontFamily = homeLocalizedFont(),
                modifier = Modifier.padding(8.dp),
            )
        }
        val order = TorFlowStageId.entries
        order.forEachIndexed { index, id ->
            val stage = snap.stage(id)
            FlowNode(
                stage = stage,
                now = now,
                percent = snap.percent,
                phase = snap.phaseText,
                pulseAlpha = pulseAlpha,
                expanded = open == id,
                onClick = { open = if (open == id) null else id },
            )
            if (id == TorFlowStageId.BATCH && snap.branches.isNotEmpty()) {
                snap.branches.forEach { FlowBranchCard(it) }
            }
            if (index < order.lastIndex) {
                val next = snap.stage(order[index + 1])
                FlowConnector(
                    color = if (stage.state == TorFlowState.DONE) UacColors.ConnectedGreen.copy(alpha = 0.7f) else FlowPending.copy(alpha = 0.5f),
                    flowing = next.state == TorFlowState.RUNNING,
                    dashed = next.state == TorFlowState.SKIPPED || stage.state == TorFlowState.SKIPPED,
                    phase = dash,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowLegend()
    }
}

@Composable
private fun FlowHeader(startedAt: Long, endedAt: Long, now: Long, percent: Int, phase: String, bridge: String) {
    val font = homeLocalizedFont()
    val shape = RoundedCornerShape(14.dp)
    val total = when {
        startedAt <= 0L -> 0L
        endedAt > 0L -> endedAt - startedAt
        else -> now - startedAt
    }
    val progress by animateFloatAsState(percent / 100f, tween(500), label = "torPct")
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF15304F), Color(0xFF0B1A2C))), shape)
            .border(1.dp, UacColors.DisconnectedBlue.copy(alpha = 0.35f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderStat(homeText("Elapsed", "زمان کل"), formatMs(total).ifEmpty { "–" }, Modifier.weight(1f))
            HeaderStat(homeText("Progress", "پیشرفت"), "$percent%", Modifier.weight(1f))
            HeaderStat(homeText("Bridge", "پل واقعی"), bridge.ifBlank { "–" }, Modifier.weight(1.6f))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF), CircleShape)) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp)
                    .background(Brush.horizontalGradient(listOf(UacColors.DisconnectedBlue, UacColors.ConnectedGreen)), CircleShape),
            )
        }
        if (phase.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(phase, color = UacColors.TextSecondary, fontSize = 11.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier) {
    val font = homeLocalizedFont()
    Column(modifier) {
        Text(label, color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font)
        Text(
            value, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = font,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FlowNode(
    stage: TorFlowStage,
    now: Long,
    percent: Int,
    phase: String,
    pulseAlpha: Float,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val font = homeLocalizedFont()
    val color = stage.state.color()
    val shape = RoundedCornerShape(12.dp)
    val skipped = stage.state == TorFlowState.SKIPPED
    val pending = stage.state == TorFlowState.PENDING
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (pending) 0.6f else 1f)
            .background(Color(0xCC0F1E30), shape)
            .then(
                if (skipped) {
                    Modifier.dashedBorder(color.copy(alpha = 0.6f))
                } else {
                    Modifier.border(1.dp, color.copy(alpha = if (stage.state == TorFlowState.RUNNING) 0.35f + 0.5f * pulseAlpha else 0.4f), shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp)
                    .alpha(if (stage.state == TorFlowState.RUNNING) pulseAlpha else 1f)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val suffix = if (stage.id == TorFlowStageId.BATCH && stage.detail.isNotBlank()) " ${stage.detail}" else ""
                Text(stage.id.title() + suffix, color = UacColors.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = font)
                Text(
                    stage.detail.takeIf { it.isNotBlank() && stage.id != TorFlowStageId.BATCH } ?: stage.id.hint(),
                    color = UacColors.TextSecondary, fontSize = 11.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stage.state.label(), color = color, fontSize = 10.5.sp, fontFamily = font)
                val elapsed = formatMs(stage.elapsedMs(now))
                if (elapsed.isNotEmpty()) Text(elapsed, color = UacColors.TextPrimary, fontSize = 11.sp, fontFamily = font)
            }
        }
        if (stage.id == TorFlowStageId.BOOTSTRAP && stage.state != TorFlowState.PENDING) {
            Spacer(Modifier.height(8.dp))
            val p by animateFloatAsState(percent / 100f, tween(500), label = "bootPct")
            Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x33FFFFFF), CircleShape)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(5.dp).background(color, CircleShape))
            }
            Text("$percent% · $phase", color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                DetailRow(homeText("Status", "وضعیت"), stage.state.label())
                if (stage.startedAt > 0L) DetailRow(homeText("Started", "شروع"), clock(stage.startedAt))
                if (stage.endedAt > 0L) DetailRow(homeText("Ended", "پایان"), clock(stage.endedAt))
                if (stage.detail.isNotBlank()) DetailRow(homeText("Detail", "جزئیات"), stage.detail)
                stage.facts.forEach { DetailRow(it.first, it.second) }
                if (stage.facts.isEmpty() && stage.detail.isBlank()) {
                    Text(homeText("No data yet", "هنوز داده‌ای نیست"), color = UacColors.TextSecondary, fontSize = 11.sp, fontFamily = font)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    val font = homeLocalizedFont()
    Row(Modifier.fillMaxWidth().background(Color(0x22FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(key, color = UacColors.TextSecondary, fontSize = 11.sp, fontFamily = font, modifier = Modifier.weight(0.4f))
        Text(value, color = UacColors.TextPrimary, fontSize = 11.sp, fontFamily = font, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun FlowBranchCard(branch: TorFlowBranch) {
    val font = homeLocalizedFont()
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("↳", color = UacColors.ErrorRed, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Column(
            Modifier.weight(1f)
                .background(UacColors.ErrorRed.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                .border(1.dp, UacColors.ErrorRed.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                "${branch.title} ✕  ·  ${clock(branch.at)}",
                color = UacColors.ErrorRed, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = font,
            )
            Text(branch.reason, color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("↻ " + homeText("retry: ", "تلاش بعدی: ") + branch.retry, color = FlowRunning, fontSize = 10.5.sp, fontFamily = font)
        }
    }
}

@Composable
private fun FlowConnector(color: Color, flowing: Boolean, dashed: Boolean, phase: Float) {
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - 18.dp.toPx() else 18.dp.toPx()
        val effect = when {
            flowing -> PathEffect.dashPathEffect(floatArrayOf(8f, 6f), -phase)
            dashed -> PathEffect.dashPathEffect(floatArrayOf(5f, 6f), 0f)
            else -> null
        }
        drawLine(
            color = if (flowing) FlowRunning else color,
            start = Offset(x, 0f), end = Offset(x, size.height),
            strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = effect,
        )
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = this.then(
    Modifier.drawBehindDashed(color),
)

private fun Modifier.drawBehindDashed(color: Color): Modifier = this.drawBehind {
    val r = 12.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
        ),
    )
}

@Composable
private fun FlowLegend() {
    val font = homeLocalizedFont()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(TorFlowState.DONE, TorFlowState.RUNNING, TorFlowState.PENDING, TorFlowState.FAILED, TorFlowState.SKIPPED).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(s.color(), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(s.label(), color = UacColors.TextSecondary, fontSize = 10.sp, fontFamily = font)
            }
        }
    }
}