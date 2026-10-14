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
import com.uacspoofer.mobile.auto.XrayFlowBranch
import com.uacspoofer.mobile.auto.XrayFlowStage
import com.uacspoofer.mobile.auto.XrayFlowStageId
import com.uacspoofer.mobile.auto.XrayFlowState
import com.uacspoofer.mobile.auto.XrayLiveFlow
import kotlinx.coroutines.delay
import com.uacspoofer.mobile.ui.theme.UacColors

private val FlowRunning = Color(0xFFFFB547)
private val FlowPending = Color(0xFF5B6B80)

private fun XrayFlowState.color(): Color = when (this) {
    XrayFlowState.DONE -> UacColors.ConnectedGreen
    XrayFlowState.RUNNING -> FlowRunning
    XrayFlowState.FAILED -> UacColors.ErrorRed
    XrayFlowState.SKIPPED -> FlowPending
    XrayFlowState.PENDING -> FlowPending
}

@Composable
private fun XrayFlowState.label(): String = when (this) {
    XrayFlowState.DONE -> homeText("done", "انجام شد")
    XrayFlowState.RUNNING -> homeText("running", "در حال اجرا")
    XrayFlowState.FAILED -> homeText("failed", "ناموفق")
    XrayFlowState.SKIPPED -> homeText("skipped", "رد شد")
    XrayFlowState.PENDING -> homeText("waiting", "در انتظار")
}

@Composable
private fun XrayFlowStageId.title(): String = when (this) {
    XrayFlowStageId.OPERATOR -> homeText("Operator", "شناسایی اپراتور")
    XrayFlowStageId.DPI -> homeText("DPI type", "نوع فیلترینگ (DPI)")
    XrayFlowStageId.LIST -> homeText("Config list", "دریافت لیست کانفیگ")
    XrayFlowStageId.ECH -> homeText("ECH keys", "کلید ECH")
    XrayFlowStageId.CLEAN_IP -> homeText("Clean IP race", "مسابقه IP تمیز")
    XrayFlowStageId.BATCH -> homeText("Batch test", "تست دسته‌ای")
    XrayFlowStageId.RANKING -> homeText("Ranking", "رتبه‌بندی")
    XrayFlowStageId.CONNECT -> homeText("Connect", "اتصال")
    XrayFlowStageId.RESCUE -> homeText("Clean-IP rescue", "نجات با IP تمیز")
    XrayFlowStageId.DNS_ROUTING -> homeText("DNS & routing", "DNS و مسیریابی")
    XrayFlowStageId.FEATURES -> homeText("Active features", "قابلیت‌های فعال")
    XrayFlowStageId.SPEED_RANK -> homeText("Real-speed ranking", "رتبه‌بندی با سرعت واقعی")
    XrayFlowStageId.HEALTH -> homeText("Health check", "پایش سلامت و جایگزینی")
}

@Composable
private fun XrayFlowStageId.hint(): String = when (this) {
    XrayFlowStageId.OPERATOR -> homeText("MCI / Irancell / Rightel / Wi-Fi", "همراه اول، ایرانسل، رایتل یا وای‌فای")
    XrayFlowStageId.DPI -> homeText("sni (MCI) or ip (Irancell), cached 24 h", "فیلتر روی SNI یا IP؛ ۲۴ ساعت در حافظه")
    XrayFlowStageId.LIST -> homeText("GitHub → jsDelivr → GitHub API, ETag", "GitHub ← jsDelivr ← GitHub API با ETag")
    XrayFlowStageId.ECH -> homeText("DNS type 65 over UDP → DoH → cache", "DNS نوع ۶۵ روی UDP، بعد DoH، بعد حافظه")
    XrayFlowStageId.CLEAN_IP -> homeText("Clean Cloudflare IPs for fm+cs configs", "IPهای تمیز Cloudflare برای کانفیگ‌های fm+cs")
    XrayFlowStageId.BATCH -> homeText("24 per xray, 9 s, gstatic 204", "۲۴ تا در هر xray، سقف ۹ ثانیه، gstatic 204")
    XrayFlowStageId.RANKING -> homeText("Top 10 kept per network", "۱۰ کانفیگ برتر هر شبکه نگه داشته می‌شه")
    XrayFlowStageId.CONNECT -> homeText("Best config first, then the next 3", "اول بهترین کانفیگ، بعد ۳ تای بعدی")
    XrayFlowStageId.RESCUE -> homeText("Only if every route failed", "فقط وقتی همه مسیرها شکست بخورن")
    XrayFlowStageId.DNS_ROUTING -> homeText("DoH, Iran direct, IPv6 block", "DoH، ایرانی مستقیم، بستن IPv6")
    XrayFlowStageId.FEATURES -> homeText("Live on/off state", "وضعیت زنده‌ی روشن و خاموش")
    XrayFlowStageId.SPEED_RANK -> homeText("Small download, screen on", "دانلود کوچک وقتی صفحه روشنه")
    XrayFlowStageId.HEALTH -> homeText("Every 30 s when idle; 3 fails → failover", "هر ۳۰ ثانیه در بیکاری؛ ۳ خطا ← کانفیگ بعدی")
}

private val TechRun = Regex("[A-Za-z0-9\\[][A-Za-z0-9.:/_%+\\-\\[\\]@=,]*")

/** Wraps technical runs (IPs, ports, ms, ids, hashes, times) as LTR with Latin digits. */
private fun tech(text: String): androidx.compose.ui.text.AnnotatedString =
    homeLatinDigits(TechRun.replace(text) { m -> if (m.value.any { it.isDigit() }) homeLtr(m.value) else m.value })

private fun formatMs(ms: Long): String = when {
    ms <= 0L -> ""
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000L}m ${(ms / 1000L) % 60}s"
}

private fun clock(at: Long): String =
    if (at <= 0L) "" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))

@Composable
internal fun XrayLiveFlowButton(accent: Color, onClick: () -> Unit) {
    val snap by XrayLiveFlow.snapshot.collectAsStateWithLifecycle()
    val anyFailed = snap.stages.values.any { it.state == XrayFlowState.FAILED }
    val anyRunning = snap.stages.values.any { it.state == XrayFlowState.RUNNING && it.id != XrayFlowStageId.HEALTH }
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
                homeText("Every step of the Xray connect, live", "همه مراحل اتصال Xray، به‌صورت زنده"),
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
internal fun XrayLiveFlowDialog(visible: Boolean, onDismissRequest: () -> Unit) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = homeText("Live path · Xray", "مسیر زنده · Xray"),
        subtitle = homeText("The connect process, step by step", "فرایند اتصال، مرحله به مرحله"),
        icon = Icons.Outlined.Route,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
    ) {
        PowDirectional { XrayLiveFlowContent() }
    }
}

@Composable
private fun XrayLiveFlowContent() {
    val snap by XrayLiveFlow.snapshot.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ticking = snap.stages.values.any { it.state == XrayFlowState.RUNNING }
    // Composed only while the overlay is shown, so this 1 s clock never runs in background.
    LaunchedEffect(ticking) {
        while (ticking) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
        now = System.currentTimeMillis()
    }
    var open by remember { mutableStateOf<XrayFlowStageId?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val adv by remember(context) { com.uacspoofer.mobile.settings.AdvancedSettingsStore(context) }.state.collectAsStateWithLifecycle()
    val guard by remember(context) { com.uacspoofer.mobile.settings.NetworkGuardStore.get(context) }.settings.collectAsStateWithLifecycle()
    val metrics by com.uacspoofer.mobile.vpn.ConnectionMetricsStore.metrics.collectAsStateWithLifecycle()
    val exit by remember(context) { com.uacspoofer.mobile.vpn.ExitIpInfoRepository.get(context) }.state.collectAsStateWithLifecycle()
    val connState by com.uacspoofer.mobile.core.ConnectionStateStore.state.collectAsStateWithLifecycle()
    val on = homeText("on", "روشن")
    val off = homeText("off", "خاموش")
    fun flag(b: Boolean) = if (b) on else off
    val proxyMode = adv.connectionMode == com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
    val featureFacts = listOf(
        homeText("Mode", "حالت") to (if (proxyMode) homeText("Proxy · SOCKS ", "پروکسی · SOCKS ") + adv.socksPort else homeText("Tunnel (VPN)", "تونل (VPN)")),
        homeText("Iranian sites direct", "سایت ایرانی مستقیم") to flag(adv.iranDirect),
        "DNS" to com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers.idFor(adv.dnsResolverUrl),
        homeText("Smart DNS cache", "کش هوشمند DNS") to flag(adv.dnsServeStale),
        "TCP Fast Open" to (if (adv.tcpFastOpen && !com.uacspoofer.mobile.mci.TcpFastOpenSupport.kernelSupports) homeText("kernel lacks it", "کرنل پشتیبانی نمی‌کند") else flag(adv.tcpFastOpen)),
        homeText("Larger buffers", "بافر بزرگ‌تر") to (if (adv.bigBuffers) "256 KB" else "64 KB"),
        homeText("Screen-on warm-up", "آماده‌سازی با روشن شدن صفحه") to flag(adv.prewarm),
        homeText("QUIC blocker", "مسدودکننده QUIC") to (if (guard.quicBlock) homeText("on · ICMP reject", "روشن · رد فوری ICMP") else off),
        "Mux" to (if (adv.muxEnabled) "${on} · ${adv.muxConcurrency}" else off),
        "MTU" to adv.tunMtu.toString(),
        homeText("IPv4 only", "فقط IPv4") to flag(adv.ipv4Only),
        homeText("Domain strategy", "روش resolve") to adv.domainStrategy,
        "Keep-alive" to "${adv.keepAliveIdleSeconds}s / ${adv.keepAliveIntervalSeconds}s",
        homeText("TCP tuning", "تنظیم TCP") to "tcpNoDelay",
    )
    val liveFeatures = XrayFlowStage(
        id = XrayFlowStageId.FEATURES,
        state = if (connState == com.uacspoofer.mobile.core.ConnectionState.CONNECTED) XrayFlowState.DONE else XrayFlowState.PENDING,
        detail = listOfNotNull(
            homeText("Iran direct", "ایرانی مستقیم").takeIf { adv.iranDirect },
            "Fast Open".takeIf { adv.tcpFastOpen },
            "Mux".takeIf { adv.muxEnabled },
            "QUIC".takeIf { guard.quicBlock },
        ).joinToString(" · ").ifEmpty { homeText("defaults", "پیش‌فرض") } + " · MTU ${adv.tunMtu}",
        facts = featureFacts,
    )
    val connectLive = buildList {
        metrics.latencyMs?.let { add(homeText("Latency", "تأخیر") to "$it ms") }
        exit.info?.let { add(homeText("Exit", "خروجی") to "${it.country} · ${it.ipAddress}") }
    }
    val pulse = rememberInfiniteTransition(label = "xrayFlowPulse")
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
                homeText("Connect with Xray to see the live flow.", "برای دیدن مسیر زنده، با Xray وصل شو."),
                color = UacColors.TextSecondary, fontSize = 12.5.sp, fontFamily = homeLocalizedFont(),
                modifier = Modifier.padding(8.dp),
            )
        }
        val order = XrayFlowStageId.entries
        order.forEachIndexed { index, id ->
            val stage = when (id) {
                XrayFlowStageId.FEATURES -> liveFeatures
                XrayFlowStageId.CONNECT -> snap.stage(id).let { st -> if (st.state == XrayFlowState.DONE) st.copy(facts = st.facts + connectLive) else st }
                else -> snap.stage(id)
            }
            FlowNode(
                stage = stage,
                now = now,
                percent = snap.percent,
                phase = snap.phaseText,
                pulseAlpha = pulseAlpha,
                expanded = open == id,
                onClick = { open = if (open == id) null else id },
            )
            if (id == XrayFlowStageId.CONNECT && snap.branches.isNotEmpty()) {
                snap.branches.forEach { FlowBranchCard(it) }
            }
            if (index < order.lastIndex) {
                val next = snap.stage(order[index + 1])
                FlowConnector(
                    color = if (stage.state == XrayFlowState.DONE) UacColors.ConnectedGreen.copy(alpha = 0.7f) else FlowPending.copy(alpha = 0.5f),
                    flowing = next.state == XrayFlowState.RUNNING,
                    dashed = next.state == XrayFlowState.SKIPPED || stage.state == XrayFlowState.SKIPPED,
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
    val progress by animateFloatAsState(percent / 100f, tween(500), label = "xrayPct")
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
            HeaderStat(homeText("Config", "کانفیگ"), bridge.ifBlank { "–" }, Modifier.weight(1.6f))
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
            Text(tech(phase), color = UacColors.TextSecondary, fontSize = 11.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier) {
    val font = homeLocalizedFont()
    Column(modifier) {
        Text(label, color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font)
        Text(
            tech(value), color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = font,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FlowNode(
    stage: XrayFlowStage,
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
    val skipped = stage.state == XrayFlowState.SKIPPED
    val pending = stage.state == XrayFlowState.PENDING
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (pending) 0.6f else 1f)
            .background(Color(0xCC0F1E30), shape)
            .then(
                if (skipped) {
                    Modifier.dashedBorder(color.copy(alpha = 0.6f))
                } else {
                    Modifier.border(1.dp, color.copy(alpha = if (stage.state == XrayFlowState.RUNNING) 0.35f + 0.5f * pulseAlpha else 0.4f), shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp)
                    .alpha(if (stage.state == XrayFlowState.RUNNING) pulseAlpha else 1f)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val suffix = ""
                Text(stage.id.title() + suffix, color = UacColors.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = font)
                Text(
                    tech(stage.detail.takeIf { it.isNotBlank() } ?: stage.id.hint()),
                    color = UacColors.TextSecondary, fontSize = 11.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stage.state.label(), color = color, fontSize = 10.5.sp, fontFamily = font)
                val elapsed = formatMs(stage.elapsedMs(now))
                if (elapsed.isNotEmpty()) Text(tech(elapsed), color = UacColors.TextPrimary, fontSize = 11.sp, fontFamily = font)
            }
        }
        if (stage.id == XrayFlowStageId.BATCH && stage.state != XrayFlowState.PENDING) {
            Spacer(Modifier.height(8.dp))
            val p by animateFloatAsState(percent / 100f, tween(500), label = "batchPct")
            Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x33FFFFFF), CircleShape)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(5.dp).background(color, CircleShape))
            }
            Text(tech("$percent% · $phase"), color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(tech(value), color = UacColors.TextPrimary, fontSize = 11.sp, fontFamily = font, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun FlowBranchCard(branch: XrayFlowBranch) {
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
                tech("${branch.title} ✕  ·  ${clock(branch.at)}"),
                color = UacColors.ErrorRed, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = font,
            )
            Text(tech(branch.reason), color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tech("↻ " + homeText("retry: ", "تلاش بعدی: ") + branch.retry), color = FlowRunning, fontSize = 10.5.sp, fontFamily = font)
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
        listOf(XrayFlowState.DONE, XrayFlowState.RUNNING, XrayFlowState.PENDING, XrayFlowState.FAILED, XrayFlowState.SKIPPED).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(s.color(), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(s.label(), color = UacColors.TextSecondary, fontSize = 10.sp, fontFamily = font)
            }
        }
    }
}