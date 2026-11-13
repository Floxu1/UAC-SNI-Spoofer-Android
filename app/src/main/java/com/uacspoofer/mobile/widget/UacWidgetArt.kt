package com.uacspoofer.mobile.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

internal object UacWidgetArt {
    fun card(targetW: Int, targetH: Int, accent: Int, edgeStart: Int): Bitmap {
        val aspect = targetW.toFloat() / targetH.coerceAtLeast(1)
        var w = targetW.coerceIn(240, 400)
        var h = (w / aspect).toInt().coerceAtLeast(1)
        if (h > 230) {
            h = 230
            w = (h * aspect).toInt().coerceIn(240, 400)
        }
        h = h.coerceIn(140, 230)
        return draw(w, h) { canvas ->
            val radius = h * 0.16f
            val rect = RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f)
            val fill = paint()
            fill.shader = LinearGradient(
                0f,
                0f,
                0f,
                h.toFloat(),
                0xFF0A1628.toInt(),
                0xFF050B11.toInt(),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, fill)

            val clip = Path()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            bloom(canvas, w * 0.02f, h * 0.02f, w * 0.55f, edgeStart, 56)
            bloom(canvas, w * 0.96f, h * 0.98f, w * 0.62f, accent, 78)
            world(canvas, w, h)
            waves(canvas, w, h, accent, edgeStart)
            canvas.restoreToCount(save)

            val glow = paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = h * 0.035f
                shader = LinearGradient(
                    0f,
                    0f,
                    w.toFloat(),
                    h.toFloat(),
                    withAlpha(edgeStart, 200),
                    withAlpha(accent, 220),
                    Shader.TileMode.CLAMP,
                )
                maskFilter = BlurMaskFilter(h * 0.045f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(rect, radius, radius, glow)
            val border = paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = (h * 0.011f).coerceAtLeast(2f)
                shader = LinearGradient(
                    0f,
                    0f,
                    w.toFloat(),
                    h.toFloat(),
                    edgeStart,
                    accent,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(rect, radius, radius, border)
        }
    }

    fun power(size: Int, accent: Int): Bitmap {
        val s = size.coerceIn(96, 168)
        return draw(s, s) { canvas ->
            val cx = s / 2f
            val cy = s / 2f
            val ring = s * 0.36f
            val halo = paint().apply {
                color = accent
                alpha = 110
                maskFilter = BlurMaskFilter(s * 0.09f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(cx, cy, ring, halo)
            val inner = paint()
            inner.shader = RadialGradient(
                cx,
                cy,
                ring * 0.92f,
                blend(0xFF051014.toInt(), accent, 0.24f),
                blend(0xFF051014.toInt(), accent, 0.05f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, ring * 0.86f, inner)
            val ringPaint = paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.045f
                color = accent
            }
            canvas.drawCircle(cx, cy, ring, ringPaint)
            glyph(canvas, cx, cy + s * 0.02f, s * 0.17f, accent)
        }
    }

    fun shield(size: Int, accent: Int): Bitmap {
        val s = size.coerceIn(48, 128)
        return draw(s, s) { canvas ->
            val path = shieldPath(s)
            val glow = paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.07f
                color = accent
                strokeJoin = Paint.Join.ROUND
                maskFilter = BlurMaskFilter(s * 0.08f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(path, glow)
            val stroke = paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.045f
                color = accent
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, stroke)
            lock(canvas, s)
        }
    }

    fun pill(width: Int, height: Int, accent: Int, active: Boolean): Bitmap {
        val w = width.coerceIn(8, 240)
        val h = height.coerceIn(8, 96)
        return draw(w, h) { canvas ->
            val rect = RectF(h * 0.08f, h * 0.08f, w - h * 0.08f, h - h * 0.08f)
            val radius = rect.height() / 2f
            if (active) {
                val glow = paint().apply {
                    color = accent
                    alpha = 120
                    maskFilter = BlurMaskFilter(h * 0.28f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(rect, radius, radius, glow)
                val fill = paint().apply { color = blend(0xFF071018.toInt(), accent, 0.28f) }
                canvas.drawRoundRect(rect, radius, radius, fill)
                val stroke = paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (h * 0.07f).coerceAtLeast(2f)
                    color = accent
                }
                canvas.drawRoundRect(rect, radius, radius, stroke)
            } else {
                val fill = paint().apply { color = 0x0DFFFFFF }
                canvas.drawRoundRect(rect, radius, radius, fill)
                val stroke = paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (h * 0.05f).coerceAtLeast(1.5f)
                    color = 0x26FFFFFF
                }
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
        }
    }

    fun dot(size: Int, accent: Int): Bitmap {
        val s = size.coerceIn(12, 64)
        return draw(s, s) { canvas ->
            val glow = paint().apply {
                color = accent
                alpha = 170
                maskFilter = BlurMaskFilter(s * 0.22f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(s / 2f, s / 2f, s * 0.28f, glow)
            canvas.drawCircle(s / 2f, s / 2f, s * 0.16f, paint().apply { color = accent })
        }
    }

    fun signal(width: Int, height: Int, accent: Int, bars: Int): Bitmap {
        val w = width.coerceIn(12, 96)
        val h = height.coerceIn(12, 96)
        return draw(w, h) { canvas ->
            val gap = w * 0.14f
            val barW = (w - gap * 2f) / 3f
            val levels = floatArrayOf(0.42f, 0.70f, 1f)
            val shown = bars.coerceIn(0, 3)
            for (i in 0..2) {
                val on = i < shown
                val paint = paint().apply {
                    color = if (on) accent else withAlpha(accent, 48)
                }
                if (on) {
                    val glow = paint().apply {
                        color = accent
                        alpha = 140
                        maskFilter = BlurMaskFilter(barW * 0.8f, BlurMaskFilter.Blur.NORMAL)
                    }
                    val left = i * (barW + gap)
                    val top = h * (1f - levels[i])
                    val rect = RectF(left, top, left + barW, h.toFloat())
                    canvas.drawRoundRect(rect, barW / 2f, barW / 2f, glow)
                    canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
                } else {
                    val left = i * (barW + gap)
                    val top = h * (1f - levels[i])
                    canvas.drawRoundRect(
                        RectF(left, top, left + barW, h.toFloat()),
                        barW / 2f,
                        barW / 2f,
                        paint,
                    )
                }
            }
        }
    }

    private fun world(canvas: Canvas, w: Int, h: Int) {
        val left = w * 0.08f
        val top = h * 0.16f
        val mw = w * 0.86f
        val mh = h * 0.70f
        val dot = paint().apply { color = 0x2EFFFFFF }
        val step = (w / 72f).coerceAtLeast(4f)
        val radius = (w / 260f).coerceAtLeast(1.1f)
        var y = top
        while (y < top + mh) {
            var x = left
            while (x < left + mw) {
                val nx = (x - left) / mw
                val ny = (y - top) / mh
                if (land(nx, ny)) canvas.drawCircle(x, y, radius, dot)
                x += step
            }
            y += step
        }
    }

    private fun land(x: Float, y: Float): Boolean {
        for (poly in CONTINENTS) {
            if (inside(x, y, poly)) return true
        }
        return false
    }

    private fun inside(x: Float, y: Float, poly: FloatArray): Boolean {
        var hit = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            val crosses = (yi > y) != (yj > y)
            if (crosses && x < (xj - xi) * (y - yi) / ((yj - yi) + 0.00001f) + xi) hit = !hit
            j = i
        }
        return hit
    }

    private fun waves(canvas: Canvas, w: Int, h: Int, accent: Int, edgeStart: Int) {
        val low = Path().apply {
            moveTo(-8f, h * 0.80f)
            cubicTo(w * 0.16f, h * 0.98f, w * 0.40f, h * 0.64f, w * 0.68f, h * 0.76f)
            cubicTo(w * 0.86f, h * 0.84f, w * 0.94f, h * 0.70f, w + 12f, h * 0.56f)
        }
        val lower = Path().apply {
            moveTo(-8f, h * 0.94f)
            cubicTo(w * 0.22f, h * 1.05f, w * 0.48f, h * 0.78f, w * 0.74f, h * 0.90f)
            cubicTo(w * 0.90f, h * 0.98f, w + 4f, h * 0.86f, w + 16f, h * 0.74f)
        }
            strokeWave(canvas, low, w, edgeStart, accent, h * 0.02f, 90)
            strokeWave(canvas, low, w, edgeStart, accent, h * 0.008f, 210)
            strokeWave(canvas, lower, w, accent, accent, h * 0.028f, 120)
            strokeWave(canvas, lower, w, accent, accent, h * 0.01f, 230)
    }

    private fun strokeWave(
        canvas: Canvas,
        path: Path,
        widthPx: Int,
        start: Int,
        end: Int,
        stroke: Float,
        alpha: Int,
    ) {
        val paint = paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke.coerceAtLeast(1.5f)
            shader = LinearGradient(
                0f,
                0f,
                widthPx.toFloat(),
                0f,
                withAlpha(start, alpha),
                withAlpha(end, alpha),
                Shader.TileMode.CLAMP,
            )
            if (stroke > 3f) maskFilter = BlurMaskFilter(stroke * 1.4f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, paint)
    }

    private fun bloom(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        val paint = paint()
        paint.shader = RadialGradient(
            cx,
            cy,
            radius.coerceAtLeast(1f),
            withAlpha(color, alpha),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun glyph(canvas: Canvas, cx: Float, cy: Float, radius: Float, accent: Int) {
        val arc = RectF(cx - radius, cy - radius * 0.78f, cx + radius, cy + radius * 0.95f)
        val glow = paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = accent
            alpha = 180
            strokeWidth = radius * 0.34f
            maskFilter = BlurMaskFilter(radius * 0.22f, BlurMaskFilter.Blur.NORMAL)
        }
        val sharp = paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = accent
            strokeWidth = radius * 0.18f
        }
        canvas.drawArc(arc, -50f, 280f, false, glow)
        canvas.drawArc(arc, -50f, 280f, false, sharp)
        canvas.drawLine(cx, cy - radius * 1.15f, cx, cy - radius * 0.05f, glow)
        canvas.drawLine(cx, cy - radius * 1.15f, cx, cy - radius * 0.05f, sharp)
    }

    private fun shieldPath(size: Int): Path {
        val s = size.toFloat()
        return Path().apply {
            moveTo(s * 0.50f, s * 0.10f)
            lineTo(s * 0.82f, s * 0.22f)
            lineTo(s * 0.82f, s * 0.48f)
            cubicTo(s * 0.82f, s * 0.66f, s * 0.68f, s * 0.80f, s * 0.50f, s * 0.90f)
            cubicTo(s * 0.32f, s * 0.80f, s * 0.18f, s * 0.66f, s * 0.18f, s * 0.48f)
            lineTo(s * 0.18f, s * 0.22f)
            close()
        }
    }

    private fun lock(canvas: Canvas, size: Int) {
        val s = size.toFloat()
        val body = paint().apply { color = Color.WHITE }
        val rect = RectF(s * 0.40f, s * 0.46f, s * 0.60f, s * 0.64f)
        canvas.drawRoundRect(rect, s * 0.03f, s * 0.03f, body)
        val shackle = paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
            strokeWidth = s * 0.045f
        }
        canvas.drawArc(
            RectF(s * 0.42f, s * 0.34f, s * 0.58f, s * 0.52f),
            180f,
            180f,
            false,
            shackle,
        )
    }

    private fun draw(w: Int, h: Int, block: (Canvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        runCatching { block(Canvas(bitmap)) }
        return bitmap
    }

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(base: Int, tint: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun ch(from: Int, to: Int) = (from + (to - from) * t).toInt().coerceIn(0, 255)
        return Color.rgb(
            ch(Color.red(base), Color.red(tint)),
            ch(Color.green(base), Color.green(tint)),
            ch(Color.blue(base), Color.blue(tint)),
        )
    }

    private val CONTINENTS = listOf(
        floatArrayOf(
            0.06f, 0.30f, 0.12f, 0.18f, 0.20f, 0.15f, 0.28f, 0.20f, 0.33f, 0.30f,
            0.28f, 0.40f, 0.23f, 0.50f, 0.20f, 0.46f, 0.15f, 0.38f, 0.08f, 0.36f,
        ),
        floatArrayOf(
            0.22f, 0.52f, 0.30f, 0.50f, 0.34f, 0.60f, 0.31f, 0.76f, 0.26f, 0.90f,
            0.22f, 0.78f, 0.20f, 0.62f,
        ),
        floatArrayOf(
            0.30f, 0.08f, 0.36f, 0.05f, 0.39f, 0.12f, 0.34f, 0.18f, 0.30f, 0.15f,
        ),
        floatArrayOf(
            0.44f, 0.20f, 0.50f, 0.15f, 0.56f, 0.18f, 0.55f, 0.30f, 0.48f, 0.34f, 0.44f, 0.28f,
        ),
        floatArrayOf(
            0.44f, 0.36f, 0.56f, 0.34f, 0.62f, 0.46f, 0.57f, 0.64f, 0.50f, 0.76f,
            0.44f, 0.66f, 0.42f, 0.48f,
        ),
        floatArrayOf(
            0.56f, 0.16f, 0.68f, 0.12f, 0.82f, 0.16f, 0.92f, 0.26f, 0.88f, 0.40f,
            0.74f, 0.46f, 0.60f, 0.38f, 0.55f, 0.26f,
        ),
        floatArrayOf(
            0.78f, 0.58f, 0.90f, 0.56f, 0.94f, 0.66f, 0.86f, 0.76f, 0.77f, 0.70f,
        ),
        floatArrayOf(0.44f, 0.18f, 0.47f, 0.16f, 0.48f, 0.22f, 0.45f, 0.24f),
        floatArrayOf(0.86f, 0.30f, 0.89f, 0.26f, 0.90f, 0.36f, 0.87f, 0.38f),
        floatArrayOf(0.58f, 0.62f, 0.61f, 0.60f, 0.62f, 0.72f, 0.59f, 0.74f),
    )
}
