package com.uacspoofer.mobile.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.uacspoofer.mobile.R

internal object UacWidgetAssets {
    private val decoded = HashMap<Int, Bitmap>()
    private val powerByAccent = HashMap<Int, Bitmap>()
    private val panels = HashMap<Long, Bitmap>()
    private val marks = HashMap<String, Bitmap>()

    fun panel(context: Context, widthPx: Int, heightPx: Int): Bitmap {
        val src = bitmap(context, R.drawable.widget_panel_art)
        var w = widthPx.coerceAtLeast(1)
        var h = heightPx.coerceAtLeast(1)
        if (w > PANEL_MAX_W) {
            h = (h * (PANEL_MAX_W.toFloat() / w)).toInt().coerceAtLeast(1)
            w = PANEL_MAX_W
        }
        if (h > PANEL_MAX_H) {
            w = (w * (PANEL_MAX_H.toFloat() / h)).toInt().coerceAtLeast(1)
            h = PANEL_MAX_H
        }
        val key = w.toLong() shl 32 or h.toLong()
        panels[key]?.let { return it }
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        panels[key] = scaled
        return scaled
    }

    fun shield(context: Context): Bitmap = bitmap(context, R.drawable.widget_shield_art)

    fun pingIcon(): Bitmap = marks.getOrPut("ping") { icon(0xFF3DFF8A.toInt(), ::drawBolt) }

    fun globeIcon(): Bitmap = marks.getOrPut("globe") { icon(0xFF2EE6D0.toInt(), ::drawGlobe) }

    fun downIcon(): Bitmap = marks.getOrPut("down") { icon(0xFF3DFF8A.toInt()) { canvas, color -> drawArrow(canvas, color, up = false) } }

    fun upIcon(): Bitmap = marks.getOrPut("up") { icon(0xFF3AA8FF.toInt()) { canvas, color -> drawArrow(canvas, color, up = true) } }

    fun downWave(): Bitmap = marks.getOrPut("wave-down") { wave(0xFF2CFF9A.toInt(), downShape) }

    fun upWave(): Bitmap = marks.getOrPut("wave-up") { wave(0xFF3DB7FF.toInt(), upShape) }

    fun power(accent: Int, glow: Float = 1f, phase: Float = 0f): Bitmap {
        if (glow == 1f && phase == 0f) {
            powerByAccent[accent]?.let { return it }
            val drawn = drawPower(accent, 1f, 0f)
            powerByAccent[accent] = drawn
            return drawn
        }
        return drawPower(accent, glow.coerceIn(0.45f, 1.45f), phase)
    }

    private fun bitmap(context: Context, resId: Int): Bitmap {
        decoded[resId]?.let { return it }
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(context.resources, resId, options)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        decoded[resId] = bitmap
        return bitmap
    }

    private fun drawPower(accent: Int, glow: Float, phase: Float): Bitmap {
        val size = 240
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val ring = size * 0.39f
        val breathe = (70 + 50 * glow).toInt().coerceIn(40, 180)
        softCircle(canvas, cx, cy, size * 0.47f, size * (0.05f + 0.03f * glow), accent, breathe)
        val angle = phase * Math.PI.toFloat() * 2f
        val orbit = ring * 0.22f * glow
        softCircle(
            canvas,
            cx + kotlin.math.cos(angle) * orbit,
            cy + kotlin.math.sin(angle) * orbit,
            ring * 0.55f,
            size * 0.04f,
            accent,
            (120 * glow).toInt().coerceIn(40, 200),
        )
        softCircle(canvas, cx, cy, ring, size * 0.04f, accent, (150 * glow).toInt().coerceIn(80, 220))
        val disc = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy - ring * 0.18f,
                ring * 0.92f,
                intArrayOf(
                    blend(0xFF07140F.toInt(), accent, 0.62f),
                    blend(0xFF061018.toInt(), accent, 0.20f),
                    blend(0xFF03060A.toInt(), accent, 0.06f),
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, ring * 0.84f, disc)
        val bloom = stroke(accent, size * 0.055f, 190).apply {
            maskFilter = BlurMaskFilter(size * 0.028f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, ring, bloom)
        canvas.drawCircle(cx, cy, ring, stroke(accent, size * 0.026f, 255))
        canvas.drawCircle(cx, cy, ring, stroke(withWhite(accent, 0.62f), size * 0.009f, 230))
        val glass = stroke(Color.WHITE, size * 0.007f, 48)
        canvas.drawCircle(cx, cy, ring * 0.78f, glass)
        val shine = stroke(Color.WHITE, size * 0.012f, 185).apply { strokeCap = Paint.Cap.ROUND }
        val arc = RectF(cx - ring, cy - ring, cx + ring, cy + ring)
        canvas.drawArc(arc, 206f, 52f, false, shine)
        glyph(canvas, cx, cy + size * 0.01f, size * 0.145f, accent)
        return bitmap
    }

    private fun glyph(canvas: Canvas, cx: Float, cy: Float, radius: Float, accent: Int) {
        val arc = RectF(cx - radius, cy - radius * 0.78f, cx + radius, cy + radius * 0.95f)
        val glow = stroke(accent, radius * 0.42f, 170).apply {
            strokeCap = Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(radius * 0.28f, BlurMaskFilter.Blur.NORMAL)
        }
        val body = stroke(accent, radius * 0.20f, 255).apply { strokeCap = Paint.Cap.ROUND }
        val hot = stroke(withWhite(accent, 0.72f), radius * 0.07f, 255).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawArc(arc, -52f, 284f, false, glow)
        canvas.drawArc(arc, -52f, 284f, false, body)
        canvas.drawArc(arc, -52f, 284f, false, hot)
        val top = cy - radius * 1.18f
        val mid = cy - radius * 0.02f
        canvas.drawLine(cx, top, cx, mid, glow)
        canvas.drawLine(cx, top, cx, mid, body)
        canvas.drawLine(cx, top, cx, mid, hot)
    }

    private fun softCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        blur: Float,
        color: Int,
        alpha: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha
            maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun stroke(color: Int, width: Float, alpha: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width.coerceAtLeast(1f)
            this.color = color
            this.alpha = alpha
        }

    private fun icon(color: Int, draw: (Canvas, Int) -> Unit): Bitmap {
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), color)
        return bitmap
    }

    private fun drawBolt(canvas: Canvas, color: Int) {
        val path = Path().apply {
            moveTo(40f, 8f)
            lineTo(22f, 38f)
            lineTo(33f, 38f)
            lineTo(27f, 64f)
            lineTo(54f, 30f)
            lineTo(41f, 30f)
            close()
        }
        fillGlow(canvas, path, color)
    }

    private fun drawGlobe(canvas: Canvas, color: Int) {
        val cx = 36f
        val cy = 36f
        val radius = 18f
        val glow = stroke(color, 6f, 150).apply {
            maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        }
        val body = stroke(color, 2.6f, 255)
        val hot = stroke(withWhite(color, 0.7f), 1.1f, 220)
        canvas.drawCircle(cx, cy, radius, glow)
        canvas.drawCircle(cx, cy, radius, body)
        canvas.drawCircle(cx, cy, radius, hot)
        val equator = RectF(cx - radius, cy - radius * 0.36f, cx + radius, cy + radius * 0.36f)
        canvas.drawOval(equator, body)
        canvas.drawOval(equator, hot)
        val meridian = RectF(cx - radius * 0.42f, cy - radius, cx + radius * 0.42f, cy + radius)
        canvas.drawOval(meridian, body)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, hot)
    }

    private fun drawArrow(canvas: Canvas, color: Int, up: Boolean) {
        val path = Path()
        if (up) {
            path.moveTo(36f, 10f)
            path.lineTo(54f, 32f)
            path.lineTo(44f, 32f)
            path.lineTo(44f, 62f)
            path.lineTo(28f, 62f)
            path.lineTo(28f, 32f)
            path.lineTo(18f, 32f)
        } else {
            path.moveTo(36f, 62f)
            path.lineTo(54f, 40f)
            path.lineTo(44f, 40f)
            path.lineTo(44f, 10f)
            path.lineTo(28f, 10f)
            path.lineTo(28f, 40f)
            path.lineTo(18f, 40f)
        }
        path.close()
        fillGlow(canvas, path, color)
    }

    private fun fillGlow(canvas: Canvas, path: Path, color: Int) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, glow)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withWhite(color, 0.72f)
                alpha = 90
            },
        )
    }

    private fun wave(color: Int, samples: FloatArray): Bitmap {
        val width = 140
        val height = 56
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val line = Path()
        line.moveTo(0f, samples[0] * height)
        val step = width.toFloat() / (samples.size - 1)
        for (index in 1 until samples.size) {
            line.lineTo(step * index, samples[index] * height)
        }
        val fill = Path(line)
        fill.lineTo(width.toFloat(), height.toFloat())
        fill.lineTo(0f, height.toFloat())
        fill.close()
        canvas.drawPath(
            fill,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    height.toFloat(),
                    color and 0x00FFFFFF or 0x66000000,
                    color and 0x00FFFFFF,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(line, glow)
        canvas.drawPath(
            line,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = withWhite(color, 0.55f)
            },
        )
        return bitmap
    }

    private fun withWhite(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

    private fun blend(base: Int, tint: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(from: Int, to: Int) = (from + (to - from) * t).toInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(base), Color.red(tint)),
            channel(Color.green(base), Color.green(tint)),
            channel(Color.blue(base), Color.blue(tint)),
        )
    }

    private val downShape = floatArrayOf(0.72f, 0.64f, 0.70f, 0.42f, 0.56f, 0.28f, 0.40f, 0.34f)
    private val upShape = floatArrayOf(0.78f, 0.62f, 0.68f, 0.58f, 0.36f, 0.48f, 0.30f, 0.22f)

    private const val PANEL_MAX_W = 440
    private const val PANEL_MAX_H = 240
}
