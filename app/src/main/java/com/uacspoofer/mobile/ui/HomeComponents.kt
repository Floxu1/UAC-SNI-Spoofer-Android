package com.uacspoofer.mobile.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.util.lerp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.ui.theme.NeonTokens
import com.uacspoofer.mobile.ui.theme.styleTokens
import com.uacspoofer.mobile.ui.theme.neonDp
import com.uacspoofer.mobile.ui.theme.neonSp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeChangeResult
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.canChangeEngineMode
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.direct.DirectStatusStore
import com.uacspoofer.mobile.engine.pow.PowStepState
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun HomeHeader(
    accent: Color,
    compact: Boolean,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    engineToggleEnabled: Boolean = false,
    onEngineLaidOut: ((LayoutCoordinates) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val engineStore = remember(context) { EngineModeStore.get(context) }
    val engineMode by engineStore.mode.collectAsStateWithLifecycle()
    var pendingEngine by remember { mutableStateOf<EngineMode?>(null) }
    val neonMenu = styleTokens()
    val iconSize = if (neonMenu != null) neonDp(compact, 24f, 22f, neonMenu.menuIcon) else if (compact) 22.dp else 24.dp
    val buttonSize = if (neonMenu != null) neonDp(compact, 42f, 38f, neonMenu.menuButton) else if (compact) 38.dp else 42.dp
    val menuTint = neonMenu?.menuColor ?: UacColors.TextPrimary
    val menuAlpha = neonMenu?.menuAlpha ?: 1f
    val drawerOpen = LocalDrawerOpen.current
    val guideSession = LocalHomeGuideSession.current

    LaunchedEffect(pendingEngine) {
        val target = pendingEngine ?: return@LaunchedEffect
        if (target == engineStore.snapshot()) {
            pendingEngine = null
            return@LaunchedEffect
        }
        val state = ConnectionStateStore.state.value
        val reconnect = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
        if (reconnect) {
            ConnectionStateStore.tryBeginDisconnect()
            runCatching { VpnController.stop(context) }
            val settled = withTimeoutOrNull(15_000L) {
                ConnectionStateStore.state.first { canChangeEngineMode(it) }
            }
            if (settled == null) {
                pendingEngine = null
                return@LaunchedEffect
            }
        }
        if (engineStore.setMode(target) == EngineModeChangeResult.APPLIED && reconnect) {
            delay(250)
            VpnController.start(context)
        }
        pendingEngine = null
    }

    val headerHeight = if (compact) 44.dp else 48.dp
    if (!engineToggleEnabled) {
        Row(
            modifier = modifier.height(headerHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteIconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .size(buttonSize)
                    .then(if (neonMenu != null) Modifier.graphicsLayer { alpha = menuAlpha } else Modifier)
                    .focusProperties { canFocus = !drawerOpen }
                    .trackHomeSlot(HomeRemoteSlot.Menu)
                    .openDrawerOnDpadLeft(onMenuClick),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open navigation menu",
                    tint = menuTint,
                    modifier = Modifier.size(iconSize),
                )
            }
            Icon(
                imageVector = Icons.Outlined.VerifiedUser,
                contentDescription = "Connection status",
                tint = accent,
                modifier = Modifier.size(iconSize),
            )
        }
        return
    }

    Box(
        modifier = modifier
            .height(headerHeight)
            .zIndex(8f),
    ) {
        RemoteIconButton(
            onClick = onMenuClick,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(buttonSize)
                .then(if (neonMenu != null) Modifier.graphicsLayer { alpha = menuAlpha } else Modifier)
                .focusProperties { canFocus = !drawerOpen }
                .trackHomeSlot(HomeRemoteSlot.Menu)
                .openDrawerOnDpadLeft(onMenuClick),
        ) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = "Open navigation menu",
                tint = menuTint,
                modifier = Modifier.size(iconSize),
            )
        }
        EngineSwitchRail(
            selected = engineMode,
            pending = pendingEngine,
            compact = compact,
            enabled = pendingEngine == null,
            onSelect = { target ->
                if (pendingEngine != null || target == engineStore.snapshot()) return@EngineSwitchRail
                if (canChangeEngineMode(ConnectionStateStore.state.value)) {
                    engineStore.setMode(target)
                } else {
                    pendingEngine = target
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .wrapContentHeight(unbounded = true, align = Alignment.Top)
                .then(
                    if (onEngineLaidOut != null) {
                        Modifier.onGloballyPositioned(onEngineLaidOut)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (guideSession?.step == HomeGuideStep.Engine) {
                        Modifier.guideTargetDpad(guideSession.gotIt)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
internal fun AppTitle(compact: Boolean, accent: Color) {
    val engineMode = rememberDisplayedEngineMode()
    val neonTitle = styleTokens()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = if (neonTitle != null) neonDp(compact, 64f, 56f, neonTitle.titlePad) else if (compact) 56.dp else 64.dp)
            .then(if (neonTitle != null) Modifier.graphicsLayer { alpha = neonTitle.titleAlpha } else Modifier),
    ) {
        Text(
            text = when {
                engineMode.isTor -> "UAC TOR BRIDGE"
                engineMode.isPow -> "UAC PoW"
                engineMode.isDirect -> "UAC DIRECT"
                else -> "UAC SNI SPOOFER"
            },
            fontSize = if (neonTitle != null) neonSp(compact, 23f, 20f, neonTitle.titleSize) else if (compact) 20.sp else 23.sp,
            fontWeight = if (neonTitle != null) FontWeight(neonTitle.titleWeight) else FontWeight.ExtraBold,
            letterSpacing = 0.55.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(
                brush = if (neonTitle != null) {
                    Brush.horizontalGradient(
                        0f to neonTitle.titleStart,
                        0.45f to neonTitle.titleMid,
                        1f to neonTitle.titleEnd,
                    )
                } else {
                    Brush.horizontalGradient(
                        0f to accent,
                        0.30f to Color(0xFFDFF8FF),
                        0.58f to Color.White,
                        0.82f to Color(0xFFB8DFFF),
                        1f to accent,
                    )
                },
                shadow = Shadow(
                    color = (if (neonTitle != null) neonTitle.titleGlow else accent).copy(alpha = neonTitle?.titleGlowAlpha ?: 0.62f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = neonTitle?.titleGlowBlur ?: 16f,
                ),
            ),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(if (neonTitle != null) neonDp(compact, 154f, 132f, neonTitle.underlineWidth) else if (compact) 132.dp else 154.dp)
                .height(if (neonTitle != null) neonTitle.underlineHeight.dp else 2.dp)
                .background(
                    if (neonTitle != null) {
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, neonTitle.underlineStart, neonTitle.underlineMid, neonTitle.underlineStart, Color.Transparent),
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, accent.copy(alpha = 0.95f), Color.White, accent.copy(alpha = 0.95f), Color.Transparent),
                        )
                    },
                    RoundedCornerShape(50),
                ),
        )
    }
}

@Composable
internal fun ConnectButton(
    state: ConnectionState,
    accent: Color,
    diameter: Dp,
    onClick: () -> Unit,
    halo: Dp = 54.dp,
) {
    val isPersian = LocalHomePersian.current
    val localizedFont = homeLocalizedFont()
    val interactionDisabled = state == ConnectionState.DISCONNECTING
    val transition = rememberInfiniteTransition(label = "connect-glow")
    val animatedGlow = transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-glow-intensity",
    )
    val loadingRotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connect-loading-rotation",
    )
    val loadingSweep = transition.animateFloat(
        initialValue = 58f,
        targetValue = 292f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 880, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-loading-sweep",
    )
    val styledTokens = styleTokens()
    val orbitMs = styledTokens?.connectOrbitMs?.toInt()?.coerceIn(300, 8_000) ?: 2_000
    val sweepLo = styledTokens?.connectSweepMin ?: 100f
    val sweepHi = styledTokens?.connectSweepMax ?: 150f
    val ringBreath = transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-ring-breath",
    )
    val iconBreath = transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-icon-breath",
    )
    val cometOrbit = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = orbitMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connect-comet-orbit",
    )
    val cometSweep = transition.animateFloat(
        initialValue = minOf(sweepLo, sweepHi),
        targetValue = maxOf(sweepLo, sweepHi),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-comet-sweep",
    )
    val connectExit = animateFloatAsState(
        targetValue = if (styledTokens != null && state == ConnectionState.CONNECTING) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "connect-comet-fade",
    )
    val ringBrushes = remember { SweepRingBrushes() }
    val emphasizePersianLabel = isPersian
    val buttonLabel = when (state) {
        ConnectionState.DISCONNECTED -> homeText("CONNECT", "اتصال")
        ConnectionState.CONNECTING -> homeText("CANCEL", "لغو")
        ConnectionState.CONNECTED -> homeText("DISCONNECT", "قطع اتصال")
        ConnectionState.DISCONNECTING -> homeText("DISCONNECTING...", "در حال قطع...")
        ConnectionState.ERROR -> homeText("RETRY", "تلاش دوباره")
    }

    val neonTokens = styledTokens
    val gpuGlow = neonTokens != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val density = LocalDensity.current
    val outerGlow = remember { NeonGlowBuffer() }
    val innerGlow = remember { NeonGlowBuffer() }
    val buttonAlpha = neonTokens?.buttonAlpha ?: 1f
    val neonGlowBox = neonTokens?.let { tuned ->
        val blurDp = maxOf(tuned.glowBlur, tuned.glowBlurConnected, tuned.connectFarBlur, 0f)
        val outerReach = tuned.ringWidth / 2f +
            tuned.hairlineStart +
            tuned.hairlineGap * (tuned.hairlineCount - 1).coerceAtLeast(0) +
            tuned.hairlineWidth / 2f
        diameter + (outerReach * 2f).dp + (blurDp * 2f).dp + 24.dp
    }
    Box(
        modifier = Modifier
            .size(neonGlowBox?.let { maxOf(diameter + halo, it) } ?: (diameter + halo))
            .then(
                if (neonTokens != null) {
                    Modifier.graphicsLayer {
                        alpha = buttonAlpha
                        clip = false
                    }
                } else {
                    Modifier
                },
            )
            .trackHomeSlot(HomeRemoteSlot.Connect)
            .clickable(enabled = !interactionDisabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val outerAwake = state == ConnectionState.CONNECTED
        if (gpuGlow && neonTokens != null && outerAwake) {
            val tuned = neonTokens
            val outerBlurPx = with(density) { tuned.glowBlurConnected.dp.toPx() }
            GpuBlurredRing(
                modifier = Modifier.matchParentSize(),
                blurPx = outerBlurPx,
                pulse = null,
                resting = 1f,
                glide = connectExit,
                glideIdle = 0f,
                glideSettled = 1f,
                colorAlpha = tuned.glowAlpha,
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val surfaceRadius = diameter.toPx() / 2f
                val tube = tuned.ringWidth.dp.toPx()
                val tubeRadius = surfaceRadius + tube / 2f
                rotate(tuned.gradientAngle, center) {
                    drawCircle(
                        brush = neonSweep(tuned.gradientStart, tuned.gradientEnd, center),
                        radius = tubeRadius + tuned.glowSpread.dp.toPx(),
                        center = center,
                        style = Stroke(width = tube),
                    )
                }
            }
        }
        val ringBreathing = neonTokens != null && connectExit.value > 0.001f
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (ringBreathing) {
                        Modifier.graphicsLayer {
                            val exit = connectExit.value
                            alpha = lerp(1f, ringBreath.value, exit)
                            clip = false
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val surfaceRadius = diameter.toPx() / 2f
            val atmosphericRadius = size.minDimension / 2f
            val haloPx = halo.toPx()
            val neon = com.uacspoofer.mobile.ui.theme.UacThemeState.isNeon
            fun ring(alpha: Float): Brush {
                val tuned = neonTokens
                if (!neon || tuned == null) return SolidColor(accent.copy(alpha = alpha))
                return ringBrushes.sweep(alpha, center, tuned.gradientStart, tuned.gradientEnd)
            }

            if (neon && neonTokens != null) {
                val tuned = neonTokens
                val exit = connectExit.value
                val tube = tuned.ringWidth.dp.toPx()
                val tubeRadius = surfaceRadius + tube / 2f
                val idle = tuned.glowDisconnected
                val settled = if (state == ConnectionState.CONNECTED) 1f else idle
                val tubeGain = lerp(settled, idle, exit)
                val hairSettled = if (state == ConnectionState.CONNECTED) settled else idle * 0.18f
                val hairGain = lerp(hairSettled, idle * 0.18f, exit)
                rotate(tuned.gradientAngle, center) {
                if (!gpuGlow && state == ConnectionState.CONNECTED) drawBlurredNeonRing(
                    center = center,
                        radius = tubeRadius + tuned.glowSpread.dp.toPx(),
                    stroke = tube,
                        blur = tuned.glowBlurConnected.dp.toPx(),
                        alpha = (tuned.glowAlpha * (1f - exit)).toInt().coerceIn(0, 255),
                    buffer = outerGlow,
                        gradientStart = tuned.gradientStart,
                        gradientEnd = tuned.gradientEnd,
                )
                drawCircle(
                    brush = ring(tubeGain),
                    radius = tubeRadius,
                    center = center,
                    style = Stroke(width = tube),
                )
                drawCircle(
                        color = tuned.ringCore.copy(alpha = tuned.ringCore.alpha * tuned.ringCoreAlpha * tubeGain),
                    radius = tubeRadius,
                    center = center,
                        style = Stroke(width = tuned.ringCoreWidth.dp.toPx()),
                    )
                    val hairAlphas = floatArrayOf(
                        tuned.hairlineAlpha1,
                        tuned.hairlineAlpha2,
                        tuned.hairlineAlpha3,
                        tuned.hairlineAlpha4,
                    )
                    repeat(tuned.hairlineCount.coerceIn(0, hairAlphas.size)) { index ->
                        val gap = (tuned.hairlineStart + tuned.hairlineGap * index).dp.toPx()
                        drawCircle(
                            brush = ring(hairAlphas[index] * hairGain),
                            radius = tubeRadius + gap,
                            center = center,
                            style = Stroke(width = tuned.hairlineWidth.dp.toPx()),
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
            } else {
            val glowIntensity = if (state == ConnectionState.CONNECTING) animatedGlow.value else 1f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.54f to accent.copy(alpha = 0.015f * glowIntensity),
                        0.67f to accent.copy(alpha = 0.20f * glowIntensity),
                        0.76f to accent.copy(alpha = 0.34f * glowIntensity),
                        0.87f to accent.copy(alpha = 0.14f * glowIntensity),
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = atmosphericRadius,
                ),
                center = center,
                radius = atmosphericRadius,
            )
            drawCircle(
                brush = ring(0.075f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.50f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                brush = ring(0.13f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.33f,
                center = center,
                style = Stroke(width = 1.4.dp.toPx()),
            )
            drawCircle(
                brush = ring(0.29f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.15f,
                center = center,
                style = Stroke(width = (7.dp.toPx() * (haloPx / 54.dp.toPx()).coerceIn(0.55f, 1f))),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.10f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.07f,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )
            if (!neon && state == ConnectionState.CONNECTING) {
                val progressRadius = surfaceRadius + haloPx * 0.15f
                val progressTopLeft = Offset(center.x - progressRadius, center.y - progressRadius)
                val progressSize = Size(progressRadius * 2f, progressRadius * 2f)
                drawArc(
                    brush = ring(0.09f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 11.dp.toPx()),
                )
                drawArc(
                    brush = ring(0.18f * glowIntensity),
                    startAngle = loadingRotation.value,
                    sweepAngle = loadingSweep.value,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        colorStops = if (neon) {
                            arrayOf(
                                0f to Color(0xFFFF2BD6).copy(alpha = 0.15f),
                                0.55f to Color(0xFF22D3EE),
                                0.82f to Color.White,
                                1f to Color(0xFFFF2BD6).copy(alpha = 0.20f),
                            )
                        } else {
                            arrayOf(
                                0f to accent.copy(alpha = 0.15f),
                                0.55f to accent,
                                0.82f to Color.White,
                                1f to accent.copy(alpha = 0.20f),
                            )
                        },
                        center = center,
                    ),
                    startAngle = loadingRotation.value,
                    sweepAngle = loadingSweep.value,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 4.6.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.96f),
                    startAngle = loadingRotation.value + loadingSweep.value - 7f,
                    sweepAngle = 7f,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            }
        }

        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        listOf(
                            neonTokens?.centerInner ?: UacColors.ButtonCenter,
                            neonTokens?.centerOuter ?: UacColors.ButtonEdge,
                        ),
                    ),
                    shape = CircleShape,
                )
                .semantics { contentDescription = buttonLabel },
            contentAlignment = Alignment.Center,
        ) {
            if (gpuGlow && neonTokens != null) {
                val tuned = neonTokens
                val innerBlurPx = with(density) { tuned.innerGlowBlur.dp.toPx() }
                GpuBlurredRing(
                    modifier = Modifier.matchParentSize(),
                    blurPx = innerBlurPx,
                    pulse = null,
                    resting = tuned.glowDisconnected,
                    glide = connectExit,
                    glideBreath = ringBreath,
                    glideIdle = tuned.glowDisconnected,
                    glideSettled = if (state == ConnectionState.CONNECTED) 1f else tuned.glowDisconnected,
                    colorAlpha = tuned.innerGlowAlpha,
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = size.minDimension / 2f - 2.dp.toPx()
                    rotate(tuned.gradientAngle, center) {
                        drawCircle(
                            brush = neonSweep(tuned.gradientStart, tuned.gradientEnd, center),
                            radius = outerRadius - tuned.innerGlowInset.dp.toPx(),
                            center = center,
                            style = Stroke(width = tuned.ringWidth.dp.toPx()),
                        )
                    }
                }
            }
            Canvas(modifier = Modifier.matchParentSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 2.dp.toPx()
                val neonDisc = com.uacspoofer.mobile.ui.theme.UacThemeState.isNeon
                if (neonDisc) {
                    val tuned = neonTokens
                    if (tuned != null && !gpuGlow) {
                    val exit = connectExit.value
                    val breath = if (exit > 0.001f) ringBreath.value else 1f
                    val idle = tuned.glowDisconnected
                    val settled = if (state == ConnectionState.CONNECTED) 1f else idle
                    val inward = lerp(settled, idle * breath, exit)
                        rotate(tuned.gradientAngle, center) {
                    drawBlurredNeonRing(
                        center = center,
                                radius = outerRadius - tuned.innerGlowInset.dp.toPx(),
                                stroke = tuned.ringWidth.dp.toPx(),
                                blur = tuned.innerGlowBlur.dp.toPx(),
                                alpha = (tuned.innerGlowAlpha * inward).toInt().coerceIn(0, 255),
                        buffer = innerGlow,
                                gradientStart = tuned.gradientStart,
                                gradientEnd = tuned.gradientEnd,
                    )
                        }
                    }
                    return@Canvas
                }
                val glowIntensity = if (!gpuGlow && state == ConnectionState.CONNECTING) animatedGlow.value else 1f
                fun discRingBrush(alpha: Float): Brush {
                    if (!neonDisc) return SolidColor(accent.copy(alpha = alpha))
                    val magenta = Color(0xFFFF2BD6).copy(alpha = alpha)
                    val cyan = Color(0xFF22D3EE).copy(alpha = alpha)
                    return Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.00f to magenta,
                            0.22f to magenta,
                            0.42f to cyan,
                            0.72f to cyan,
                            0.90f to magenta,
                            1.00f to magenta,
                        ),
                        center = center,
                    )
                }
                val inwardGain = when (state) {
                    ConnectionState.CONNECTED -> 1.15f
                    ConnectionState.CONNECTING -> glowIntensity
                    else -> 0.62f
                }
                if (neonDisc) {
                    listOf(
                        40.dp to 0.08f,
                        28.dp to 0.18f,
                        16.dp to 0.30f,
                        8.dp to 0.50f,
                    ).forEach { (width, alpha) ->
                        drawCircle(
                            brush = discRingBrush(alpha * inwardGain),
                            radius = outerRadius,
                            center = center,
                            style = Stroke(width = width.toPx()),
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
                val discGlow = if (neonDisc) {
                    discRingBrush(0.34f * glowIntensity)
                } else {
                    SolidColor(accent.copy(alpha = 0.34f * glowIntensity))
                }
                val discRing = if (neonDisc) {
                    discRingBrush(1f)
                } else {
                    Brush.sweepGradient(
                        listOf(
                            accent,
                            Color.White.copy(alpha = 0.90f),
                            accent,
                            accent.copy(alpha = 0.76f),
                            accent,
                        ),
                        center = center,
                    )
                }
                drawCircle(
                    brush = discGlow,
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 10.dp.toPx()),
                )
                drawCircle(
                    brush = discRing,
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 3.2.dp.toPx()),
                )
                val arcBounds = androidx.compose.ui.geometry.Size(
                    size.width - 4.dp.toPx(),
                    size.height - 4.dp.toPx(),
                )
                val arcTopLeft = Offset(2.dp.toPx(), 2.dp.toPx())
                if (neonDisc) {
                    drawArc(
                        brush = discRingBrush(0.20f * inwardGain),
                        startAngle = 208f,
                        sweepAngle = 104f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcBounds,
                        style = Stroke(width = 4.dp.toPx()),
                        blendMode = BlendMode.Plus,
                    )
                }
                drawArc(
                    brush = if (neonDisc) discRingBrush(0.70f) else SolidColor(Color.White.copy(alpha = 0.34f)),
                    startAngle = 208f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcBounds,
                    style = Stroke(width = 0.9.dp.toPx()),
                )
                if (neonDisc) {
                    drawCircle(
                        brush = discRingBrush(0.18f * inwardGain),
                        radius = outerRadius - 6.dp.toPx(),
                        center = center,
                        style = Stroke(width = 4.dp.toPx()),
                        blendMode = BlendMode.Plus,
                    )
                }
                drawCircle(
                    brush = if (neonDisc) discRingBrush(0.95f) else SolidColor(UacColors.ButtonInnerRing),
                    radius = outerRadius - 6.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.1.dp.toPx()),
                )
            }

            val neonFace = neonTokens != null
            val iconTint = when {
                neonTokens == null -> accent
                state == ConnectionState.CONNECTED || state == ConnectionState.DISCONNECTING -> neonTokens.iconActive
                else -> neonTokens.iconIdle
            }
            val labelColor = when {
                neonTokens == null -> accent
                state == ConnectionState.CONNECTED || state == ConnectionState.DISCONNECTING -> neonTokens.labelActive
                else -> neonTokens.labelIdle
            }
            val labelBase = when {
                emphasizePersianLabel && diameter < 150.dp -> 14f
                emphasizePersianLabel -> 18f
                buttonLabel.length > 11 -> 10.5f
                else -> 13f
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .size(diameter * (neonTokens?.iconScale ?: 0.21f))
                        .then(
                            if (neonTokens != null && connectExit.value > 0.001f) {
                                Modifier.graphicsLayer {
                                    alpha = lerp(1f, iconBreath.value, connectExit.value)
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
                Spacer(Modifier.height(if (diameter < 150.dp) 4.dp else 8.dp))
                Text(
                    text = buttonLabel,
                    color = labelColor,
                    fontSize = (labelBase * ((neonTokens?.labelSize ?: 13f) / 13f)).sp,
                    fontWeight = if (neonTokens != null) {
                        FontWeight(if (emphasizePersianLabel) neonTokens.labelWeightFa else neonTokens.labelWeight)
                    } else if (emphasizePersianLabel) {
                        FontWeight.Bold
                    } else {
                        FontWeight.SemiBold
                    },
                    fontFamily = localizedFont,
                    letterSpacing = if (isPersian) 0.sp else 0.55.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                        shadow = when {
                            neonFace -> Shadow(
                                color = labelColor.copy(alpha = neonTokens?.labelGlowAlpha ?: 0.55f),
                                offset = Offset.Zero,
                                blurRadius = neonTokens?.labelGlowBlur ?: 10f,
                            )
                            emphasizePersianLabel -> Shadow(
                                color = accent.copy(alpha = 0.42f),
                                offset = Offset.Zero,
                                blurRadius = 9f,
                            )
                            else -> null
                        },
                    ),
                )
            }
        }

        if (neonTokens != null) {
            ConnectingComet(
                tokens = neonTokens,
                diameter = diameter,
                orbit = cometOrbit,
                sweep = cometSweep,
                fade = connectExit,
            )
        }
    }
}

@Composable
internal fun ConnectionStatus(state: ConnectionState, accent: Color) {
    val isPersian = LocalHomePersian.current
    val localizedFont = homeLocalizedFont()
    val context = LocalContext.current
    val panelStore = remember(context) { ConnectPanelStore.get(context) }
    val statusPanel by panelStore.status.collectAsStateWithLifecycle()
    val stepsPanel by panelStore.steps.collectAsStateWithLifecycle()
    val engineMode = rememberDisplayedEngineMode()
    val advancedStore = remember(context) { AdvancedSettingsStore(context) }
    val advanced by advancedStore.state.collectAsStateWithLifecycle()
    val torStatus by TorStatusStore.status.collectAsStateWithLifecycle()
    val powStatus by PowStatusStore.status.collectAsStateWithLifecycle()
    val directStatus by DirectStatusStore.status.collectAsStateWithLifecycle()
    val routeProgress by ConnectionStateStore.routeProgress.collectAsStateWithLifecycle()
    val killSwitchBlocking by NetworkGuardStore.blocking.collectAsStateWithLifecycle()
    var showRouteProgress by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state != ConnectionState.CONNECTING) {
            showRouteProgress = false
            return@LaunchedEffect
        }
        showRouteProgress = false
        delay(CONNECTING_ROUTE_HINT_DELAY_MS)
        showRouteProgress = true
    }
    val status = when {
        killSwitchBlocking && state == ConnectionState.ERROR ->
            homeText("Internet blocked", "اینترنت مسدود است")
        else -> when (state) {
            ConnectionState.DISCONNECTED -> homeText("Disconnected", "وصل نیست")
            ConnectionState.CONNECTING -> {
                val detail = powStatus.detail.trim()
                when {
                    engineMode.isPow && detail.startsWith("SIM changed") ->
                        homeText("SIM changed…", "سیم‌کارت عوض شد…")
                    else -> homeText("Connecting...", "در حال اتصال…")
                }
            }
            ConnectionState.CONNECTED -> homeText("Connected", "وصل شد")
            ConnectionState.DISCONNECTING -> homeText("Disconnecting...", "در حال قطع...")
            ConnectionState.ERROR -> homeText("Connection failed", "اتصال برقرار نشد")
        }
    }
    val connectingHint = when {
        engineMode.isTor -> homeText(
            TorStatusCopy.connectingHint(
                persian = false,
                percent = torStatus.bootstrapPercent,
                phase = torStatus.phase,
                detail = torStatus.detail,
                showRouteProgress = showRouteProgress,
            ),
            TorStatusCopy.connectingHint(
                persian = true,
                percent = torStatus.bootstrapPercent,
                phase = torStatus.phase,
                detail = torStatus.detail,
                showRouteProgress = showRouteProgress,
            ),
        )
        engineMode.isPow -> homeText(
            PowStatusCopy.connectingHint(
                persian = false,
                percent = powStatus.progressPercent,
                phase = powStatus.phase,
                detail = powStatus.detail,
                showRouteProgress = showRouteProgress,
            ),
            PowStatusCopy.connectingHint(
                persian = true,
                percent = powStatus.progressPercent,
                phase = powStatus.phase,
                detail = powStatus.detail,
                showRouteProgress = showRouteProgress,
            ),
        )
        engineMode.isDirect -> homeText(
            "Starting direct mode (no server) ${directStatus.progressPercent}%",
            "در حال راه‌اندازی حالت مستقیم (بدون سرور) ${directStatus.progressPercent}٪",
        )
        else -> when {
            showRouteProgress && routeProgress.isActive -> homeText(
                "Connecting with route ${routeProgress.current}/${routeProgress.total}",
                "اتصال با مسیر ${routeProgress.current}/${routeProgress.total}",
            )
            else -> homeText("Establishing a secure tunnel", "در حال ساخت اتصال امن")
        }
    }
    val hint = when {
        killSwitchBlocking && state == ConnectionState.ERROR -> homeText(
            "Kill switch is on · tap to reconnect",
            "کلید قطع اضطراری روشن است · برای وصل دوباره بزن",
        )
        else -> when (state) {
        ConnectionState.DISCONNECTED -> homeText("Tap the button to connect", "برای وصل شدن، دکمه رو بزن")
        ConnectionState.CONNECTING -> connectingHint
        ConnectionState.CONNECTED -> when {
            engineMode.isTor -> if (advanced.connectionMode == CONNECTION_MODE_PROXY) {
                homeText("Local Tor SOCKS only · no device VPN", "فقط SOCKS محلی Tor · بدون VPN دستگاه")
            } else {
                homeText("Device VPN is routing through Tor", "VPN دستگاه از Tor می‌گذره")
            }
            engineMode.isPow -> if (advanced.connectionMode == CONNECTION_MODE_PROXY) {
                homeText("Local UAC PoW SOCKS only · no device VPN", "فقط SOCKS محلی UAC PoW · بدون VPN دستگاه")
            } else {
                homeText("Device VPN is routing through UAC PoW", "VPN دستگاه از UAC PoW می‌گذره")
            }
            engineMode.isDirect -> if (advanced.connectionMode == CONNECTION_MODE_PROXY) {
                homeText("Local SOCKS only · no device VPN", "فقط SOCKS محلی · بدون VPN دستگاه")
            } else {
                homeText(
                    "Direct, no server · IP-blocked sites stay unreachable",
                    "مستقیم و بدون سرور · سایت‌هایی که IPشون بسته‌ست باز نمی‌شن",
                )
            }
            else -> if (advanced.connectionMode == CONNECTION_MODE_PROXY) {
                homeText("Local SOCKS only · no device VPN", "فقط SOCKS محلی · بدون VPN دستگاه")
            } else {
                homeText("Your connection is secure", "اتصال شما امنه")
            }
        }
        ConnectionState.DISCONNECTING -> homeText("Closing the secure tunnel", "در حال بستن اتصال امن")
        ConnectionState.ERROR -> when {
            engineMode.isTor && torStatus.detail.isNotBlank() -> homeText(
                torStatus.detail,
                TorStatusCopy.errorHint(true, torStatus.detail) ?: torStatus.detail,
            )
            engineMode.isPow && powStatus.detail.isNotBlank() -> homeText(
                powStatus.detail,
                PowStatusCopy.errorHint(true, powStatus.detail) ?: powStatus.detail,
            )
            engineMode.isDirect && directStatus.detail.isNotBlank() -> homeText(
                directStatus.detail,
                "اتصال مستقیم راه نیفتاد: ${directStatus.detail}",
            )
            else -> homeText("Tap retry to try again", "دوباره امتحان کن")
        }
        }
    }

    val neonStatus = styleTokens()
    val statusColor = if (neonStatus != null && !neonStatus.statusUseAccent) neonStatus.statusColor else accent
    val showProgressInfo = (state == ConnectionState.CONNECTING || state == ConnectionState.ERROR) &&
        (statusPanel == ConnectPanelMode.CLOSED || (engineMode.isPow && stepsPanel == ConnectPanelMode.CLOSED))
    val reopen = connectPanelText(R.string.connect_panel_reopen)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = status,
            color = statusColor,
            fontSize = neonStatus?.statusSize?.sp ?: 15.sp,
            fontWeight = if (neonStatus != null) FontWeight(neonStatus.statusWeight) else FontWeight.Medium,
            fontFamily = localizedFont,
            textAlign = TextAlign.Center,
            style = TextStyle(
                textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
            ),
            modifier = (if (isPersian) Modifier.widthIn(min = 180.dp) else Modifier)
                .then(if (neonStatus != null) Modifier.graphicsLayer { alpha = neonStatus.statusAlpha } else Modifier),
            maxLines = 1,
            softWrap = false,
        )
        AnimatedVisibility(
            visible = showProgressInfo,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f, animationSpec = tween(200)),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.8f, animationSpec = tween(200)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                ReopenPanelsButton(
                    label = reopen,
                    onClick = {
                        if (statusPanel == ConnectPanelMode.CLOSED) {
                            panelStore.setStatus(ConnectPanelMode.COLLAPSED)
                        }
                        if (stepsPanel == ConnectPanelMode.CLOSED) {
                            panelStore.setSteps(ConnectPanelMode.COLLAPSED)
                        }
                    },
                )
            }
        }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = hint,
            color = neonStatus?.hintColor ?: UacColors.TextSecondary,
            fontSize = neonStatus?.hintSize?.sp ?: 11.5.sp,
            fontWeight = if (neonStatus != null) FontWeight(neonStatus.hintWeight) else FontWeight.Normal,
            fontFamily = localizedFont,
            textAlign = TextAlign.Center,
            style = TextStyle(
                textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
            ),
            modifier = Modifier
                .padding(horizontal = (neonStatus?.hintPad ?: 28f).dp)
                .then(if (neonStatus != null) Modifier.graphicsLayer { alpha = neonStatus.hintAlpha } else Modifier),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReopenPanelsButton(label: String, onClick: () -> Unit) {
    val tokens = styleTokens()
    val start = tokens?.gradientStart ?: UacColors.DisconnectedBlue
    val end = tokens?.gradientEnd ?: UacColors.ConnectingCyan
    val glyph = androidx.compose.ui.graphics.lerp(Color.White, end, 0.32f)
    val density = LocalDensity.current
    val glow = remember(density) {
        val blurPx = with(density) { 10.dp.toPx() }
        val sigma = blurPx * 0.57735f + 0.5f
        BlurEffect(sigma, sigma, TileMode.Decal)
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 20.dp, color = end.copy(alpha = 0.28f)),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .graphicsLayer {
                    renderEffect = glow
                    clip = false
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(end.copy(alpha = 0.25f), CircleShape),
            )
        }
        Box(
            Modifier
                .size(22.dp)
                .background(end.copy(alpha = 0.08f), CircleShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(listOf(start, end)),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "i",
                color = glyph,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.SemiBold,
                fontFamily = homeLocalizedFont(),
            )
        }
    }
}

private const val CONNECTING_ROUTE_HINT_DELAY_MS = 2_000L

@Composable
internal fun PowConnectSheet(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val panelStore = remember(context) { ConnectPanelStore.get(context) }
    val panelMode by panelStore.steps.collectAsStateWithLifecycle()
    val engineMode = rememberDisplayedEngineMode()
    val status by PowStatusStore.status.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val previousConnection = remember { mutableStateOf(connection) }
    LaunchedEffect(connection, engineMode.isPow) {
        val enteredError = connection == ConnectionState.ERROR &&
            previousConnection.value != ConnectionState.ERROR
        previousConnection.value = connection
        if (engineMode.isPow && enteredError) {
            panelStore.setSteps(ConnectPanelMode.OPEN)
        }
    }
    if (!engineMode.isPow) return
    val visible = connection == ConnectionState.CONNECTING || connection == ConnectionState.ERROR
    if (!visible || status.steps.isEmpty() || panelMode == ConnectPanelMode.CLOSED) return
    val persian = LocalHomePersian.current
    val font = homeLocalizedFont()
    val rows = remember(status.steps, persian) { powStepRows(status.steps, persian) }
    val scroll = rememberScrollState()
    LaunchedEffect(rows.size, rows.lastOrNull()) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val shape = RoundedCornerShape(18.dp)
    val paint = connectPanelPaint(primary = true)
    val done = rows.count { it.state == PowStepState.DONE }
    val current = rows.firstOrNull { it.state == PowStepState.RUNNING }
        ?: rows.firstOrNull { it.state == PowStepState.FAILED }
        ?: rows.lastOrNull()
    var dragDown by remember { mutableFloatStateOf(0f) }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Box(
            modifier
                .fillMaxWidth()
                .animateContentSize(tween(250, easing = FastOutSlowInEasing)),
            contentAlignment = Alignment.BottomCenter,
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
            label = "steps-panel-fold",
        ) { expanded ->
        if (!expanded) {
            PowStepsCollapsed(
                modifier = Modifier.fillMaxWidth(),
                row = current,
                done = done,
                total = rows.size,
                paint = paint,
                persian = persian,
                font = font,
                onExpand = { panelStore.setSteps(ConnectPanelMode.OPEN) },
            )
        } else Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(paint.background)
                .border(1.dp, paint.border.copy(alpha = 0.75f), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragDown = 0f },
                            onVerticalDrag = { _, dy -> if (dy > 0f) dragDown += dy },
                            onDragEnd = {
                                if (dragDown > 48.dp.toPx()) panelStore.setSteps(ConnectPanelMode.COLLAPSED)
                                dragDown = 0f
                            },
                            onDragCancel = { dragDown = 0f },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = homeText("Connection steps", "مراحل اتصال"),
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = font,
                    textAlign = TextAlign.Start,
                    style = TextStyle(textDirection = if (persian) TextDirection.Rtl else TextDirection.Ltr),
                )
                ConnectPanelCount(done, rows.size, paint.border)
                Spacer(Modifier.weight(1f))
                ConnectPanelActions(
                    onCollapse = { panelStore.setSteps(ConnectPanelMode.COLLAPSED) },
                    onClose = { panelStore.setSteps(ConnectPanelMode.CLOSED) },
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(UacColors.Divider),
            )
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rows.forEach { row -> PowStepLine(row, persian, font) }
            }
        }
        }
        }
    }
}

@Composable
private fun PowStepsCollapsed(
    modifier: Modifier,
    row: PowStepRow?,
    done: Int,
    total: Int,
    paint: ConnectPanelPaint,
    persian: Boolean,
    font: androidx.compose.ui.text.font.FontFamily?,
    onExpand: () -> Unit,
) {
    val fraction = if (total == 0) 0f else done.toFloat() / total.toFloat()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(paint.background)
            .border(1.dp, paint.border.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onExpand),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (row != null) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when (row.state) {
                        PowStepState.DONE -> Icon(
                            Icons.Rounded.Check, null, tint = UacColors.ConnectedGreen, modifier = Modifier.size(14.dp),
                        )
                        PowStepState.FAILED -> Icon(
                            Icons.Rounded.Close, null, tint = UacColors.ErrorRed, modifier = Modifier.size(14.dp),
                        )
                        PowStepState.RUNNING -> CircularProgressIndicator(
                            color = paint.border,
                            trackColor = paint.border.copy(alpha = 0.15f),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Text(
                text = row?.title.orEmpty(),
                color = UacColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = font,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(textDirection = if (persian) TextDirection.Rtl else TextDirection.Ltr),
                modifier = Modifier.weight(1f),
            )
            ConnectPanelCount(done, total, paint.border)
            Icon(
                Icons.Rounded.ExpandLess,
                connectPanelText(R.string.connect_panel_expand),
                tint = UacColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.08f))) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(paint.border),
            )
        }
    }
}

private data class PowStepCopy(val title: String, val detail: String = "")

private data class PowStepRow(
    val title: String,
    val detail: String,
    val state: PowStepState,
    val repeats: Int = 1,
)

/** Maps raw engine steps to display rows and folds back-to-back repeats into one row. */
private fun powStepRows(steps: List<com.uacspoofer.mobile.engine.pow.PowStep>, persian: Boolean): List<PowStepRow> {
    val rows = mutableListOf<PowStepRow>()
    steps.forEach { step ->
        val copy = stageLabel(step.text, persian)
        val last = rows.lastOrNull()
        if (last != null && last.title == copy.title && last.detail == copy.detail) {
            rows[rows.lastIndex] = last.copy(state = step.state, repeats = last.repeats + 1)
        } else {
            rows += PowStepRow(copy.title, copy.detail, step.state)
        }
    }
    return rows
}

@Composable
private fun PowStepLine(row: PowStepRow, persian: Boolean, font: androidx.compose.ui.text.font.FontFamily?) {
    val titleColor = when (row.state) {
        PowStepState.DONE -> UacColors.TextPrimary.copy(alpha = 0.88f)
        PowStepState.FAILED -> UacColors.TextPrimary.copy(alpha = 0.88f)
        PowStepState.RUNNING -> UacColors.TextPrimary
    }
    val direction = TextStyle(textDirection = if (persian) TextDirection.Rtl else TextDirection.Ltr)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .background(
                    when (row.state) {
                        PowStepState.DONE -> UacColors.ConnectedGreen.copy(alpha = 0.14f)
                        PowStepState.FAILED -> UacColors.ErrorRed.copy(alpha = 0.14f)
                        PowStepState.RUNNING -> Color.Transparent
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (row.state) {
                PowStepState.DONE -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = UacColors.ConnectedGreen,
                    modifier = Modifier.size(13.dp),
                )
                PowStepState.FAILED -> Icon(
                    Icons.Rounded.Close,
                    contentDescription = null,
                    tint = UacColors.ErrorRed,
                    modifier = Modifier.size(13.dp),
                )
                PowStepState.RUNNING -> CircularProgressIndicator(
                    color = UacColors.ConnectingCyan,
                    trackColor = UacColors.ConnectingCyan.copy(alpha = 0.15f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val title = if (row.repeats > 1) {
                row.title + " " + homeLtr("×${row.repeats}")
            } else {
                row.title
            }
            Text(
                text = homeLatinDigits(if (persian) localizePersian(title) else title),
                color = titleColor,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = if (row.state == PowStepState.RUNNING) FontWeight.Medium else FontWeight.Normal,
                fontFamily = font,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                style = direction,
            )
            if (row.detail.isNotBlank()) {
                Text(
                    text = homeLtr(row.detail),
                    color = if (row.state == PowStepState.FAILED) {
                        UacColors.ErrorRed.copy(alpha = 0.8f)
                    } else {
                        UacColors.TextSecondary
                    },
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    style = direction,
                )
            }
        }
    }
}

private fun lastWord(text: String): String = text.trim().substringAfterLast(" ").trimEnd('.')

private fun scanLabel(body: String): PowStepCopy {
    val parts = body.split(' ')
    val address = parts.firstOrNull().orEmpty()
    val reason = when (parts.getOrNull(1)) {
        "trying" -> return PowStepCopy("اسکن آدرس", address)
        "400" -> "این آدرس دروازه MASQUE نیست"
        "access-denied" -> "گواهی دستگاه پذیرفته نشد"
        "certificate" -> "گواهی ناشناخته"
        "timeout" -> "پاسخی نیامد"
        "reset" -> "اتصال وسط دست‌دهی قطع شد"
        "eof" -> "دست‌دهی TLS نیمه‌کاره ماند"
        else -> "آدرس رد شد"
    }
    return PowStepCopy("رد شد: $reason", address)
}

private fun psiphonRoute(label: String): String = when {
    label.startsWith("domain-fronted") -> "Psiphon · CDN"
    label.startsWith("direct through WARP") -> "Psiphon · WARP"
    else -> label
}

private fun stageLabel(text: String, persian: Boolean): PowStepCopy {
    if (!persian) {
        return when {
            text.startsWith("Key account key won via ") || text.startsWith("Key account key saved via ") -> {
                val route = text.substringAfter(" via ").substringBefore(" ").trim().trimEnd(')')
                PowStepCopy("Key saved", route)
            }
            text.startsWith("Key racing ") -> PowStepCopy("Getting key", "ECH · split · current network")
            text.startsWith("MASQUE accepted") -> PowStepCopy("MASQUE connected", lastWord(text))
            text.startsWith("Trying saved clean address") -> PowStepCopy("Trying saved address", lastWord(text))
            text.startsWith("Saved clean address accepted") -> PowStepCopy("Saved address answered", lastWord(text))
            text.startsWith("Saved clean HTTP/2 address") -> PowStepCopy("New clean address saved", lastWord(text))
            text.startsWith("MASQUE start via") -> PowStepCopy(
                "Starting MASQUE over " + text.removePrefix("MASQUE start via ").substringBefore(" with"),
            )
            text.startsWith("Scan ") && !text.startsWith("Scan finished") -> {
                val parts = text.removePrefix("Scan ").split(' ')
                val title = if (parts.getOrNull(1) == "trying") "Scanning address" else "Rejected: ${parts.drop(1).joinToString(" ")}"
                PowStepCopy(title, parts.firstOrNull().orEmpty())
            }
            else -> PowStepCopy(text)
        }
    }
    return when {
        text.startsWith("Starting UAC PoW") -> PowStepCopy("راه‌اندازی UAC PoW")
        text.startsWith("Reconnecting for country") -> PowStepCopy("اتصال دوباره برای کشور انتخاب‌شده", lastWord(text))
        text.startsWith("Connecting MASQUE") -> PowStepCopy("اتصال به MASQUE")
        text.startsWith("Connecting WireGuard") -> PowStepCopy("اتصال به WireGuard")
        text.startsWith("Connecting WoW") -> PowStepCopy("اتصال به WARP-in-WARP")
        text.startsWith("Starting Psiphon directly") -> PowStepCopy("راه‌اندازی تونل امن", "Psiphon")
        text.startsWith("Starting Psiphon through") ->
            PowStepCopy("راه‌اندازی تونل امن", "Psiphon · " + text.removePrefix("Starting Psiphon through ").trim())
        text.startsWith("Starting Psiphon") -> PowStepCopy("راه‌اندازی تونل امن")
        text.startsWith("Trying UDP/1701") -> PowStepCopy("امتحان UDP/1701")
        text.startsWith("Trying saved clean address") -> PowStepCopy("امتحان آی‌پی ذخیره‌شده", lastWord(text))
        text.startsWith("Trying ") -> PowStepCopy("امتحان مسیر بعدی", psiphonRoute(text.removePrefix("Trying ").trim()))
        text.startsWith("Creating device") -> PowStepCopy("ساخت VPN دستگاه")
        text.startsWith("Routing device") -> PowStepCopy("هدایت ترافیک به تونل")
        text.startsWith("UAC PoW ready") -> PowStepCopy("اتصال برقرار شد")
        text.startsWith("Key account key won via ") || text.startsWith("Key account key saved via ") -> {
            val route = text.substringAfter(" via ").substringBefore(" ").trim().trimEnd(')')
            val label = when (route) {
                "ech" -> "ECH"
                "fragment" -> "تکه‌کردن ClientHello"
                "external" -> "از شبکه فعلی"
                else -> route
            }
            PowStepCopy("کلید گرفته شد", label)
        }
        text.startsWith("Key racing ") -> PowStepCopy("گرفتن کلید", "ECH · تکه‌کردن · شبکه فعلی")
        text.startsWith("Key ") || text.startsWith("Key:") -> PowStepCopy(
            "کلید حساب",
            text.removePrefix("Key:").removePrefix("Key ").trim().take(80),
        )
        text.startsWith("Clean HTTP/2 list:") -> PowStepCopy("آی‌پی‌های تمیز HTTP/2", text.substringAfter(":").trim())
        text.startsWith("Clean list empty") -> PowStepCopy("آی‌پی تمیزی نیست؛ شروع اسکن HTTP/2")
        text.startsWith("Every saved address failed. Scan was not started") ->
            PowStepCopy("همه آی‌پی‌های ذخیره‌شده رد شدند (این حالت اسکن ندارد)")
        text.startsWith("Saved addresses failed") -> PowStepCopy("آی‌پی‌های ذخیره‌شده جواب ندادند؛ شروع اسکن HTTP/2")
        text.startsWith("Saved clean address accepted") -> PowStepCopy("آی‌پی ذخیره‌شده جواب داد", lastWord(text))
        text.startsWith("Saved clean address rejected") -> PowStepCopy("آی‌پی ذخیره‌شده جواب نداد")
        text.startsWith("Scanning HTTP/2") -> PowStepCopy("اسکن HTTP/2 برای آی‌پی جدید")
        text.startsWith("Saved clean HTTP/2 address") -> PowStepCopy("آی‌پی تمیز جدید ذخیره شد", lastWord(text))
        text.startsWith("HTTP/2 started") -> PowStepCopy("انتخاب HTTP/2")
        text.startsWith("Scan finished") -> PowStepCopy("اسکن تمام شد؛ همین روش ادامه دارد")
        text.startsWith("Scan ") -> scanLabel(text.removePrefix("Scan "))
        text.startsWith("MASQUE accepted") -> PowStepCopy("اتصال MASQUE برقرار شد", lastWord(text))
        text.startsWith("MASQUE start via") ->
            PowStepCopy("شروع MASQUE روی " + text.removePrefix("MASQUE start via ").substringBefore(" with").trim())
        text.startsWith("MASQUE start") -> PowStepCopy("شروع MASQUE")
        text.startsWith("No usable MASQUE") ->
            PowStepCopy("هیچ دروازه MASQUE جواب نداد", text.substringAfter("(", "").substringBefore(")"))
        text.startsWith("Certificate refused during") || text.startsWith("This certificate was refused") ->
            PowStepCopy("گواهی وسط اسکن رد شد؛ گرفتن گواهی جدید")
        text.startsWith("Certificate refused") -> PowStepCopy("گواهی رد شد؛ گرفتن گواهی جدید")
        text.startsWith("Certificate ") -> PowStepCopy("این گواهی هم رد شد؛ گرفتن گواهی بعدی")
        text.startsWith("New certificate saved") -> PowStepCopy("گواهی جدید ذخیره شد؛ ادامه جستجو")
        text.startsWith("New certificate issued") -> PowStepCopy("گواهی جدید صادر شد؛ امتحان دوباره دروازه‌ها")
        text.startsWith("Could not refresh the MASQUE certificate") -> PowStepCopy("تمدید گواهی MASQUE نشد")
        text.startsWith("Tunnel did not stay up") -> PowStepCopy(
            "تونل روی این آدرس پایدار نماند؛ اسکن دوباره",
            text.removePrefix("Tunnel did not stay up on ").substringBefore(". "),
        )
        text.startsWith("UDP/1701 did not answer, trying TCP") -> PowStepCopy("UDP/1701 جواب نداد؛ امتحان TCP")
        text.startsWith("UDP/1701 did not answer") -> PowStepCopy("UDP/1701 جواب نداد")
        else -> PowStepCopy(PowStatusCopy.errorHint(true, text) ?: text)
    }
}

@Composable
internal fun FeatureCard(accent: Color, compact: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (compact) 15.dp else 17.dp)
    Row(
        modifier = modifier
            .height(if (compact) 86.dp else 94.dp)
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .background(UacColors.Surface.copy(alpha = 0.77f), shape)
            .border(0.75.dp, UacColors.CardBorder, shape)
            .padding(horizontal = 5.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeatureItem(
            Icons.Outlined.VerifiedUser,
            homeText("Secure", "امن"),
            homeText("Encrypted", "رمزگذاری‌شده"),
            accent,
            compact,
            Modifier.weight(1f),
        )
        FeatureDivider()
        FeatureItem(
            Icons.Rounded.Bolt,
            homeText("Fast", "سریع"),
            homeText("Optimized", "بهینه"),
            accent,
            compact,
            Modifier.weight(1f),
        )
        FeatureDivider()
        FeatureItem(
            Icons.Rounded.Wifi,
            homeText("Stable", "پایدار"),
            homeText("Reliable", "قابل‌اعتماد"),
            accent,
            compact,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    compact: Boolean,
    modifier: Modifier,
) {
    val localizedFont = homeLocalizedFont()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(if (compact) 21.dp else 23.dp),
        )
        Spacer(Modifier.height(if (compact) 3.dp else 4.dp))
        Text(
            text = title,
            color = UacColors.TextPrimary,
            fontSize = if (compact) 11.5.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = localizedFont,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = subtitle,
            color = UacColors.TextSecondary,
            fontSize = if (compact) 9.5.sp else 10.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = localizedFont,
        )
    }
}

private fun neonSweep(start: Color, end: Color, center: Offset): Brush =
    Brush.sweepGradient(
        colorStops = arrayOf(
            0.00f to end,
            0.22f to end,
            0.42f to start,
            0.78f to start,
            1.00f to end,
        ),
        center = center,
    )

@Composable
private fun GpuBlurredRing(
    modifier: Modifier,
    blurPx: Float,
    pulse: State<Float>?,
    resting: Float,
    colorAlpha: Float,
    glide: State<Float>? = null,
    glideBreath: State<Float>? = null,
    glideIdle: Float = resting,
    glideSettled: Float = resting,
    content: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    val effect = remember(blurPx) {
        if (blurPx < 1f) {
            null
        } else {
            val sigma = blurPx * 0.57735f + 0.5f
            BlurEffect(sigma, sigma, TileMode.Decal)
        }
    }
    Box(
        modifier.graphicsLayer {
            val gain = if (glide != null) {
                val exit = glide.value
                val breath = if (exit > 0.001f && glideBreath != null) glideBreath.value else 1f
                lerp(glideSettled, glideIdle * breath, exit)
            } else if (pulse != null) {
                pulse.value
            } else {
                resting
            }
            alpha = (colorAlpha * gain / 255f).coerceIn(0f, 1f)
            scaleX = 1f
            scaleY = 1f
            clip = false
            renderEffect = effect
        },
    ) {
        Canvas(Modifier.matchParentSize(), onDraw = content)
    }
}

@Composable
private fun BoxScope.ConnectingComet(
    tokens: NeonTokens,
    diameter: Dp,
    orbit: State<Float>,
    sweep: State<Float>,
    fade: State<Float>,
) {
    val strokes = remember { CometStrokes() }
    Canvas(Modifier.matchParentSize()) {
        val presence = fade.value
        if (presence <= 0.001f) return@Canvas
        val diameterPx = diameter.toPx()
        val gpu = strokes.drawGpuGlows(this, tokens, diameterPx, orbit, sweep, fade)
        if (!gpu) {
            strokes.drawTrailMasked(
                scope = this,
                tokens = tokens,
                diameterPx = diameterPx,
                orbit = orbit,
                sweep = sweep,
                fade = fade,
                blurPx = tokens.connectFarBlur.dp.toPx(),
                layerAlpha = tokens.connectFarAlpha,
            )
            strokes.drawTrailMasked(
                scope = this,
                tokens = tokens,
                diameterPx = diameterPx,
                orbit = orbit,
                sweep = sweep,
                fade = fade,
                blurPx = tokens.connectNearBlur.dp.toPx(),
                layerAlpha = tokens.connectNearAlpha,
            )
        }
        strokes.drawTrail(this, tokens, diameterPx, orbit, sweep, fade, alphaScale = presence)
        strokes.drawHead(this, tokens, diameterPx, orbit, fade)
    }
}

private class CometStrokes {
    private val trailPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isDither = true
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    }
    private val maskPaint = android.graphics.Paint(trailPaint)
    private val addPaint = android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    }
    private val recordPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isDither = true
    }
    private val path = android.graphics.Path()
    private val oval = android.graphics.RectF()
    private val colors = IntArray(7)
    private val stops = FloatArray(7)
    private var sweepShader: android.graphics.SweepGradient? = null
    private var shaderCx = Float.NaN
    private var shaderCy = Float.NaN
    private var shaderSpan = Float.NaN
    private var shaderStart = 0
    private var shaderEnd = 0
    private var bitmap: android.graphics.Bitmap? = null
    private var maskRadius = Float.NaN
    private var maskFilter: android.graphics.BlurMaskFilter? = null
    private var farNode: Any? = null
    private var nearNode: Any? = null
    private var farSigma = Float.NaN
    private var nearSigma = Float.NaN
    private var gpuFailed = false

    fun drawGpuGlows(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
    ): Boolean {
        if (gpuFailed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (fade.value <= 0.001f) return true
        return drawGpuGlows31(scope, tokens, diameterPx, orbit, sweep, fade)
    }

    private fun drawGpuGlows31(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
    ): Boolean {
        return try {
            val placed = place(scope, tokens, diameterPx, orbit, sweep) ?: return true
            val presence = fade.value
            scope.drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                glowNode(
                    native = native,
                    placed = placed,
                    far = true,
                    blurPx = with(scope) { tokens.connectFarBlur.dp.toPx() },
                    layerAlpha = tokens.connectFarAlpha,
                    presence = presence,
                    width = scope.size.width.toInt().coerceAtLeast(1),
                    height = scope.size.height.toInt().coerceAtLeast(1),
                )
                glowNode(
                    native = native,
                    placed = placed,
                    far = false,
                    blurPx = with(scope) { tokens.connectNearBlur.dp.toPx() },
                    layerAlpha = tokens.connectNearAlpha,
                    presence = presence,
                    width = scope.size.width.toInt().coerceAtLeast(1),
                    height = scope.size.height.toInt().coerceAtLeast(1),
                )
            }
            true
        } catch (error: Throwable) {
            gpuFailed = true
            false
        }
    }

    private fun glowNode(
        native: android.graphics.Canvas,
        placed: PlacedTrail,
        far: Boolean,
        blurPx: Float,
        layerAlpha: Float,
        presence: Float,
        width: Int,
        height: Int,
    ) {
        if (blurPx < 1f || layerAlpha <= 0.001f || width < 2 || height < 2) return
        val sigma = blurPx * 0.57735f + 0.5f
        val node = renderNode(far)
        node.setPosition(0, 0, width, height)
        node.setClipToBounds(false)
        val cached = if (far) farSigma else nearSigma
        if (cached != sigma) {
            node.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    sigma,
                    sigma,
                    android.graphics.Shader.TileMode.DECAL,
                ),
            )
            if (far) farSigma = sigma else nearSigma = sigma
        }
        recordPaint.shader = placed.shader
        recordPaint.strokeWidth = placed.stroke
        recordPaint.alpha = (layerAlpha.coerceIn(0f, 1f) * presence * 255f).toInt().coerceIn(0, 255)
        val recording = node.beginRecording()
        recording.save()
        recording.rotate(placed.spin, placed.cx, placed.cy)
        recording.drawPath(path, recordPaint)
        recording.restore()
        node.endRecording()
        val checkpoint = native.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), addPaint)
        native.drawRenderNode(node)
        native.restoreToCount(checkpoint)
    }

    private fun renderNode(far: Boolean): android.graphics.RenderNode {
        val current = (if (far) farNode else nearNode) as? android.graphics.RenderNode
        if (current != null) return current
        val created = android.graphics.RenderNode(if (far) "uac-comet-far" else "uac-comet-near")
        created.setClipToBounds(false)
        if (far) farNode = created else nearNode = created
        return created
    }

    fun drawTrail(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
        alphaScale: Float,
    ) {
        if (fade.value <= 0.001f || alphaScale <= 0.001f) return
        val placed = place(scope, tokens, diameterPx, orbit, sweep) ?: return
        trailPaint.shader = placed.shader
        trailPaint.strokeWidth = placed.stroke
        trailPaint.alpha = (alphaScale.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        scope.rotate(placed.spin, Offset(placed.cx, placed.cy)) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPath(path, trailPaint)
            }
        }
    }

    fun drawTrailMasked(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
        blurPx: Float,
        layerAlpha: Float,
    ) {
        val presence = fade.value
        if (presence <= 0.001f || layerAlpha <= 0.001f || blurPx < 1f) return
        val placed = place(scope, tokens, diameterPx, orbit, sweep) ?: return
        val width = scope.size.width.toInt().coerceAtLeast(1)
        val height = scope.size.height.toInt().coerceAtLeast(1)
        val buffer = bitmap?.takeIf { it.width == width && it.height == height } ?: android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        ).also {
            bitmap?.recycle()
            bitmap = it
        }
        if (maskRadius != blurPx) {
            maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
            maskRadius = blurPx
        }
        buffer.eraseColor(android.graphics.Color.TRANSPARENT)
        maskPaint.shader = placed.shader
        maskPaint.strokeWidth = placed.stroke
        maskPaint.maskFilter = maskFilter
        maskPaint.xfermode = null
        maskPaint.alpha = (layerAlpha.coerceIn(0f, 1f) * presence * 255f).toInt().coerceIn(0, 255)
        val software = android.graphics.Canvas(buffer)
        software.save()
        software.rotate(placed.spin, placed.cx, placed.cy)
        software.drawPath(path, maskPaint)
        software.restore()
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(buffer, 0f, 0f, addPaint)
        }
    }

    fun drawHead(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        fade: State<Float>,
    ) {
        val presence = fade.value
        if (presence <= 0.001f) return
        val orbitDeg = orbit.value
        val stroke = with(scope) { tokens.ringWidth.dp.toPx() }
        val radius = diameterPx / 2f + stroke / 2f
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val theta = Math.toRadians(orbitDeg.toDouble())
        val hx = cx + radius * cos(theta).toFloat()
        val hy = cy + radius * sin(theta).toFloat()
        val haloPx = with(scope) { 11.dp.toPx() }
        val headPx = with(scope) { tokens.connectHeadRadius.dp.toPx() }
        val head = Offset(hx, hy)
        scope.drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to tokens.gradientEnd.copy(alpha = 0.55f * presence),
                    1f to Color.Transparent,
                ),
                center = head,
                radius = haloPx,
            ),
            radius = haloPx,
            center = head,
            blendMode = BlendMode.Plus,
        )
        scope.drawCircle(
            color = Color(0xFFEBFFFF).copy(alpha = presence),
            radius = headPx,
            center = head,
        )
    }

    private fun place(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
    ): PlacedTrail? {
        val stroke = with(scope) { tokens.ringWidth.dp.toPx() }
        if (stroke < 0.5f || diameterPx < 1f) return null
        val span = sweep.value.coerceIn(8f, 350f)
        val orbitDeg = orbit.value
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val radius = diameterPx / 2f + stroke / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val head = 180f + span / 2f
        path.rewind()
        path.addArc(oval, head - span, span)
        val shader = sweepShader(cx, cy, span, tokens.gradientStart, tokens.gradientEnd)
        return PlacedTrail(shader, stroke, orbitDeg - head, cx, cy)
    }

    private fun sweepShader(
        cx: Float,
        cy: Float,
        span: Float,
        gradientStart: Color,
        gradientEnd: Color,
    ): android.graphics.SweepGradient {
        val head = 180f + span / 2f
        val tail = 180f - span / 2f
        val at60 = head - span * 0.60f
        val at15 = head - span * 0.15f
        val after = (head + 1.15f).coerceAtMost(359.3f)
        stops[0] = 0f
        stops[1] = tail / 360f
        stops[2] = at60 / 360f
        stops[3] = at15 / 360f
        stops[4] = head / 360f
        stops[5] = after / 360f
        stops[6] = 1f
        for (index in 1 until stops.lastIndex) {
            if (stops[index] <= stops[index - 1]) {
                stops[index] = (stops[index - 1] + 0.0008f).coerceAtMost(0.998f)
            }
        }
        val startColor = colorWithAlpha(gradientStart, 0.5f)
        val endColor = colorWithAlpha(gradientEnd, 1f)
        colors[0] = 0
        colors[1] = 0
        colors[2] = startColor
        colors[3] = endColor
        colors[4] = 0xFFEBFFFF.toInt()
        colors[5] = 0
        colors[6] = 0
        val moved = sweepShader == null ||
            shaderCx != cx ||
            shaderCy != cy ||
            shaderStart != startColor ||
            shaderEnd != endColor ||
            kotlin.math.abs(shaderSpan - span) > 0.2f
        if (moved) {
            sweepShader = android.graphics.SweepGradient(cx, cy, colors, stops)
            shaderCx = cx
            shaderCy = cy
            shaderStart = startColor
            shaderEnd = endColor
            shaderSpan = span
        }
        return sweepShader!!
    }

    private class PlacedTrail(
        val shader: android.graphics.SweepGradient,
        val stroke: Float,
        val spin: Float,
        val cx: Float,
        val cy: Float,
    )
}

private fun colorWithAlpha(color: Color, alpha: Float): Int {
    val rgb = color.toArgb() and 0x00FFFFFF
    val channel = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    return (channel shl 24) or rgb
}

private class NeonGlowBuffer {
    var bitmap: android.graphics.Bitmap? = null
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var maskBlur = Float.NaN
    private var mask: android.graphics.BlurMaskFilter? = null
    private var shaderCx = Float.NaN
    private var shaderCy = Float.NaN
    private var shaderStart = 0
    private var shaderEnd = 0
    private var keyWidth = -1
    private var keyHeight = -1
    private var keyRadius = Float.NaN
    private var keyStroke = Float.NaN
    private var keyBlur = Float.NaN
    private var keyAlpha = -1

    fun matches(
        width: Int,
        height: Int,
        center: Offset,
        radius: Float,
        stroke: Float,
        blur: Float,
        alpha: Int,
        start: Int,
        end: Int,
    ): Boolean = bitmap != null &&
        keyWidth == width &&
        keyHeight == height &&
        shaderCx == center.x &&
        shaderCy == center.y &&
        keyRadius == radius &&
        keyStroke == stroke &&
        keyBlur == blur &&
        keyAlpha == alpha &&
        shaderStart == start &&
        shaderEnd == end

    fun prepare(
        width: Int,
        height: Int,
        center: Offset,
        radius: Float,
        stroke: Float,
        blur: Float,
        alpha: Int,
        start: Int,
        end: Int,
    ): android.graphics.Bitmap {
        var current = this.bitmap
        if (current == null || current.width != width || current.height != height) {
            current?.recycle()
            current = android.graphics.Bitmap.createBitmap(
                width,
                height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            this.bitmap = current
        }
        if (shaderCx != center.x || shaderCy != center.y || shaderStart != start || shaderEnd != end) {
            paint.shader = android.graphics.SweepGradient(
                center.x,
                center.y,
                intArrayOf(end, end, start, start, end),
                floatArrayOf(0f, 0.22f, 0.42f, 0.78f, 1f),
            )
            shaderCx = center.x
            shaderCy = center.y
            shaderStart = start
            shaderEnd = end
        }
        if (blur >= 1f) {
            if (maskBlur != blur) {
                mask = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                maskBlur = blur
            }
            paint.maskFilter = mask
        } else {
            paint.maskFilter = null
            mask = null
            maskBlur = Float.NaN
        }
        paint.strokeWidth = stroke
        paint.alpha = alpha
        current.eraseColor(android.graphics.Color.TRANSPARENT)
        android.graphics.Canvas(current).drawCircle(center.x, center.y, radius, paint)
        keyWidth = width
        keyHeight = height
        keyRadius = radius
        keyStroke = stroke
        keyBlur = blur
        keyAlpha = alpha
        return current
    }
}

private class SweepRingBrushes {
    private var cx = Float.NaN
    private var cy = Float.NaN
    private var start = 0
    private var end = 0
    private val brushes = HashMap<Float, Brush>(8)

    fun sweep(alpha: Float, center: Offset, gradientStart: Color, gradientEnd: Color): Brush {
        val startArgb = gradientStart.toArgb()
        val endArgb = gradientEnd.toArgb()
        if (cx != center.x || cy != center.y || start != startArgb || end != endArgb) {
            brushes.clear()
            cx = center.x
            cy = center.y
            start = startArgb
            end = endArgb
        }
        if (brushes.size > 24) brushes.clear()
        return brushes.getOrPut(alpha) {
            val startColor = gradientStart.copy(alpha = gradientStart.alpha * alpha)
            val endColor = gradientEnd.copy(alpha = gradientEnd.alpha * alpha)
            Brush.sweepGradient(
                colorStops = arrayOf(
                    0.00f to endColor,
                    0.22f to endColor,
                    0.42f to startColor,
                    0.78f to startColor,
                    1.00f to endColor,
                ),
                center = center,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlurredNeonRing(
    center: Offset,
    radius: Float,
    stroke: Float,
    blur: Float,
    alpha: Int,
    buffer: NeonGlowBuffer,
    gradientStart: Color,
    gradientEnd: Color,
) {
    val width = size.width.toInt().coerceAtLeast(1)
    val height = size.height.toInt().coerceAtLeast(1)
    val start = gradientStart.toArgb()
    val end = gradientEnd.toArgb()
    val bitmap = if (
        buffer.matches(width, height, center, radius, stroke, blur, alpha, start, end)
    ) {
        buffer.bitmap!!
    } else {
        buffer.prepare(width, height, center, radius, stroke, blur, alpha, start, end)
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}

@Composable
private fun FeatureDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight(0.68f)
            .width(1.dp)
            .background(UacColors.Divider),
    )
}
