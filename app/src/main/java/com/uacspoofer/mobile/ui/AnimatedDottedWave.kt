package com.uacspoofer.mobile.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.request.ImageRequestBuilder
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.ui.theme.UacColors

@Composable
internal fun AnimatedDottedWave(
    accent: Color,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    secondary: Color? = null,
    glowBlurPx: Float = 8f,
    glowAlpha: Float = 0.72f,
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .background(
                if (secondary == null) {
                    Brush.verticalGradient(
                        0f to UacColors.BackgroundBottom.copy(alpha = 0.10f),
                        0.22f to accent.copy(alpha = 0.025f),
                        1f to UacColors.BackgroundBottom.copy(alpha = 0.58f),
                    )
                } else {
                    Brush.verticalGradient(
                        0f to UacColors.BackgroundBottom.copy(alpha = 0.10f),
                        0.22f to accent.copy(alpha = 0.025f),
                        0.62f to secondary.copy(alpha = 0.025f),
                        1f to UacColors.BackgroundBottom.copy(alpha = 0.58f),
                    )
                },
            ),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PlatformAnimatedWave(
                accent = accent,
                secondary = secondary,
                motionEnabled = motionEnabled,
                glowBlurPx = glowBlurPx,
                glowAlpha = glowAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LegacyAnimatedWebpWave(
                accent = accent,
                motionEnabled = motionEnabled,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PlatformAnimatedWave(
    accent: Color,
    secondary: Color?,
    motionEnabled: Boolean,
    glowBlurPx: Float,
    glowAlpha: Float,
    modifier: Modifier = Modifier,
) {
    if (secondary != null) {
        NeonGradientWave(
            magenta = accent,
            cyan = secondary,
            motionEnabled = motionEnabled,
            glowBlurPx = glowBlurPx,
            glowAlpha = glowAlpha,
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current

    val animatedWave: Drawable? = remember(context) {
        Api28Impl.decodeAnimatedWave(context)
    }

    DisposableEffect(animatedWave, motionEnabled) {
        if (animatedWave != null) {
            Api28Impl.setRunning(
                drawable = animatedWave,
                running = motionEnabled,
            )
        }

        onDispose {
            if (animatedWave != null) {
                Api28Impl.stop(animatedWave)
            }
        }
    }

    val accentArgb = accent.toArgb()

    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(animatedWave)
            }
        },
        update = { imageView ->
            imageView.setColorFilter(
                accentArgb,
                PorterDuff.Mode.SRC_IN,
            )

            if (animatedWave != null) {
                Api28Impl.setRunning(
                    drawable = animatedWave,
                    running = motionEnabled,
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun LegacyAnimatedWebpWave(
    accent: Color,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    remember(context.applicationContext) {
        if (!Fresco.hasBeenInitialized()) {
            Fresco.initialize(context.applicationContext)
        }
        true
    }

    val accentArgb = accent.toArgb()

    AndroidView(
        factory = { viewContext ->
            SimpleDraweeView(viewContext).apply {
                hierarchy =
                    GenericDraweeHierarchyBuilder
                        .newInstance(resources)
                        .setActualImageScaleType(
                            ScalingUtils.ScaleType.CENTER_CROP,
                        )
                        .setFadeDuration(0)
                        .build()

                tag = null
            }
        },
        update = { draweeView ->
            draweeView.hierarchy.setActualImageColorFilter(
                PorterDuffColorFilter(
                    accentArgb,
                    PorterDuff.Mode.SRC_IN,
                ),
            )

            val previousMotionState =
                draweeView.tag as? Boolean

            if (previousMotionState != motionEnabled) {
                draweeView.controller
                    ?.animatable
                    ?.stop()

                val request =
                    ImageRequestBuilder
                        .newBuilderWithResourceId(
                            R.drawable.uac_digital_wave,
                        )
                        .build()

                val controller =
                    Fresco
                        .newDraweeControllerBuilder()
                        .setOldController(
                            draweeView.controller,
                        )
                        .setImageRequest(request)
                        .setAutoPlayAnimations(
                            motionEnabled,
                        )
                        .build()

                draweeView.controller = controller
                draweeView.tag = motionEnabled
            } else {
                val animatable =
                    draweeView.controller?.animatable

                if (motionEnabled) {
                    animatable?.start()
                } else {
                    animatable?.stop()
                }
            }
        },
        modifier = modifier,
    )
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun NeonGradientWave(
    magenta: Color,
    cyan: Color,
    motionEnabled: Boolean,
    glowBlurPx: Float,
    glowAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val wave = remember(context) { Api28Impl.decodeAnimatedWave(context) }
    DisposableEffect(wave, motionEnabled) {
        Api28Impl.setRunning(wave, motionEnabled)
        onDispose { Api28Impl.stop(wave) }
    }
    val tint = remember(magenta, cyan) {
        Brush.horizontalGradient(
            0f to magenta,
            0.38f to magenta,
            0.62f to cyan,
            1f to cyan,
        )
    }
    AndroidView(
        factory = { viewContext -> NeonWaveHost(viewContext) },
        update = { host ->
            host.attach(wave)
            host.style(glowBlurPx, glowAlpha)
            Api28Impl.setRunning(wave, motionEnabled)
        },
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = tint, blendMode = BlendMode.SrcIn)
            },
    )
}

@RequiresApi(Build.VERSION_CODES.P)
private class NeonWaveHost(context: Context) : FrameLayout(context), Drawable.Callback {
    private val glow = ImageView(context)
    private val sharp = ImageView(context)
    private var wave: Drawable? = null

    init {
        glow.scaleType = ImageView.ScaleType.CENTER_CROP
        sharp.scaleType = ImageView.ScaleType.CENTER_CROP
        glow.alpha = 0.72f
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(sharp, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private var appliedBlur = Float.NaN

    fun style(blur: Float, alpha: Float) {
        glow.alpha = alpha
        if (blur != appliedBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appliedBlur = blur
            glow.setRenderEffect(
                if (blur < 0.5f) {
                    null
                } else {
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                },
            )
        }
    }

    fun attach(drawable: Drawable) {
        if (wave === drawable && glow.drawable === drawable) {
            drawable.callback = this
            return
        }
        wave = drawable
        val filter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
        glow.colorFilter = filter
        sharp.colorFilter = filter
        glow.setImageDrawable(drawable)
        sharp.setImageDrawable(drawable)
        drawable.callback = this
    }

    override fun invalidateDrawable(who: Drawable) {
        glow.invalidate()
        sharp.invalidate()
        invalidate()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
        postDelayed(what, time - android.os.SystemClock.uptimeMillis())
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        removeCallbacks(what)
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private object Api28Impl {

    fun decodeAnimatedWave(
        context: Context,
    ): Drawable {
        val source =
            android.graphics.ImageDecoder.createSource(
                context.resources,
                R.drawable.uac_digital_wave,
            )

        return android.graphics.ImageDecoder.decodeDrawable(
            source,
        )
    }

    fun setRunning(
        drawable: Drawable,
        running: Boolean,
    ) {
        val animated =
            drawable as?
                android.graphics.drawable.AnimatedImageDrawable
                ?: return

        animated.repeatCount =
            android.graphics.drawable.AnimatedImageDrawable
                .REPEAT_INFINITE

        if (running) {
            animated.start()
        } else {
            animated.stop()
        }
    }

    fun stop(
        drawable: Drawable,
    ) {
        (
            drawable as?
                android.graphics.drawable.AnimatedImageDrawable
            )
            ?.stop()
    }
}
