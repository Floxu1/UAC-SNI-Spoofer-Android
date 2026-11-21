package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.auto.AutoBootstrapStore
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.pow.PowPhase
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.direct.DirectPhase
import com.uacspoofer.mobile.engine.direct.DirectIpScan
import com.uacspoofer.mobile.engine.direct.DirectIpStage
import com.uacspoofer.mobile.engine.direct.DirectStatusStore
import com.uacspoofer.mobile.engine.direct.DirectStatus
import com.uacspoofer.mobile.engine.tor.TorFlowState
import com.uacspoofer.mobile.engine.tor.TorLiveFlow
import com.uacspoofer.mobile.ui.theme.AppTheme
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.UacThemeState
import com.uacspoofer.mobile.ui.theme.styleTokens
import kotlinx.coroutines.delay

enum class ConnectOverlayEngine { TOR, POW, DIRECT, GENERIC }

private enum class OverlayMode { HIDDEN, CONNECTING, SUCCESS, FAILED }

@Composable
private fun directPanelPaint(primary: Boolean): ConnectPanelPaint {
    val tokens = styleTokens()
    val accent = when {
        tokens == null -> DirectAccent
        primary -> tokens.gradientStart
        else -> tokens.gradientEnd
    }
    return ConnectPanelPaint(accent, UacColors.Surface.copy(alpha = 0.93f))
}

private fun MiniStepState.color(): Color = when (this) {
    MiniStepState.DONE -> UacColors.ConnectedGreen
    MiniStepState.RUNNING -> UacColors.ConnectingCyan
    MiniStepState.FAILED -> UacColors.ErrorRed
    MiniStepState.SKIPPED -> Color(0xFF3D4856)
    MiniStepState.PENDING -> Color(0xFF52606F)
}

private data class OverlayData(
    val steps: List<MiniStep>,
    val detail: String,
    val engineStart: Long,
    val bootstrapping: Boolean = false,
)

/**
 * Compact floating connect-progress card for the dashboard. It only collects StateFlows the engines
 * already publish, and only while shown; the 1 s elapsed-clock ticker runs only while connecting.
 */
@Composable
internal fun ConnectProgressOverlay(
    state: ConnectionState,
    engine: ConnectOverlayEngine,
    onOpenDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    directDetailsModifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(OverlayMode.HIDDEN) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var endedAt by remember { mutableLongStateOf(0L) }
    var lastState by remember { mutableStateOf(state) }
    val isDirect = engine == ConnectOverlayEngine.DIRECT

    LaunchedEffect(state) {
        val prev = lastState
        lastState = state
        val wasConnecting = mode == OverlayMode.CONNECTING || prev == ConnectionState.CONNECTING
        when (state) {
            ConnectionState.CONNECTING -> {
                if (mode != OverlayMode.CONNECTING) {
                    startedAt = System.currentTimeMillis()
                    endedAt = 0L
                }
                mode = OverlayMode.CONNECTING
            }
            ConnectionState.CONNECTED -> if (wasConnecting) {
                endedAt = System.currentTimeMillis()
                mode = OverlayMode.SUCCESS
                delay(if (isDirect) 3_000L else 1_500L)
                mode = OverlayMode.HIDDEN
            }
            ConnectionState.ERROR -> if (wasConnecting) {
                endedAt = System.currentTimeMillis()
                mode = OverlayMode.FAILED
            }
            // User cancelled (or engine stopped) mid-connect: just hide.
            ConnectionState.DISCONNECTED, ConnectionState.DISCONNECTING ->
                if (mode != OverlayMode.FAILED) mode = OverlayMode.HIDDEN
        }
    }

    val context = LocalContext.current
    val panelStore = remember(context) { ConnectPanelStore.get(context) }
    val panelMode by (if (isDirect) panelStore.directStatus else panelStore.status)
        .collectAsStateWithLifecycle()
    val directStatus = if (isDirect) {
        DirectStatusStore.status.collectAsStateWithLifecycle().value
    } else null
    fun setPanelMode(value: ConnectPanelMode) {
        panelStore.setStatus(value, direct = isDirect)
    }
    val collapsedLift by animateDpAsState(
        targetValue = if (panelMode == ConnectPanelMode.COLLAPSED) (-28).dp else 0.dp,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "status-pill-lift",
    )

    AnimatedVisibility(
        visible = mode != OverlayMode.HIDDEN && panelMode != ConnectPanelMode.CLOSED,
        modifier = modifier,
        enter = fadeIn(tween(220)) +
            slideInVertically(tween(320, easing = FastOutSlowInEasing)) { -it / 2 } +
            scaleIn(tween(320), initialScale = 0.94f),
        exit = fadeOut(tween(220)) +
            slideOutVertically(tween(260)) { -it / 3 } +
            scaleOut(tween(220), targetScale = 0.96f),
    ) {
        val data: OverlayData = when (engine) {
            ConnectOverlayEngine.TOR -> {
                val snap by TorLiveFlow.snapshot.collectAsStateWithLifecycle()
                val steps = ConnectProgressModel.torSteps(snap)
                val failedDetail = snap.stages.values.lastOrNull { it.state == TorFlowState.FAILED }?.detail.orEmpty()
                val focused = steps.getOrNull(ConnectProgressModel.focusIndex(steps))?.detail.orEmpty()
                val detail = when {
                    mode == OverlayMode.FAILED && failedDetail.isNotBlank() -> failedDetail
                    snap.percent in 1..99 -> homeLtr("${snap.percent}%") + "  " + snap.phaseText.ifBlank { focused }
                    else -> focused
                }
                OverlayData(steps, detail, snap.startedAt)
            }
            ConnectOverlayEngine.POW -> {
                val status by PowStatusStore.status.collectAsStateWithLifecycle()
                var lastActive by remember { mutableStateOf(PowPhase.STARTING) }
                if (status.phase != PowPhase.FAILED && status.phase != PowPhase.IDLE) lastActive = status.phase
                val steps = ConnectProgressModel.powSteps(status.phase, lastActive)
                val detail = status.detail.ifBlank { status.steps.lastOrNull()?.text.orEmpty() }
                OverlayData(
                    steps,
                    homeText(
                        PowStatusCopy.h3ScanStage(false, detail) ?: detail,
                        PowStatusCopy.h3ScanStage(true, detail) ?: detail,
                    ),
                    0L,
                )
            }
            ConnectOverlayEngine.DIRECT -> {
                val status = checkNotNull(directStatus)
                val scan = status.ipScan
                OverlayData(
                    ConnectProgressModel.directSteps(status.phase, status.lastActivePhase, scan != null),
                    directProgressDetail(status),
                    status.startedAt,
                )
            }
            ConnectOverlayEngine.GENERIC -> {
                val auto by AutoBootstrapStore.snapshot.collectAsStateWithLifecycle()
                if (auto.active) {
                    val steps = ConnectProgressModel.autoSteps(auto)
                    val focused = steps.getOrNull(ConnectProgressModel.focusIndex(steps))?.detail.orEmpty()
                    val detail = if (mode == OverlayMode.FAILED && auto.error.isNotBlank()) auto.error else focused
                    OverlayData(steps, detail, auto.startedAt, bootstrapping = true)
                } else {
                    OverlayData(emptyList(), "", 0L)
                }
            }
        }
        val steps = when (mode) {
            OverlayMode.SUCCESS -> data.steps.map {
                if (it.state == MiniStepState.SKIPPED) it else it.copy(state = MiniStepState.DONE)
            }
            OverlayMode.FAILED -> if (data.steps.none { it.state == MiniStepState.FAILED }) {
                val f = ConnectProgressModel.focusIndex(data.steps)
                data.steps.mapIndexed { i, s -> if (i == f) s.copy(state = MiniStepState.FAILED) else s }
            } else {
                data.steps
            }
            else -> data.steps
        }

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(mode) {
            while (mode == OverlayMode.CONNECTING) {
                now = System.currentTimeMillis()
                delay(1000)
            }
            now = System.currentTimeMillis()
        }
        // Prefer the engine's own start time when it belongs to this attempt.
        val begin = if (data.engineStart > 0L && kotlin.math.abs(data.engineStart - startedAt) < 10_000L) {
            minOf(data.engineStart, startedAt)
        } else {
            startedAt
        }
        val elapsed = (if (endedAt > 0L && mode != OverlayMode.CONNECTING) endedAt else now) - begin

        val basePaint = if (isDirect) directPanelPaint(primary = false) else connectPanelPaint(primary = false)
        val accent by animateColorAsState(
            when (mode) {
                OverlayMode.SUCCESS -> UacColors.ConnectedGreen
                OverlayMode.FAILED -> UacColors.ErrorRed
                else -> if (isDirect) basePaint.border else UacColors.ConnectingCyan
            },
            tween(400), label = "cpAccent",
        )
        val title = when (mode) {
            OverlayMode.SUCCESS -> homeText("Connected", "وصل شد")
            OverlayMode.FAILED -> homeText("Connection failed", "اتصال ناموفق بود")
            else -> if (data.bootstrapping) homeText("Setting up", "در حال راه‌اندازی") else homeText("Connecting", "در حال اتصال")
        }
        val engineName = when (engine) {
            ConnectOverlayEngine.TOR -> "Tor"
            ConnectOverlayEngine.POW -> "UAC PoW"
            ConnectOverlayEngine.DIRECT -> "UAC Direct"
            ConnectOverlayEngine.GENERIC -> if (data.bootstrapping) "Auto" else ""
        }
        val detail = when {
            mode == OverlayMode.SUCCESS -> homeText("Tunnel is ready", "تونل آماده است")
            mode == OverlayMode.FAILED && data.detail.isBlank() -> homeText("Tap to dismiss", "برای بستن بزنید")
            data.detail.isNotBlank() -> data.detail
            else -> homeText("Preparing secure tunnel…", "در حال آماده‌سازی تونل امن…")
        }
        val font = homeLocalizedFont()
        val paint = if (isDirect) basePaint.copy(border = accent) else basePaint
        val shape = RoundedCornerShape(18.dp)
        val dismiss: () -> Unit = { mode = OverlayMode.HIDDEN }
        val onTap: () -> Unit = when {
            onOpenDetails != null -> onOpenDetails
            !isDirect && mode == OverlayMode.FAILED -> dismiss
            else -> ({})
        }
        val progress by animateFloatAsState(
            if (mode == OverlayMode.SUCCESS) 1f else ConnectProgressModel.progress(steps),
            tween(500, easing = FastOutSlowInEasing), label = "cpProgress",
        )
        val focused = steps.getOrNull(ConnectProgressModel.focusIndex(steps))
        val stepName = focused?.let { homeText(it.english, it.persian) }.orEmpty()
        val collapsedTitle = if (stepName.isBlank()) title else "$title · $stepName"

        Box(
            Modifier
                .widthIn(max = 380.dp)
                .offset(y = collapsedLift)
                .animateContentSize(tween(250, easing = FastOutSlowInEasing)),
            contentAlignment = Alignment.TopCenter,
        ) {
        AnimatedContent(
            targetState = panelMode == ConnectPanelMode.OPEN,
            transitionSpec = {
                ContentTransform(
                    fadeIn(tween(250)),
                    fadeOut(tween(250)),
                    sizeTransform = null,
                )
            },
            label = "status-panel-fold",
        ) { expanded ->
        if (!expanded) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(paint.background)
                    .border(1.dp, paint.border.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        setPanelMode(ConnectPanelMode.OPEN)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(accent, pulsing = mode == OverlayMode.CONNECTING)
                Spacer(Modifier.width(8.dp))
                Text(
                    collapsedTitle, color = UacColors.TextPrimary, fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium, fontFamily = font,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    homeLtr(ConnectProgressModel.formatElapsed(elapsed)),
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.ExpandMore,
                    connectPanelText(R.string.connect_panel_expand),
                    tint = UacColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(paint.background, shape)
                .border(1.dp, paint.border.copy(alpha = 0.75f), shape)
                .then(if (isDirect) Modifier else Modifier.clickable(onClick = onTap))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(accent, pulsing = mode == OverlayMode.CONNECTING)
                Spacer(Modifier.width(9.dp))
                Text(
                    title, color = UacColors.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = font, maxLines = 1,
                )
                if (engineName.isNotEmpty()) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        homeLtr(engineName), color = accent, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    homeLtr(ConnectProgressModel.formatElapsed(elapsed)), color = UacColors.TextSecondary,
                    fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                )
                if (!isDirect && mode == OverlayMode.FAILED) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.Close, homeText("Dismiss", "بستن"), tint = UacColors.TextSecondary,
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = dismiss).padding(2.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                ConnectPanelActions(
                    onCollapse = { setPanelMode(ConnectPanelMode.COLLAPSED) },
                    onClose = { setPanelMode(ConnectPanelMode.CLOSED) },
                )
            }
            Spacer(Modifier.height(9.dp))
            if (steps.isNotEmpty()) {
                MiniStepper(steps, font, runningColor = if (isDirect) accent else UacColors.ConnectingCyan)
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.07f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress).height(2.dp)
                            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.45f), accent))),
                    )
                }
            } else {
                GenericBar(accent, done = mode != OverlayMode.CONNECTING)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                color = if (mode == OverlayMode.FAILED) UacColors.ErrorRed.copy(alpha = 0.9f) else UacColors.TextSecondary,
                fontSize = 11.sp, fontFamily = font, maxLines = if (mode == OverlayMode.FAILED) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mode == OverlayMode.FAILED && onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    homeText("Retry", "تلاش دوباره"),
                    color = UacColors.TextPrimary, fontSize = 12.sp, fontFamily = font, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(UacColors.ErrorRed.copy(alpha = 0.22f))
                        .clickable { mode = OverlayMode.HIDDEN; onRetry() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        }
        }
    }
    if (directStatus != null) {
        val detailsMode by panelStore.directSteps.collectAsStateWithLifecycle()
        DirectConnectDetails(
            status = directStatus,
            visible = mode != OverlayMode.HIDDEN,
            connecting = mode == OverlayMode.CONNECTING,
            panelMode = detailsMode,
            onPanelModeChange = { panelStore.setSteps(it, direct = true) },
            modifier = directDetailsModifier,
        )
    }
}

@Composable
private fun directProgressDetail(status: DirectStatus): String = when {
    status.phase == DirectPhase.SCANNING -> status.ipScan?.let { directScanCount(it) }
        ?: homeText("Checking routes…", "در حال بررسی مسیرها…")
    status.phase == DirectPhase.FAILED -> homeText("Check the connection steps below.", "مراحل اتصال را پایین صفحه ببینید.")
    status.detail == "Trying another route" -> homeText("Trying another route…", "در حال امتحان مسیر دیگری…")
    status.detail == "Testing secure DNS" -> homeText("Testing DNS…", "در حال انتخاب DNS…")
    status.phase == DirectPhase.STARTING -> homeText("Opening the route…", "در حال برقراری اتصال…")
    status.phase == DirectPhase.CHECKING -> homeText("Checking internet access…", "در حال بررسی دسترسی به اینترنت…")
    status.phase == DirectPhase.CONNECTED -> homeText("Internet access verified", "دسترسی به اینترنت تأیید شد")
    else -> homeText("Preparing the connection…", "در حال آماده‌سازی اتصال…")
}

@Composable
private fun DirectConnectDetails(
    status: DirectStatus,
    visible: Boolean,
    connecting: Boolean,
    panelMode: ConnectPanelMode,
    onPanelModeChange: (ConnectPanelMode) -> Unit,
    modifier: Modifier,
) {
    AnimatedVisibility(
        visible = visible && panelMode != ConnectPanelMode.CLOSED,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(320)) { it / 2 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(260)) { it / 3 },
    ) {
        val expanded = panelMode == ConnectPanelMode.OPEN
        BoxWithConstraints(
            Modifier.then(if (expanded) Modifier.widthIn(max = 420.dp) else Modifier)
                .fillMaxWidth().animateContentSize(tween(250)),
        ) {
            val listHeight = minOf(200.dp, maxHeight * 0.28f)
            val steps = ConnectProgressModel.directSteps(status.phase, status.lastActivePhase, status.ipScan != null)
            val font = homeLocalizedFont()
            val paint = directPanelPaint(primary = true)
            val accent = paint.border
            val resultAccent = if (UacThemeState.theme == AppTheme.CLASSIC && status.phase != DirectPhase.FAILED) {
                UacColors.ConnectedGreen
            } else {
                accent
            }
            val shape = RoundedCornerShape(20.dp)
            val glass = Brush.verticalGradient(listOf(lerp(paint.background, accent, 0.035f), paint.background))
            val heading = homeText("Connection details", "جزئیات اتصال")
            val current = steps.getOrNull(ConnectProgressModel.focusIndex(steps))
            val scan = status.ipScan?.takeIf { it.total > 0 }
            val selected = scan?.checks?.firstOrNull { it.stage == DirectIpStage.SELECTED }
            val scanning = connecting && selected == null
            var showChecks by remember(status.startedAt) { mutableStateOf(false) }
            CompositionLocalProvider(
                LocalLayoutDirection provides if (LocalHomePersian.current) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                if (!expanded) {
                    val measurements = listOfNotNull(
                        selected?.pingMs?.let { ping ->
                            val value = homeLtr("$ping ms")
                            homeText("Ping $value", "پینگ $value")
                        },
                        status.selectedDnsLabel?.let { homeLtr(it) },
                    ).joinToString(" · ")
                    val headline = when {
                        status.phase == DirectPhase.FAILED -> homeText("Could not connect", "اتصال برقرار نشد")
                        measurements.isNotBlank() -> measurements
                        status.phase == DirectPhase.SCANNING && scan != null -> directScanCount(scan)
                        else -> directProgressDetail(status)
                    }
                    CollapsedConnectSteps(
                        title = headline,
                        state = current?.state,
                        done = steps.count { it.state == MiniStepState.DONE },
                        total = steps.size,
                        paint = paint,
                        onExpand = { onPanelModeChange(ConnectPanelMode.OPEN) },
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(glass)
                            .border(1.dp, accent.copy(alpha = 0.50f), shape)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DirectDetailsIcon(Icons.Outlined.Route, accent)
                            Text(
                                heading, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            ConnectPanelActions(
                                onCollapse = { onPanelModeChange(ConnectPanelMode.COLLAPSED) },
                                onClose = { onPanelModeChange(ConnectPanelMode.CLOSED) },
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(UacColors.Divider))
                        LazyColumn(Modifier.heightIn(max = listHeight).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (selected != null) {
                                item("selected-route") {
                                    DirectResultRow(
                                        Icons.Outlined.Route,
                                        homeText("Selected route", "مسیر منتخب"),
                                        selected.address.substringBeforeLast(':'),
                                        homeText("Ping", "پینگ"),
                                        selected.pingMs,
                                        font,
                                        resultAccent,
                                    )
                                }
                            }
                            status.selectedDnsLabel?.let { label ->
                                item("selected-dns") {
                                    DirectResultRow(
                                        Icons.Outlined.Public, homeText("Selected DNS", "DNS منتخب"), label,
                                        homeText("Response time", "زمان پاسخ"), status.selectedDnsLatencyMs, font, resultAccent,
                                    )
                                }
                            }
                            if (scan != null) {
                                item("ip-count") {
                                    DirectScanSummary(scan, scanning, showChecks, font, accent) { showChecks = !showChecks }
                                }
                                if (scanning || showChecks) directIpChecks(scan, scanning, font, accent)
                            }
                            if (status.phase == DirectPhase.FAILED ||
                                (selected == null && scan == null && status.selectedDnsLabel == null)
                            ) {
                                item("current-detail") {
                                    Text(
                                        if (status.phase == DirectPhase.CONNECTED) homeText("Internet access verified", "دسترسی به اینترنت تأیید شد")
                                        else if (status.phase == DirectPhase.FAILED && current != null)
                                            homeText("Connection failed during ${current.english.lowercase()}.", "اتصال در مرحلهٔ «${current.persian}» برقرار نشد.")
                                        else directProgressDetail(status),
                                        color = UacColors.TextSecondary, fontSize = 12.sp, fontFamily = font,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectScanSummary(
    scan: DirectIpScan,
    scanning: Boolean,
    expanded: Boolean,
    font: FontFamily?,
    accent: Color,
    onToggle: () -> Unit,
) {
    val checked = scan.sweepChecked ?: scan.checks.count { it.finished }
    val progress by animateFloatAsState(
        (checked.toFloat() / scan.total).coerceIn(0f, 1f),
        tween(220, easing = FastOutSlowInEasing),
        label = "direct-address-scan",
    )
    val expandable = !scanning && scan.checks.any { it.stage != DirectIpStage.SELECTED }
    val shape = RoundedCornerShape(13.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(accent.copy(alpha = 0.07f))
            .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (scanning) homeText("Finding a route", "جست‌وجوی مسیر")
                else homeText("Addresses checked", "آدرس‌های بررسی‌شده"),
                Modifier.weight(1f),
                color = UacColors.TextPrimary, fontSize = 12.sp, fontFamily = font,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                homeLtr("$checked / ${scan.total}"),
                color = accent, fontSize = 11.5.sp, fontFamily = font, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            if (expandable) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    if (expanded) homeText("Hide addresses", "جمع کردن آدرس‌ها") else homeText("Show addresses", "نمایش آدرس‌ها"),
                    tint = UacColors.TextSecondary,
                    modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
                )
            }
        }
        if (scanning) DirectDetailsProgress(progress, accent)
    }
}

@Composable
private fun DirectDetailsProgress(progress: Float, accent: Color) {
    Box(
        Modifier.fillMaxWidth().height(3.dp).progressSemantics(progress).clip(CircleShape)
            .background(accent.copy(alpha = 0.15f)),
    ) {
        Box(Modifier.fillMaxWidth(progress).height(3.dp).background(accent, CircleShape))
    }
}

@Composable
private fun DirectDetailsIcon(icon: ImageVector, accent: Color) {
    Box(
        Modifier.size(28.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DirectResultRow(
    icon: ImageVector,
    label: String,
    value: String,
    latencyLabel: String,
    latencyMs: Long?,
    font: FontFamily?,
    accent: Color,
) {
    Row(
        Modifier.fillMaxWidth().background(UacColors.TextPrimary.copy(alpha = 0.045f), RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DirectDetailsIcon(icon, accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font)
            Text(
                homeLtr(value), color = UacColors.TextPrimary, fontSize = 12.sp, fontFamily = font,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (latencyMs != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(latencyLabel, color = UacColors.TextSecondary, fontSize = 10.sp, fontFamily = font, maxLines = 1)
                Text(
                    homeLtr("$latencyMs ms"), color = accent, fontSize = 12.sp, fontFamily = font,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun directScanCount(scan: DirectIpScan): String {
    val checked = scan.sweepChecked ?: scan.checks.count { it.finished }
    val count = homeLtr("$checked/${scan.total}")
    return homeText("IPs checked $count", "IPهای بررسی‌شده $count")
}

private fun LazyListScope.directIpChecks(scan: DirectIpScan, scanning: Boolean, font: FontFamily?, accent: Color) {
    val checks = scan.checks.asReversed().filter { it.stage != DirectIpStage.SELECTED }
        .sortedBy { !scanning || it.finished }
        items(checks, key = { it.address }) { check ->
            val stopped = !check.finished && !scanning
            val result = if (stopped) homeText("Stopped", "متوقف شد") else when (check.stage) {
                DirectIpStage.HTTPS -> homeText("Checking", "در حال بررسی")
                DirectIpStage.WIREGUARD -> homeText("Testing data", "بررسی انتقال داده")
                DirectIpStage.HTTPS_FAILED -> homeText("No response", "بدون پاسخ")
                DirectIpStage.WIREGUARD_FAILED -> homeText("Data check failed", "انتقال داده ناموفق")
                DirectIpStage.SELECTED -> homeText("Selected", "انتخاب شد")
            }
            val color = if (stopped) UacColors.TextSecondary else when (check.stage) {
                DirectIpStage.HTTPS, DirectIpStage.WIREGUARD -> accent
                DirectIpStage.HTTPS_FAILED, DirectIpStage.WIREGUARD_FAILED -> UacColors.ErrorRed
                DirectIpStage.SELECTED -> UacColors.ConnectedGreen
            }
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    homeLtr(check.address.substringBeforeLast(':')),
                    color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(4.dp).background(color, CircleShape))
                Text(result, color = color, fontSize = 10.5.sp, fontFamily = font, maxLines = 1)
            }
        }
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val t = rememberInfiniteTransition(label = "cpPulse")
            val s by t.animateFloat(1f, 1.9f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "cpPulseS")
            Box(
                Modifier.size(8.dp).scale(s)
                    .background(color.copy(alpha = (0.5f * (1.9f - s) / 0.9f).coerceIn(0f, 0.5f)), CircleShape),
            )
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

@Composable
private fun MiniStepper(steps: List<MiniStep>, font: FontFamily?, runningColor: Color) {
    val focus = ConnectProgressModel.focusIndex(steps)
    val anyRunning = steps.any { it.state == MiniStepState.RUNNING }
    val pulse = if (anyRunning) {
        val t = rememberInfiniteTransition(label = "cpStep")
        val p by t.animateFloat(0.6f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "cpStepP")
        p
    } else {
        1f
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { i, step ->
            val dotColor = if (step.state == MiniStepState.RUNNING) runningColor else step.state.color()
            val c by animateColorAsState(dotColor, tween(350), label = "cpDot$i")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().height(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val left = when {
                        i == 0 -> Color.Transparent
                        steps[i - 1].state == MiniStepState.DONE -> UacColors.ConnectedGreen.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.1f)
                    }
                    Box(Modifier.weight(1f).height(1.5.dp).background(left))
                    Box(
                        Modifier.size(if (i == focus) 10.dp else 8.dp)
                            .scale(if (step.state == MiniStepState.RUNNING) pulse else 1f)
                            .background(if (step.state == MiniStepState.PENDING) Color.Transparent else c, CircleShape)
                            .border(1.5.dp, c, CircleShape),
                    )
                    val right = when {
                        i == steps.lastIndex -> Color.Transparent
                        step.state == MiniStepState.DONE -> UacColors.ConnectedGreen.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.1f)
                    }
                    Box(Modifier.weight(1f).height(1.5.dp).background(right))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    homeText(step.english, step.persian),
                    color = when {
                        step.state == MiniStepState.PENDING || step.state == MiniStepState.SKIPPED ->
                            UacColors.TextSecondary.copy(alpha = 0.6f)
                        step.state == MiniStepState.FAILED -> UacColors.ErrorRed
                        i == focus -> UacColors.TextPrimary
                        else -> UacColors.TextSecondary
                    },
                    fontSize = 9.5.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontWeight = if (i == focus) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun GenericBar(color: Color, done: Boolean) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.07f)),
    ) {
        if (done) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(color))
        } else {
            val t = rememberInfiniteTransition(label = "cpGen")
            val x by t.animateFloat(-0.3f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "cpGenX")
            Box(
                Modifier.offset(x = maxWidth * x).width(maxWidth * 0.3f).height(3.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}
