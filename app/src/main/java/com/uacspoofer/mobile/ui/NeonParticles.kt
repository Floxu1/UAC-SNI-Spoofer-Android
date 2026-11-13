package com.uacspoofer.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlin.random.Random

@Composable
internal fun NeonParticleField(
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (motionEnabled) {
        MovingNeonParticles(modifier)
    } else {
        StillNeonParticles(modifier)
    }
}

@Composable
private fun MovingNeonParticles(modifier: Modifier) {
    val shift = rememberInfiniteTransition(label = "neon-dust").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "neon-dust-shift",
    )
    ParticleCanvas(shift, modifier)
}

@Composable
private fun StillNeonParticles(modifier: Modifier) {
    ParticleCanvas(null, modifier)
}

private class Dust(
    val xUnit: Float,
    val yFactor: Float,
    val radiusExtra: Float,
    val magenta: Boolean,
    val alpha: Float,
)

private fun dustTable(): List<Dust> {
    val random = Random(11)
    return List(42) {
        Dust(
            xUnit = random.nextFloat(),
            yFactor = 0.35f + random.nextFloat() * 0.65f,
            radiusExtra = random.nextFloat(),
            magenta = random.nextFloat() < 0.72f,
            alpha = 0.28f + random.nextFloat() * 0.5f,
        )
    }
}

@Composable
private fun ParticleCanvas(shift: State<Float>?, modifier: Modifier) {
    val spec = remember { dustTable() }
    val magenta = UacColors.NeonMagenta
    val cyan = UacColors.NeonCyan
    Canvas(modifier) {
        val drift = (shift?.value ?: 0f) * 22.dp.toPx()
        for (particle in spec) {
            val y = (size.height * particle.yFactor - drift).mod(size.height)
            drawCircle(
                color = if (particle.magenta) magenta else cyan,
                radius = 0.6.dp.toPx() + particle.radiusExtra * 1.5.dp.toPx(),
                center = Offset(particle.xUnit * size.width, y),
                alpha = particle.alpha,
            )
        }
    }
}
