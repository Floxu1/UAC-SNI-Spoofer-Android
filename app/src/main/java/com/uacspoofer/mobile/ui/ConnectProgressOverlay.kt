package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.auto.AutoBootstrapStore
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.engine.pow.PowPhase
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.direct.DirectPhase
import com.uacspoofer.mobile.engine.direct.DirectStatusStore
import com.uacspoofer.mobile.engine.tor.TorFlowState
import com.uacspoofer.mobile.engine.tor.TorLiveFlow
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.delay

enum class ConnectOverlayEngine { TOR, POW, DIRECT, GENERIC }

private enum class OverlayMode { HIDDEN, CONNECTING, SUCCESS, FAILED }

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
) {
    var mode by remember { mutableStateOf(OverlayMode.HIDDEN) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var endedAt by remember { mutableLongStateOf(0L) }
    var lastState by remember { mutableStateOf(state) }

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
                delay(1500)
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

    AnimatedVisibility(
        visible = mode != OverlayMode.HIDDEN,
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
                OverlayData(steps, status.detail.ifBlank { status.steps.lastOrNull()?.text.orEmpty() }, 0L)
            }
            ConnectOverlayEngine.DIRECT -> {
                val status by DirectStatusStore.status.collectAsStateWithLifecycle()
                var lastActive by remember { mutableStateOf(DirectPhase.BUILDING) }
                if (status.phase != DirectPhase.FAILED && status.phase != DirectPhase.IDLE) lastActive = status.phase
                OverlayData(ConnectProgressModel.directSteps(status.phase, lastActive), status.detail, status.startedAt)
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

        val accent by animateColorAsState(
            when (mode) {
                OverlayMode.SUCCESS -> UacColors.ConnectedGreen
                OverlayMode.FAILED -> UacColors.ErrorRed
                else -> UacColors.ConnectingCyan
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
        val shape = RoundedCornerShape(18.dp)
        val dismiss: () -> Unit = { mode = OverlayMode.HIDDEN }
        val onTap: () -> Unit = when {
            onOpenDetails != null -> onOpenDetails
            mode == OverlayMode.FAILED -> dismiss
            else -> ({})
        }
        val progress by animateFloatAsState(
            if (mode == OverlayMode.SUCCESS) 1f else ConnectProgressModel.progress(steps),
            tween(500, easing = FastOutSlowInEasing), label = "cpProgress",
        )

        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xF0142231), Color(0xEB0A1520))), shape)
                .border(1.dp, accent.copy(alpha = 0.35f), shape)
                .clickable(onClick = onTap)
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
                if (mode == OverlayMode.FAILED) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.Close, homeText("Dismiss", "بستن"), tint = UacColors.TextSecondary,
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = dismiss).padding(2.dp),
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            if (steps.isNotEmpty()) {
                MiniStepper(steps, font)
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
private fun MiniStepper(steps: List<MiniStep>, font: FontFamily?) {
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
            val c by animateColorAsState(step.state.color(), tween(350), label = "cpDot$i")
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