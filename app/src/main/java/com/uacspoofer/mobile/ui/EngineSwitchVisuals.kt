package com.uacspoofer.mobile.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Hexagon
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
}

internal fun EngineMode.toHomeRemoteSlot(): HomeRemoteSlot = when (this) {
    EngineMode.XRAY_CF -> HomeRemoteSlot.EngineXray
    EngineMode.TOR_WEBTUNNEL -> HomeRemoteSlot.EngineTor
    EngineMode.UAC_POW -> HomeRemoteSlot.EnginePow
}

@Composable
internal fun EngineMode.switchContentDescription(): String = when (this) {
    EngineMode.XRAY_CF -> homeText("Cloudflare SNI engine", "موتور کلودفلر")
    EngineMode.TOR_WEBTUNNEL -> homeText("Tor engine", "موتور تور")
    EngineMode.UAC_POW -> homeText("PoW engine", "موتور PoW")
}

private fun EngineMode.identityIcon(): ImageVector = when (this) {
    EngineMode.XRAY_CF -> Icons.Rounded.Cloud
    EngineMode.TOR_WEBTUNNEL -> TorOnionIcon
    EngineMode.UAC_POW -> Icons.Rounded.Hexagon
}

@Composable
internal fun EngineSwitchRail(
    selected: EngineMode,
    pending: EngineMode?,
    compact: Boolean,
    enabled: Boolean,
    onSelect: (EngineMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize: Dp = if (compact) 34.dp else 38.dp
    val railShape = RoundedCornerShape(percent = 50)
    Column(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = railShape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.32f),
            )
            .clip(railShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF2141E2C),
                        Color(0xF00C141E),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.06f),
                    ),
                ),
                shape = railShape,
            )
            .padding(horizontal = 5.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EngineMode.entries.forEach { mode ->
            EngineSwitchIcon(
                mode = mode,
                selected = (pending ?: selected) == mode,
                spinning = pending == mode,
                enabled = enabled,
                size = iconSize,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun EngineSwitchIcon(
    mode: EngineMode,
    selected: Boolean,
    spinning: Boolean,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val identity = mode.identityAccent()
    val tint by animateColorAsState(
        targetValue = if (selected) Color.White else identity.copy(alpha = 0.52f),
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "engine-icon-tint",
    )
    val wellAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "engine-icon-well",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "engine-icon-scale",
    )
    val spin = rememberInfiniteTransition(label = "engine-icon-spin")
    val spinDeg by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "engine-icon-spin-deg",
    )
    val description = mode.switchContentDescription()
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = if (spinning) spinDeg else 0f
            }
            .drawBehind {
                if (wellAlpha <= 0.01f) return@drawBehind
                val radius = this.size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to identity.copy(alpha = 0.55f * wellAlpha),
                            0.42f to identity.copy(alpha = 0.22f * wellAlpha),
                            0.78f to identity.copy(alpha = 0.06f * wellAlpha),
                            1f to Color.Transparent,
                        ),
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        radius = radius,
                    ),
                    radius = radius,
                )
            }
            .clip(CircleShape)
            .background(
                color = if (selected) identity.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f),
                shape = CircleShape,
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                } else {
                    Modifier
                },
            )
            .trackHomeSlot(mode.toHomeRemoteSlot())
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .keyboardFocusRing()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = mode.identityIcon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

private val TorOnionIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Engine.TorOnion",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(12f, 1.55f)
            curveTo(12.78f, 1.55f, 13.18f, 2.12f, 13.14f, 2.88f)
            lineTo(12.78f, 5.08f)
            curveTo(17.05f, 5.48f, 20.35f, 8.92f, 20.35f, 13.28f)
            curveTo(20.35f, 17.88f, 16.62f, 21.55f, 12f, 21.55f)
            curveTo(7.38f, 21.55f, 3.65f, 17.88f, 3.65f, 13.28f)
            curveTo(3.65f, 8.92f, 6.95f, 5.48f, 11.22f, 5.08f)
            lineTo(10.86f, 2.88f)
            curveTo(10.82f, 2.12f, 11.22f, 1.55f, 12f, 1.55f)
            close()
            moveTo(12f, 7.42f)
            curveTo(15.48f, 7.42f, 18.18f, 10.08f, 18.18f, 13.35f)
            curveTo(18.18f, 16.78f, 15.38f, 19.22f, 12f, 19.22f)
            curveTo(8.62f, 19.22f, 5.82f, 16.78f, 5.82f, 13.35f)
            curveTo(5.82f, 10.08f, 8.52f, 7.42f, 12f, 7.42f)
            close()
            moveTo(12f, 10.28f)
            curveTo(14.12f, 10.28f, 15.72f, 11.78f, 15.72f, 13.52f)
            curveTo(15.72f, 15.42f, 14.02f, 16.88f, 12f, 16.88f)
            curveTo(9.98f, 16.88f, 8.28f, 15.42f, 8.28f, 13.52f)
            curveTo(8.28f, 11.78f, 9.88f, 10.28f, 12f, 10.28f)
            close()
        }
    }.build()
}
