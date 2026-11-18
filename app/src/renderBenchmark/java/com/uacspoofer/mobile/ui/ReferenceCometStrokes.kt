package com.uacspoofer.mobile.ui

import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import com.uacspoofer.mobile.ui.theme.NeonTokens
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

internal class ReferenceCometStrokes {
    private val trailPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isDither = true
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    }
    private val maskPaint = android.graphics.Paint(trailPaint)
    private val addPaint = android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    }
    private val recordPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isDither = true
    }
    private val path = android.graphics.Path()
    private val oval = android.graphics.RectF()
    private val colors = IntArray(7)
    private val stops = FloatArray(7)
    private var sweepShader: android.graphics.SweepGradient? = null
    private var shaderCx = Float.NaN
    private var shaderCy = Float.NaN
    private var shaderSpan = Float.NaN
    private var shaderStart = 0
    private var shaderEnd = 0
    private var bitmap: android.graphics.Bitmap? = null
    private var maskRadius = Float.NaN
    private var maskFilter: android.graphics.BlurMaskFilter? = null
    private var farNode: Any? = null
    private var nearNode: Any? = null
    private var farSigma = Float.NaN
    private var nearSigma = Float.NaN
    private var gpuFailed = false
    private var placedTrail: PlacedTrail? = null
    private var placedTokens: NeonTokens? = null
    private var placedDiameter = Float.NaN
    private var placedWidth = Float.NaN
    private var placedHeight = Float.NaN
    private var placedDensity = Float.NaN
    private var placedSpan = Float.NaN
    private var placedOrbit = Float.NaN
    private var bitmapCanvas: android.graphics.Canvas? = null

    fun drawGpuGlows(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
    ): Boolean {
        if (gpuFailed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (fade.value <= 0.001f) return true
        return drawGpuGlows31(scope, tokens, diameterPx, orbit, sweep, fade)
    }

    private fun drawGpuGlows31(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
    ): Boolean {
        return try {
            val placed = place(scope, tokens, diameterPx, orbit, sweep) ?: return true
            val presence = fade.value
            scope.drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                glowNode(
                    native = native,
                    placed = placed,
                    far = true,
                    blurPx = with(scope) { tokens.connectFarBlur.dp.toPx() },
                    layerAlpha = tokens.connectFarAlpha,
                    presence = presence,
                    width = scope.size.width.toInt().coerceAtLeast(1),
                    height = scope.size.height.toInt().coerceAtLeast(1),
                )
                glowNode(
                    native = native,
                    placed = placed,
                    far = false,
                    blurPx = with(scope) { tokens.connectNearBlur.dp.toPx() },
                    layerAlpha = tokens.connectNearAlpha,
                    presence = presence,
                    width = scope.size.width.toInt().coerceAtLeast(1),
                    height = scope.size.height.toInt().coerceAtLeast(1),
                )
            }
            true
        } catch (error: Throwable) {
            gpuFailed = true
            false
        }
    }

    private fun glowNode(
        native: android.graphics.Canvas,
        placed: PlacedTrail,
        far: Boolean,
        blurPx: Float,
        layerAlpha: Float,
        presence: Float,
        width: Int,
        height: Int,
    ) {
        if (blurPx < 1f || layerAlpha <= 0.001f || width < 2 || height < 2) return
        val sigma = blurPx * 0.57735f + 0.5f
        val node = renderNode(far)
        node.setPosition(0, 0, width, height)
        node.setClipToBounds(false)
        val cached = if (far) farSigma else nearSigma
        if (cached != sigma) {
            node.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    sigma,
                    sigma,
                    android.graphics.Shader.TileMode.DECAL,
                ),
            )
            if (far) farSigma = sigma else nearSigma = sigma
        }
        recordPaint.shader = placed.shader
        recordPaint.strokeWidth = placed.stroke
        recordPaint.alpha = (layerAlpha.coerceIn(0f, 1f) * presence * 255f).toInt().coerceIn(0, 255)
        val recording = node.beginRecording()
        recording.save()
        recording.rotate(placed.spin, placed.cx, placed.cy)
        recording.drawPath(path, recordPaint)
        recording.restore()
        node.endRecording()
        val checkpoint = native.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), addPaint)
        native.drawRenderNode(node)
        native.restoreToCount(checkpoint)
    }

    private fun renderNode(far: Boolean): android.graphics.RenderNode {
        val current = (if (far) farNode else nearNode) as? android.graphics.RenderNode
        if (current != null) return current
        val created = android.graphics.RenderNode(if (far) "uac-comet-far" else "uac-comet-near")
        created.setClipToBounds(false)
        if (far) farNode = created else nearNode = created
        return created
    }

    fun drawTrail(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
        alphaScale: Float,
    ) {
        if (fade.value <= 0.001f || alphaScale <= 0.001f) return
        val placed = place(scope, tokens, diameterPx, orbit, sweep) ?: return
        trailPaint.shader = placed.shader
        trailPaint.strokeWidth = placed.stroke
        trailPaint.alpha = (alphaScale.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        scope.rotate(placed.spin, Offset(placed.cx, placed.cy)) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPath(path, trailPaint)
            }
        }
    }

    fun drawTrailMasked(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
        fade: State<Float>,
        blurPx: Float,
        layerAlpha: Float,
    ) {
        val presence = fade.value
        if (presence <= 0.001f || layerAlpha <= 0.001f || blurPx < 1f) return
        val placed = place(scope, tokens, diameterPx, orbit, sweep) ?: return
        val width = scope.size.width.toInt().coerceAtLeast(1)
        val height = scope.size.height.toInt().coerceAtLeast(1)
        val buffer = bitmap?.takeIf { it.width == width && it.height == height } ?: android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        ).also {
            bitmap?.recycle()
            bitmap = it
            bitmapCanvas = android.graphics.Canvas(it)
        }
        if (maskRadius != blurPx) {
            maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
            maskRadius = blurPx
        }
        buffer.eraseColor(android.graphics.Color.TRANSPARENT)
        maskPaint.shader = placed.shader
        maskPaint.strokeWidth = placed.stroke
        maskPaint.maskFilter = maskFilter
        maskPaint.xfermode = null
        maskPaint.alpha = (layerAlpha.coerceIn(0f, 1f) * presence * 255f).toInt().coerceIn(0, 255)
        val software = bitmapCanvas ?: android.graphics.Canvas(buffer).also { bitmapCanvas = it }
        software.save()
        software.rotate(placed.spin, placed.cx, placed.cy)
        software.drawPath(path, maskPaint)
        software.restore()
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(buffer, 0f, 0f, addPaint)
        }
    }

    fun drawHead(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        fade: State<Float>,
    ) {
        val presence = fade.value
        if (presence <= 0.001f) return
        val orbitDeg = orbit.value
        val stroke = with(scope) { tokens.ringWidth.dp.toPx() }
        val radius = diameterPx / 2f + stroke / 2f
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val theta = Math.toRadians(orbitDeg.toDouble())
        val hx = cx + radius * cos(theta).toFloat()
        val hy = cy + radius * sin(theta).toFloat()
        val haloPx = with(scope) { 11.dp.toPx() }
        val headPx = with(scope) { tokens.connectHeadRadius.dp.toPx() }
        val head = Offset(hx, hy)
        scope.drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to tokens.gradientEnd.copy(alpha = 0.55f * presence),
                    1f to Color.Transparent,
                ),
                center = head,
                radius = haloPx,
            ),
            radius = haloPx,
            center = head,
            blendMode = BlendMode.Plus,
        )
        scope.drawCircle(
            color = Color(0xFFEBFFFF).copy(alpha = presence),
            radius = headPx,
            center = head,
        )
    }

    private fun place(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        tokens: NeonTokens,
        diameterPx: Float,
        orbit: State<Float>,
        sweep: State<Float>,
    ): PlacedTrail? {
        val stroke = with(scope) { tokens.ringWidth.dp.toPx() }
        if (stroke < 0.5f || diameterPx < 1f) return null
        val span = sweep.value.coerceIn(8f, 350f)
        val orbitDeg = orbit.value
        val cached = placedTrail
        if (cached != null && placedTokens === tokens && placedDiameter == diameterPx &&
            placedWidth == scope.size.width && placedHeight == scope.size.height &&
            placedDensity == scope.density && placedSpan == span && placedOrbit == orbitDeg
        ) return cached
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val radius = diameterPx / 2f + stroke / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val head = 180f + span / 2f
        path.rewind()
        path.addArc(oval, head - span, span)
        val shader = sweepShader(cx, cy, span, tokens.gradientStart, tokens.gradientEnd)
        return PlacedTrail(shader, stroke, orbitDeg - head, cx, cy).also {
            placedTrail = it
            placedTokens = tokens
            placedDiameter = diameterPx
            placedWidth = scope.size.width
            placedHeight = scope.size.height
            placedDensity = scope.density
            placedSpan = span
            placedOrbit = orbitDeg
        }
    }

    private fun sweepShader(
        cx: Float,
        cy: Float,
        span: Float,
        gradientStart: Color,
        gradientEnd: Color,
    ): android.graphics.SweepGradient {
        val head = 180f + span / 2f
        val tail = 180f - span / 2f
        val at60 = head - span * 0.60f
        val at15 = head - span * 0.15f
        val after = (head + 1.15f).coerceAtMost(359.3f)
        stops[0] = 0f
        stops[1] = tail / 360f
        stops[2] = at60 / 360f
        stops[3] = at15 / 360f
        stops[4] = head / 360f
        stops[5] = after / 360f
        stops[6] = 1f
        for (index in 1 until stops.lastIndex) {
            if (stops[index] <= stops[index - 1]) {
                stops[index] = (stops[index - 1] + 0.0008f).coerceAtMost(0.998f)
            }
        }
        val startColor = referenceColorWithAlpha(gradientStart, 0.5f)
        val endColor = referenceColorWithAlpha(gradientEnd, 1f)
        colors[0] = 0
        colors[1] = 0
        colors[2] = startColor
        colors[3] = endColor
        colors[4] = 0xFFEBFFFF.toInt()
        colors[5] = 0
        colors[6] = 0
        val moved = sweepShader == null ||
            shaderCx != cx ||
            shaderCy != cy ||
            shaderStart != startColor ||
            shaderEnd != endColor ||
            kotlin.math.abs(shaderSpan - span) > 0.2f
        if (moved) {
            sweepShader = android.graphics.SweepGradient(cx, cy, colors, stops)
            shaderCx = cx
            shaderCy = cy
            shaderStart = startColor
            shaderEnd = endColor
            shaderSpan = span
        }
        return sweepShader!!
    }

    private class PlacedTrail(
        val shader: android.graphics.SweepGradient,
        val stroke: Float,
        val spin: Float,
        val cx: Float,
        val cy: Float,
    )
}

private fun referenceColorWithAlpha(color: Color, alpha: Float): Int {
 val rgb = color.toArgb() and 0x00FFFFFF
 val channel = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
 return (channel shl 24) or rgb
}
