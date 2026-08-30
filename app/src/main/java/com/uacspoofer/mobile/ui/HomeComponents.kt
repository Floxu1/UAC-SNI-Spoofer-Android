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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeChangeResult
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.canChangeEngineMode
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
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
    var spinNonce by remember { mutableStateOf(0) }
    val iconSize = if (compact) 22.dp else 24.dp
    val buttonSize = if (compact) 38.dp else 42.dp
    val switchSize = if (compact) 42.dp else 46.dp
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

    Row(
        modifier = modifier.height(if (compact) 44.dp else 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteIconButton(
            onClick = onMenuClick,
            modifier = Modifier
                .size(buttonSize)
                .focusProperties { canFocus = !drawerOpen }
                .trackHomeSlot(HomeRemoteSlot.Menu)
                .openDrawerOnDpadLeft(onMenuClick),
        ) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = "Open navigation menu",
                tint = UacColors.TextPrimary,
                modifier = Modifier.size(iconSize),
            )
        }
        if (engineToggleEnabled) {
            Box(
                modifier = Modifier
                    .size(switchSize)
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
                    )
                    .trackHomeSlot(HomeRemoteSlot.Engine)
                    .clickable(
                        enabled = pendingEngine == null,
                        role = Role.Button,
                        onClick = {
                            if (pendingEngine != null) return@clickable
                            spinNonce += 1
                            val target = engineMode.toggled()
                            if (canChangeEngineMode(ConnectionStateStore.state.value)) {
                                engineStore.setMode(target)
                            } else {
                                pendingEngine = target
                            }
                        },
                    )
                    .semantics {
                        contentDescription = if (engineMode.isTor) {
                            "Switch to UAC SNI Spoofer"
                        } else {
                            "Switch to UAC TOR BRIDGE"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                EngineSwitchGlyph(
                    accent = accent,
                    spinning = pendingEngine != null,
                    spinNonce = spinNonce,
                    compact = compact,
                    modifier = Modifier.size(switchSize),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.VerifiedUser,
                contentDescription = "Connection status",
                tint = accent,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
internal fun AppTitle(compact: Boolean, accent: Color) {
    val engineMode = rememberDisplayedEngineMode()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (engineMode.isTor) "UAC TOR BRIDGE" else "UAC SNI SPOOFER",
            fontSize = if (compact) 20.sp else 23.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.55.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(
                brush = Brush.horizontalGradient(
                    0f to accent,
                    0.30f to Color(0xFFDFF8FF),
                    0.58f to Color.White,
                    0.82f to Color(0xFFB8DFFF),
                    1f to accent,
                ),
                shadow = Shadow(
                    color = accent.copy(alpha = 0.62f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 16f,
                ),
            ),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(if (compact) 132.dp else 154.dp)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.95f), Color.White, accent.copy(alpha = 0.95f), Color.Transparent),
                    ),
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
    val animatedGlow by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-glow-intensity",
    )
    val loadingRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connect-loading-rotation",
    )
    val loadingSweep by transition.animateFloat(
        initialValue = 58f,
        targetValue = 292f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 880, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connect-loading-sweep",
    )
    val glowIntensity = if (state == ConnectionState.CONNECTING) animatedGlow else 1f
    val emphasizePersianLabel = isPersian
    val buttonLabel = when (state) {
        ConnectionState.DISCONNECTED -> homeText("CONNECT", "اتصال")
        ConnectionState.CONNECTING -> homeText("CANCEL", "لغو")
        ConnectionState.CONNECTED -> homeText("DISCONNECT", "قطع اتصال")
        ConnectionState.DISCONNECTING -> homeText("DISCONNECTING...", "در حال قطع...")
        ConnectionState.ERROR -> homeText("RETRY", "تلاش دوباره")
    }

    Box(
        modifier = Modifier
            .size(diameter + halo)
            .trackHomeSlot(HomeRemoteSlot.Connect)
            .clickable(enabled = !interactionDisabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val surfaceRadius = diameter.toPx() / 2f
            val atmosphericRadius = size.minDimension / 2f
            val haloPx = halo.toPx()

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
                color = accent.copy(alpha = 0.075f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.50f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = accent.copy(alpha = 0.13f * glowIntensity),
                radius = surfaceRadius + haloPx * 0.33f,
                center = center,
                style = Stroke(width = 1.4.dp.toPx()),
            )
            drawCircle(
                color = accent.copy(alpha = 0.29f * glowIntensity),
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
            if (state == ConnectionState.CONNECTING) {
                val progressRadius = surfaceRadius + haloPx * 0.15f
                val progressTopLeft = Offset(center.x - progressRadius, center.y - progressRadius)
                val progressSize = Size(progressRadius * 2f, progressRadius * 2f)
                drawArc(
                    color = accent.copy(alpha = 0.09f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 11.dp.toPx()),
                )
                drawArc(
                    color = accent.copy(alpha = 0.18f * animatedGlow),
                    startAngle = loadingRotation,
                    sweepAngle = loadingSweep,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0f to accent.copy(alpha = 0.15f),
                            0.55f to accent,
                            0.82f to Color.White,
                            1f to accent.copy(alpha = 0.20f),
                        ),
                        center = center,
                    ),
                    startAngle = loadingRotation,
                    sweepAngle = loadingSweep,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 4.6.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.96f),
                    startAngle = loadingRotation + loadingSweep - 7f,
                    sweepAngle = 7f,
                    useCenter = false,
                    topLeft = progressTopLeft,
                    size = progressSize,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(UacColors.ButtonCenter, UacColors.ButtonEdge),
                    ),
                    shape = CircleShape,
                )
                .semantics { contentDescription = buttonLabel },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 2.dp.toPx()
                drawCircle(
                    color = accent.copy(alpha = 0.34f * glowIntensity),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 10.dp.toPx()),
                )
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            accent,
                            Color.White.copy(alpha = 0.90f),
                            accent,
                            accent.copy(alpha = 0.76f),
                            accent,
                        ),
                        center = center,
                    ),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 3.2.dp.toPx()),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.34f),
                    startAngle = 208f,
                    sweepAngle = 104f,
                    useCenter = false,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 4.dp.toPx(),
                        size.height - 4.dp.toPx(),
                    ),
                    style = Stroke(width = 0.9.dp.toPx()),
                )
                drawCircle(
                    color = UacColors.ButtonInnerRing,
                    radius = outerRadius - 6.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.1.dp.toPx()),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(diameter * 0.21f),
                )
                Spacer(Modifier.height(if (diameter < 150.dp) 4.dp else 8.dp))
                Text(
                    text = buttonLabel,
                    color = accent,
                    fontSize = when {
                        emphasizePersianLabel && diameter < 150.dp -> 14.sp
                        emphasizePersianLabel -> 18.sp
                        buttonLabel.length > 11 -> 10.5.sp
                        else -> 13.sp
                    },
                    fontWeight = if (emphasizePersianLabel) FontWeight.Bold else FontWeight.SemiBold,
                    fontFamily = localizedFont,
                    letterSpacing = if (isPersian) 0.sp else 0.55.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                        shadow = if (emphasizePersianLabel) {
                            Shadow(color = accent.copy(alpha = 0.42f), offset = Offset.Zero, blurRadius = 9f)
                        } else {
                            null
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
    val routeProgress by ConnectionStateStore.routeProgress.collectAsStateWithLifecycle()
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
    val status = when (state) {
        ConnectionState.DISCONNECTED -> homeText("Disconnected", "وصل نیست")
        ConnectionState.CONNECTING -> homeText("Connecting...", "در حال اتصال…")
        ConnectionState.CONNECTED -> homeText("Connected", "وصل شد")
        ConnectionState.DISCONNECTING -> homeText("Disconnecting...", "در حال قطع...")
        ConnectionState.ERROR -> homeText("Connection failed", "اتصال برقرار نشد")
    }
    val connectingHint = if (engineMode.isTor) {
        homeText(
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
    } else {
        when {
            showRouteProgress && routeProgress.isActive -> homeText(
                "Connecting with route ${routeProgress.current}/${routeProgress.total}",
                "اتصال با مسیر ${routeProgress.current}/${routeProgress.total}",
            )
            else -> homeText("Establishing a secure tunnel", "در حال ساخت اتصال امن")
        }
    }
    val hint = when (state) {
        ConnectionState.DISCONNECTED -> homeText("Tap the button to connect", "برای وصل شدن، دکمه رو بزن")
        ConnectionState.CONNECTING -> connectingHint
        ConnectionState.CONNECTED -> if (engineMode.isTor) {
            if (advanced.connectionMode == CONNECTION_MODE_PROXY) {
                homeText("Local Tor SOCKS only · no device VPN", "فقط SOCKS محلی Tor · بدون VPN دستگاه")
            } else {
                homeText("Device VPN is routing through Tor", "VPN دستگاه از Tor می‌گذره")
            }
        } else {
            homeText("Your connection is secure", "اتصال شما امنه")
        }
        ConnectionState.DISCONNECTING -> homeText("Closing the secure tunnel", "در حال بستن اتصال امن")
        ConnectionState.ERROR -> if (engineMode.isTor && torStatus.detail.isNotBlank()) {
            homeText(
                torStatus.detail,
                TorStatusCopy.errorHint(true, torStatus.detail) ?: torStatus.detail,
            )
        } else {
            homeText("Tap retry to try again", "دوباره امتحان کن")
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = status,
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = localizedFont,
            textAlign = TextAlign.Center,
            style = TextStyle(
                textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
            ),
            modifier = if (isPersian) Modifier.widthIn(min = 180.dp) else Modifier,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = hint,
            color = UacColors.TextSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = localizedFont,
            textAlign = TextAlign.Center,
            style = TextStyle(
                textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
            ),
            modifier = Modifier.padding(horizontal = 28.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val CONNECTING_ROUTE_HINT_DELAY_MS = 2_000L

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

@Composable
private fun FeatureDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight(0.68f)
            .width(1.dp)
            .background(UacColors.Divider),
    )
}
