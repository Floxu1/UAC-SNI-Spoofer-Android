package com.uacspoofer.mobile.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.BuildConfig
import kotlin.math.max
import kotlin.math.min

internal val LocalWideShell = staticCompositionLocalOf { false }

internal object WideShell {
    val HomeContentMax = 420.dp
    val PageContentMax = 640.dp
    val EdgePadding = 28.dp

    fun isWide(width: Dp, height: Dp): Boolean =
        width >= 600.dp && width > height * 1.08f

    fun isWideDevice(context: Context): Boolean {
        if (BuildConfig.TV_MODE) return true
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("android.hardware.type.television")) return true
        if (context.resources.configuration.smallestScreenWidthDp >= 600) return true
        val (widthPx, heightPx) = displaySizePx(context)
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val shortestDp = min(widthPx, heightPx) / density
        val longestDp = max(widthPx, heightPx) / density
        val landscapeDisplay = widthPx > heightPx
        return landscapeDisplay && shortestDp >= 480f && longestDp / shortestDp >= 1.4f
    }

    private fun displaySizePx(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            point.x to point.y
        }
    }
}

@Composable
internal fun rememberWideShell(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return WideShell.isWideDevice(context) ||
        WideShell.isWide(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
}

@Composable
internal fun WideSplitColumn(
    modifier: Modifier = Modifier,
    headerPadding: Dp = 16.dp,
    bodyMaxWidth: Dp = WideShell.PageContentMax,
    header: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val wide = LocalWideShell.current
    Column(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (wide) WideShell.EdgePadding else headerPadding),
        ) {
            header()
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .then(if (wide) Modifier.widthIn(max = bodyMaxWidth) else Modifier)
                    .padding(horizontal = headerPadding),
                content = body,
            )
        }
    }
}

@Composable
internal fun WideCenteredBox(
    modifier: Modifier = Modifier,
    maxWidth: Dp = WideShell.PageContentMax,
    content: @Composable BoxScope.() -> Unit,
) {
    val wide = LocalWideShell.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier.widthIn(max = maxWidth) else Modifier),
            content = content,
        )
    }
}
