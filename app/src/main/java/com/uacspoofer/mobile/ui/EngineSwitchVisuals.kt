package com.uacspoofer.mobile.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
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

@Composable
internal fun EngineSwitchGlyph(
    accent: Color,
    spinning: Boolean,
    spinNonce: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(spinNonce, spinning) {
        if (spinNonce == 0 && !spinning) return@LaunchedEffect
        if (spinning) {
            while (true) {
                rotation.animateTo(
                    rotation.value + 360f,
                    animationSpec = tween(1_050, easing = LinearEasing),
                )
            }
        } else {
            rotation.animateTo(
                rotation.value + 360f,
                animationSpec = tween(640, easing = FastOutSlowInEasing),
            )
        }
    }
    val shieldSize: Dp = if (compact) 20.dp else 22.dp
    val glyphSize: Dp = if (compact) 42.dp else 46.dp
    Box(
        modifier = modifier.size(glyphSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val bloom = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to accent.copy(alpha = 0.16f),
                        0.38f to accent.copy(alpha = 0.08f),
                        0.68f to accent.copy(alpha = 0.03f),
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = bloom,
                ),
                center = center,
                radius = bloom,
            )
            val arrowRadius = shieldSize.toPx() * 0.78f
            val arrowTopLeft = Offset(center.x - arrowRadius, center.y - arrowRadius)
            val arrowSize = Size(arrowRadius * 2f, arrowRadius * 2f)
            rotate(rotation.value, center) {
                listOf(-36f, 144f).forEach { start ->
                    drawArc(
                        color = accent.copy(alpha = 0.42f),
                        startAngle = start,
                        sweepAngle = 64f,
                        useCenter = false,
                        topLeft = arrowTopLeft,
                        size = arrowSize,
                        style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(shieldSize),
        )
    }
}
