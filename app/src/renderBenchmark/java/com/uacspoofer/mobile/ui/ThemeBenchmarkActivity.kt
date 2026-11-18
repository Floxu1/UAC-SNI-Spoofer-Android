package com.uacspoofer.mobile.ui

import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.theme.AppTheme
import com.uacspoofer.mobile.ui.theme.AppThemeStore
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.UacSniSpooferTheme
import com.uacspoofer.mobile.ui.theme.colorsFor
import com.uacspoofer.mobile.ui.theme.styleTokens

/** Debug-only render fixture: exercises the real effects without starting a VPN. */
class ThemeBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = AppTheme.valueOf(intent.getStringExtra("theme") ?: "NEON")
        val initialState = ConnectionState.valueOf(intent.getStringExtra("state") ?: "DISCONNECTED")
        val frozen = intent.getBooleanExtra("freeze", false)
        val effect = intent.getStringExtra("effect") ?: "full"
        require(effect in setOf("full", "comet", "reference", "sharp")) {
            "Full-reference comparison uses the archived comparison-benchmark.apk"
        }
        val fixedOrbit = intent.getFloatExtra("orbit", -1f)
        val fixedSweep = intent.getFloatExtra("sweep", 125f)
        val fixedFade = intent.getFloatExtra("fade", 1f)
        val noWave = intent.getBooleanExtra("no_wave", false)
        val stopAfterMs = intent.getIntExtra("stop_after_ms", 0)
        val stress = intent.getBooleanExtra("stress", false)
        AppThemeStore.get(this).set(theme)
        setContent {
            UacSniSpooferTheme {
                CompositionLocalProvider(LocalHomePersian provides true) {
                    var state by remember { mutableStateOf(initialState) }
                    LaunchedEffect(Unit) {
                        if (stress) {
                            repeat(8) {
                                AppThemeStore.get(this@ThemeBenchmarkActivity).set(if (it % 2 == 0) AppTheme.NEON else AppTheme.SOLARIS)
                                state = ConnectionState.CONNECTING
                                delay(450)
                                state = ConnectionState.CONNECTED
                                delay(400)
                                state = ConnectionState.DISCONNECTED
                                delay(150)
                            }
                        } else if (stopAfterMs > 0) {
                            delay(stopAfterMs.toLong())
                            state = ConnectionState.DISCONNECTED
                        }
                    }
                    val tokens = styleTokens()
                    val accent = colorsFor(state).accent
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        tokens?.bgTop ?: UacColors.BackgroundTop,
                        tokens?.bgMid ?: UacColors.BackgroundMiddle,
                        tokens?.bgBottom ?: UacColors.BackgroundBottom,
                    )))) {
                        Box(Modifier.align(Alignment.Center)) {
                            if (effect == "full" || tokens == null) {
                                ConnectButton(state, accent, 220.dp, {}, halo = 54.dp)
                            } else {
                                val transition = rememberInfiniteTransition(label = "fixture")
                                val orbit = if (fixedOrbit >= 0f) remember { mutableFloatStateOf(fixedOrbit) }
                                    else transition.animateFloat(0f, 360f, infiniteRepeatable(tween(tokens.connectOrbitMs.toInt(), easing = LinearEasing)), label = "orbit")
                                val sweep = if (fixedOrbit >= 0f) remember { mutableFloatStateOf(fixedSweep) }
                                    else transition.animateFloat(tokens.connectSweepMin, tokens.connectSweepMax, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sweep")
                                val fade = remember { mutableFloatStateOf(fixedFade) }
                                Box(Modifier.size(328.dp)) {
                                    if (effect == "comet") {
                                        ConnectingComet(tokens, 220.dp, orbit, sweep, fade)
                                    } else {
                                        val strokes = remember { ReferenceCometStrokes() }
                                        Canvas(Modifier.matchParentSize()) {
                                            val diameter = 220.dp.toPx()
                                            if (effect != "sharp") {
                                                strokes.drawTrailMasked(this, tokens, diameter, orbit, sweep, fade, tokens.connectFarBlur.dp.toPx(), tokens.connectFarAlpha)
                                                strokes.drawTrailMasked(this, tokens, diameter, orbit, sweep, fade, tokens.connectNearBlur.dp.toPx(), tokens.connectNearAlpha)
                                            }
                                            strokes.drawTrail(this, tokens, diameter, orbit, sweep, fade, fade.value)
                                            strokes.drawHead(this, tokens, diameter, orbit, fade)
                                        }
                                    }
                                }
                            }
                        }
                        if (!noWave) AnimatedDottedWave(
                            accent = tokens?.waveStart ?: accent,
                            secondary = tokens?.waveEnd,
                            motionEnabled = !frozen,
                            glowBlurPx = tokens?.waveGlowBlur ?: 8f,
                            glowAlpha = tokens?.waveGlowAlpha ?: 0.72f,
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .height((tokens?.waveHeight ?: 32f).dp),
                        )
                    }
                }
            }
        }
    }
}
