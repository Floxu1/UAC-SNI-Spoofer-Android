package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.Engine4UnlockStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore

internal val LocalDisplayedEngineMode = compositionLocalOf<EngineMode?> { null }

@Composable
internal fun rememberDisplayedEngineMode(): EngineMode {
    val context = LocalContext.current.applicationContext
    val engineStore = remember(context) { EngineModeStore.get(context) }
    val stored by engineStore.mode.collectAsStateWithLifecycle()
    return LocalDisplayedEngineMode.current ?: stored
}

internal fun engineSwitchTransition(): ContentTransform =
    fadeIn(tween(260, easing = FastOutSlowInEasing))
        .togetherWith(fadeOut(tween(180, easing = FastOutLinearInEasing)))

internal fun EngineMode.identityAccent(): Color = when (this) {
    EngineMode.XRAY_CF -> Color(0xFF7EE4FF)
    EngineMode.TOR_WEBTUNNEL -> Color(0xFFE0C4FF)
    EngineMode.UAC_POW -> Color(0xFF6FF6D0)
    EngineMode.UAC_DIRECT -> Color(0xFFFFC46B)
}

internal fun EngineMode.toHomeRemoteSlot(): HomeRemoteSlot = when (this) {
    EngineMode.XRAY_CF -> HomeRemoteSlot.EngineXray
    EngineMode.TOR_WEBTUNNEL -> HomeRemoteSlot.EngineTor
    EngineMode.UAC_POW -> HomeRemoteSlot.EnginePow
    EngineMode.UAC_DIRECT -> HomeRemoteSlot.EngineDirect
}

@Composable
internal fun EngineMode.switchContentDescription(): String = when (this) {
    EngineMode.XRAY_CF -> homeText("Cloudflare SNI engine", "موتور کلودفلر")
    EngineMode.TOR_WEBTUNNEL -> homeText("Tor engine", "موتور تور")
    EngineMode.UAC_POW -> homeText("PoW engine", "موتور PoW")
    EngineMode.UAC_DIRECT -> homeText("Direct engine (serverless)", "موتور Direct (بدون سرور)")
}

private fun EngineMode.identityIcon(): ImageVector = when (this) {
    EngineMode.XRAY_CF -> XrayBoltIcon
    EngineMode.TOR_WEBTUNNEL -> TorOnionIcon
    EngineMode.UAC_POW -> PowHexIcon
    EngineMode.UAC_DIRECT -> DirectArrowIcon
}

private fun EngineMode.shortName(): String = when (this) {
    EngineMode.XRAY_CF -> "Xray"
    EngineMode.TOR_WEBTUNNEL -> "Tor"
    EngineMode.UAC_POW -> "PoW"
    EngineMode.UAC_DIRECT -> "Direct"
}

/**
 * Glassy vertical segmented control. A spring-driven indicator slides behind the active
 * engine, which grows to show icon + name; the others show only their icon.
 */
@Composable
internal fun EngineSwitchRail(
    selected: EngineMode,
    pending: EngineMode?,
    compact: Boolean,
    enabled: Boolean,
    onSelect: (EngineMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val unlockStore = remember(context) { Engine4UnlockStore.get(context) }
    val engine4Unlocked by unlockStore.unlocked.collectAsStateWithLifecycle()
    val active = pending ?: selected
    val visibleModes = remember(engine4Unlocked, active) {
        val base = EngineMode.visible(engine4Unlocked)
        if (active.isDirect && active !in base) base + active else base
    }
    val width: Dp = if (compact) 42.dp else 46.dp
    val small: Dp = if (compact) 34.dp else 38.dp
    val big: Dp = if (compact) 50.dp else 56.dp
    val gap = 4.dp
    val pad = 4.dp
    val activeIndex = visibleModes.indexOf(active).coerceAtLeast(0)
    val spring = spring<Dp>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    val indicatorY by animateDpAsState((small + gap) * activeIndex, spring, label = "engine-indicator-y")
    val accent by animateColorAsState(active.identityAccent(), tween(320), label = "engine-indicator-color")
    val railShape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .shadow(16.dp, railShape, ambientColor = accent.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(railShape)
            .background(Brush.verticalGradient(listOf(Color(0xE6182433), Color(0xEB0B121B))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), accent.copy(alpha = 0.18f), Color.White.copy(alpha = 0.05f))),
                railShape,
            )
            .padding(pad),
    ) {
        // sliding indicator
        Box(
            modifier = Modifier
                .offset(y = indicatorY)
                .size(width - pad * 2, big)
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.38f),
                            radius = size.maxDimension * 0.75f,
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f),
                    )
                }
                .clip(RoundedCornerShape(percent = 50))
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.12f))))
                .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(percent = 50)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
            visibleModes.forEach { mode ->
                EngineSwitchItem(
                    mode = mode,
                    selected = active == mode,
                    spinning = pending == mode,
                    enabled = enabled,
                    width = width - pad * 2,
                    height = if (active == mode) big else small,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun EngineSwitchItem(
    mode: EngineMode,
    selected: Boolean,
    spinning: Boolean,
    enabled: Boolean,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    val identity = mode.identityAccent()
    val h by animateDpAsState(height, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "engine-item-h")
    val tint by animateColorAsState(if (selected) Color.White else identity.copy(alpha = 0.55f), tween(260), label = "engine-item-tint")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "engine-item-press",
    )
    val spin = rememberInfiniteTransition(label = "engine-item-spin")
    val spinDeg by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart), label = "engine-item-spin-deg")
    val description = mode.switchContentDescription()
    Column(
        modifier = Modifier
            .size(width, h)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(percent = 50))
            .trackHomeSlot(mode.toHomeRemoteSlot())
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .keyboardFocusRing()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crossfade(targetState = selected, animationSpec = tween(260), label = "engine-item-icon") { sel ->
            Icon(
                imageVector = mode.identityIcon(),
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(if (sel) 22.dp else 20.dp)
                    .graphicsLayer { rotationZ = if (spinning) spinDeg else 0f },
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(),
            exit = fadeOut(tween(120)) + shrinkVertically(),
        ) {
            Text(
                text = mode.shortName(),
                color = Color.White,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun strokeIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit, vararg more: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        (listOf(block) + more).forEach { b ->
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = b,
            )
        }
    }.build()

private val XrayBoltIcon: ImageVector by lazy {
    strokeIcon("Engine.XrayBolt", {
        moveTo(13.2f, 2.5f); lineTo(5f, 13.4f); lineTo(11.2f, 13.4f); lineTo(10.6f, 21.5f); lineTo(19f, 10.4f); lineTo(12.8f, 10.4f); close()
    }, {
        moveTo(3f, 6.5f); lineTo(5.2f, 7.4f)
    }, {
        moveTo(21f, 17.5f); lineTo(18.8f, 16.6f)
    })
}

private val TorOnionIcon: ImageVector by lazy {
    strokeIcon("Engine.TorOnion", {
        moveTo(12f, 6.2f)
        curveTo(17f, 7.6f, 19.6f, 11f, 19.6f, 14.5f)
        curveTo(19.6f, 18.6f, 16.2f, 21.5f, 12f, 21.5f)
        curveTo(7.8f, 21.5f, 4.4f, 18.6f, 4.4f, 14.5f)
        curveTo(4.4f, 11f, 7f, 7.6f, 12f, 6.2f)
        close()
    }, {
        moveTo(12f, 9.4f)
        curveTo(14.6f, 10.7f, 15.9f, 12.6f, 15.9f, 14.7f)
        curveTo(15.9f, 16.9f, 14.2f, 18.4f, 12f, 18.4f)
        curveTo(9.8f, 18.4f, 8.1f, 16.9f, 8.1f, 14.7f)
        curveTo(8.1f, 12.6f, 9.4f, 10.7f, 12f, 9.4f)
        close()
    }, {
        moveTo(12f, 12.6f); lineTo(12f, 15.4f)
    }, {
        moveTo(12f, 6.2f); curveTo(12f, 4.5f, 12.9f, 3.1f, 14.8f, 2.5f)
    })
}

private val PowHexIcon: ImageVector by lazy {
    strokeIcon("Engine.PowHex", {
        moveTo(12f, 2.5f); lineTo(20.2f, 7.2f); lineTo(20.2f, 16.8f); lineTo(12f, 21.5f); lineTo(3.8f, 16.8f); lineTo(3.8f, 7.2f); close()
    }, {
        moveTo(8.6f, 12.2f); lineTo(11f, 14.6f); lineTo(15.6f, 9.8f)
    })
}

private val DirectArrowIcon: ImageVector by lazy {
    strokeIcon("Engine.DirectArrow", {
        moveTo(4f, 12f); lineTo(19f, 12f)
    }, {
        moveTo(14f, 7f); lineTo(19f, 12f); lineTo(14f, 17f)
    }, {
        moveTo(7f, 8.5f); lineTo(9.5f, 8.5f)
    }, {
        moveTo(7f, 15.5f); lineTo(9.5f, 15.5f)
    })
}
