package com.uacspoofer.mobile.ui

import android.annotation.SuppressLint
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.ui.theme.UacColors








@SuppressLint("NewApi")
@Composable
internal fun AnimatedDottedWave(
    accent: Color,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animatedWave = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(context.resources, R.drawable.uac_digital_wave),
            ) as? AnimatedImageDrawable
        } else {
            null
        }
    }

    DisposableEffect(animatedWave, motionEnabled) {
        if (animatedWave != null) {
            animatedWave.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            if (motionEnabled) animatedWave.start() else animatedWave.stop()
        }
        onDispose { animatedWave?.stop() }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(
                Brush.verticalGradient(
                    0f to UacColors.BackgroundBottom.copy(alpha = 0.10f),
                    0.22f to accent.copy(alpha = 0.025f),
                    1f to UacColors.BackgroundBottom.copy(alpha = 0.58f),
                ),
            ),
    ) {
        if (animatedWave != null) {
            val accentArgb = accent.toArgb()
            AndroidView(
                factory = { viewContext ->
                    ImageView(viewContext).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageDrawable(animatedWave)
                    }
                },
                update = { imageView ->
                    imageView.setColorFilter(accentArgb, android.graphics.PorterDuff.Mode.SRC_IN)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.uac_digital_wave_fallback),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alignment = Alignment.Center,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(accent, BlendMode.SrcIn),
            )
        }
    }
}
