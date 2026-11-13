package com.uacspoofer.mobile.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.uacspoofer.mobile.ui.theme.styleTokens
import com.uacspoofer.mobile.ui.theme.neonDp
import com.uacspoofer.mobile.ui.theme.neonSp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val ringBrushes = remember { SweepRingBrushes() }
    val emphasizePersianLabel = isPersian
    val buttonLabel = when (state) {
        ConnectionState.DISCONNECTED -> homeText("CONNECT", "اتصال")
        ConnectionState.CONNECTING -> homeText("CANCEL", "لغو")
        ConnectionState.CONNECTED -> homeText("DISCONNECT", "قطع اتصال")
        ConnectionState.DISCONNECTING -> homeText("DISCONNECTING...", "در حال قطع...")
        ConnectionState.ERROR -> homeText("RETRY", "تلاش دوباره")
    }

    val neonTokens = styleTokens()
    val outerGlow = remember { NeonGlowBuffer() }
    val innerGlow = remember { NeonGlowBuffer() }
    val buttonAlpha = neonTokens?.buttonAlpha ?: 1f
    val neonGlowBox = neonTokens?.let { tuned ->
        val blurDp = maxOf(tuned.glowBlur, tuned.glowBlurConnected, 0f)
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
        Canvas(modifier = Modifier.matchParentSize()) {
            val glowIntensity = if (state == ConnectionState.CONNECTING) animatedGlow.value else 1f
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
            val neonGain = when (state) {
                ConnectionState.CONNECTED -> 1f
                ConnectionState.CONNECTING -> glowIntensity
                else -> neonTokens?.glowDisconnected ?: 0.55f
            }

            if (neon && neonTokens != null) {
                val tuned = neonTokens
                val tube = tuned.ringWidth.dp.toPx()
                val tubeRadius = surfaceRadius + tube / 2f
                rotate(tuned.gradientAngle, center) {
                drawBlurredNeonRing(
                    center = center,
                        radius = tubeRadius + tuned.glowSpread.dp.toPx(),
                    stroke = tube,
                        blur = (if (state == ConnectionState.CONNECTED) tuned.glowBlurConnected else tuned.glowBlur).dp.toPx(),
                        alpha = (tuned.glowAlpha * neonGain).toInt().coerceIn(0, 255),
                    buffer = outerGlow,
                        gradientStart = tuned.gradientStart,
                        gradientEnd = tuned.gradientEnd,
                )
                drawCircle(
                    brush = ring(1f),
                    radius = tubeRadius,
                    center = center,
                    style = Stroke(width = tube),
                )
                drawCircle(
                        color = tuned.ringCore.copy(alpha = tuned.ringCore.alpha * tuned.ringCoreAlpha),
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
                            brush = ring(hairAlphas[index] * neonGain),
                            radius = tubeRadius + gap,
                            center = center,
                            style = Stroke(width = tuned.hairlineWidth.dp.toPx()),
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
            } else {
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
            Canvas(modifier = Modifier.matchParentSize()) {
                val glowIntensity = if (state == ConnectionState.CONNECTING) animatedGlow.value else 1f
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 2.dp.toPx()
                val neonDisc = com.uacspoofer.mobile.ui.theme.UacThemeState.isNeon
                if (neonDisc) {
                    val tuned = neonTokens
                    if (tuned != null) {
                    val inward = when (state) {
                        ConnectionState.CONNECTED -> 1f
                        ConnectionState.CONNECTING -> glowIntensity
                            else -> tuned.glowDisconnected
                    }
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
                    modifier = Modifier.size(diameter * (neonTokens?.iconScale ?: 0.21f)),
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
    }
}

@Composable
internal fun ConnectionStatus(state: ConnectionState, accent: Color) {
    val isPersian = LocalHomePersian.current
    val localizedFont = homeLocalizedFont()
    val context = LocalContext.current
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

private const val CONNECTING_ROUTE_HINT_DELAY_MS = 2_000L

@Composable
internal fun PowConnectSheet(modifier: Modifier = Modifier) {
    val engineMode = rememberDisplayedEngineMode()
    val status by PowStatusStore.status.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    if (!engineMode.isPow) return
    val visible = connection == ConnectionState.CONNECTING || connection == ConnectionState.ERROR
    if (!visible || status.steps.isEmpty()) return
    val persian = LocalHomePersian.current
    val font = homeLocalizedFont()
    val rows = remember(status.steps, persian) { powStepRows(status.steps, persian) }
    val scroll = rememberScrollState()
    LaunchedEffect(rows.size, rows.lastOrNull()) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val shape = RoundedCornerShape(18.dp)
    CompositionLocalProvider(
        LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(UacColors.Surface.copy(alpha = 0.96f))
                .border(1.dp, UacColors.CardBorder, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = homeText("Connection steps", "مراحل اتصال"),
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = font,
                    textAlign = TextAlign.Start,
                    style = TextStyle(textDirection = if (persian) TextDirection.Rtl else TextDirection.Ltr),
                    modifier = Modifier.weight(1f),
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
