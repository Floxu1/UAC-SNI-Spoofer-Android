package com.uacspoofer.mobile.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class CometMaskRequest(
    val width: Int,
    val height: Int,
    val diameter: Float,
    val stroke: Float,
    val span: Float,
    val farBlur: Float,
    val nearBlur: Float,
    val farVisible: Boolean,
    val nearVisible: Boolean,
) {
    fun sameGeometry(other: CometMaskRequest): Boolean =
        width == other.width && height == other.height && diameter == other.diameter &&
            stroke == other.stroke && farBlur == other.farBlur && nearBlur == other.nearBlur &&
            farVisible == other.farVisible && nearVisible == other.nearVisible
}

internal class CometMaskPixels {
    lateinit var request: CometMaskRequest
    var far: ByteBuffer? = null
    var near: ByteBuffer? = null
}

internal interface CometMaskRasterizer : AutoCloseable {
    fun draw(request: CometMaskRequest, pixels: CometMaskPixels)
    override fun close() = Unit
}

/** One in-flight job, one replaceable request, and bounded CPU-only output storage. */
internal class CometMaskWorker(
    private val onReady: (CometMaskWorker) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "uac-comet-mask").apply { isDaemon = true }
    },
    private val rasterizerFactory: () -> CometMaskRasterizer = { BitmapCometMaskRasterizer() },
) : AutoCloseable {
    private val lock = Any()
    private val free = ArrayDeque<CometMaskPixels>().apply { repeat(3) { add(CometMaskPixels()) } }
    private var pending: CometMaskRequest? = null
    private var lastRequest: CometMaskRequest? = null
    private var ready: CometMaskPixels? = null
    private var running = false
    private var used = false
    private var closed = false
    private var failed = false
    // Accessed and disposed only on the single worker thread.
    private var rasterizer: CometMaskRasterizer? = null

    val isClosed: Boolean get() = synchronized(lock) { closed }
    val hasFailed: Boolean get() = synchronized(lock) { failed }

    fun request(request: CometMaskRequest) {
        val start = synchronized(lock) {
            if (closed || failed || request == lastRequest) return
            lastRequest = request
            used = true
            pending = request
            if (running) false else { running = true; true }
        }
        if (start) schedule()
    }

    fun takeReady(): CometMaskPixels? = synchronized(lock) {
        ready.also { ready = null }
    }

    // UI returns storage only after its synchronous pixel copy is complete.
    fun release(pixels: CometMaskPixels) {
        val start = synchronized(lock) {
            if (closed || failed) return
            free.addLast(pixels)
            if (!running && pending != null) { running = true; true } else false
        }
        if (start) schedule()
    }

    private fun schedule() {
        try {
            executor.execute(::drain)
        } catch (_: Exception) {
            scheduleFailed()
        } catch (_: OutOfMemoryError) {
            scheduleFailed()
        }
    }

    private fun scheduleFailed() {
        synchronized(lock) {
            running = false
            pending = null
            if (!closed) failed = true
        }
        if (!isClosed) onReady(this)
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) {
                val request = pending
                if (closed || failed || request == null) {
                    running = false
                    return
                }
                val output = free.pollFirst() ?: ready?.also { ready = null }
                if (output == null) {
                    running = false
                    return
                }
                pending = null
                request to output
            }
            try {
                val renderer = rasterizer ?: rasterizerFactory().also { rasterizer = it }
                renderer.draw(work.first, work.second)
                work.second.request = work.first
            } catch (_: Exception) {
                fail()
                return
            } catch (_: OutOfMemoryError) {
                fail()
                return
            }
            val publish = synchronized(lock) {
                if (closed) {
                    running = false
                    false
                } else {
                    ready?.let(free::addLast)
                    ready = work.second
                    true
                }
            }
            if (!publish) return
            onReady(this)
        }
    }

    private fun fail() {
        synchronized(lock) {
            failed = true
            running = false
            pending = null
            ready = null
            free.clear()
        }
        disposeRasterizer()
        if (!isClosed) onReady(this)
    }

    private fun disposeRasterizer() {
        runCatching { rasterizer?.close() }
        rasterizer = null
    }

    override fun close() {
        val cleanup = synchronized(lock) {
            if (closed) return
            closed = true
            pending = null
            ready = null
            free.clear()
            used
        }
        // Cleanup runs after any native blur already in progress; UI never joins it.
        if (cleanup) {
            try {
                executor.execute(::disposeRasterizer)
            } catch (_: Exception) {
                // The executor may already be shut down after a scheduling failure.
            } catch (_: OutOfMemoryError) {
                // Shutdown still happens if the cleanup task cannot be allocated.
            }
        }
        executor.shutdown()
    }
}

private class BitmapCometMaskRasterizer : CometMaskRasterizer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
        color = Color.WHITE
    }
    private val path = Path()
    private val oval = RectF()
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var farRadius = Float.NaN
    private var nearRadius = Float.NaN
    private var farFilter: BlurMaskFilter? = null
    private var nearFilter: BlurMaskFilter? = null

    override fun draw(request: CometMaskRequest, pixels: CometMaskPixels) {
        var buffer = bitmap
        if (buffer == null || buffer.width != request.width || buffer.height != request.height) {
            buffer?.recycle()
            buffer = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ALPHA_8)
            bitmap = buffer
            canvas = Canvas(buffer)
        }
        val radius = request.diameter / 2f + request.stroke / 2f
        val cx = request.width / 2f
        val cy = request.height / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val head = 180f + request.span / 2f
        path.rewind()
        path.addArc(oval, head - request.span, request.span)
        paint.strokeWidth = request.stroke
        if (request.farVisible) {
            if (farRadius != request.farBlur) {
                farFilter = BlurMaskFilter(request.farBlur, BlurMaskFilter.Blur.NORMAL)
                farRadius = request.farBlur
            }
            pixels.far = rasterize(buffer, farFilter!!, pixels.far)
        }
        if (request.nearVisible) {
            if (nearRadius != request.nearBlur) {
                nearFilter = BlurMaskFilter(request.nearBlur, BlurMaskFilter.Blur.NORMAL)
                nearRadius = request.nearBlur
            }
            pixels.near = rasterize(buffer, nearFilter!!, pixels.near)
        }
    }

    private fun rasterize(bitmap: Bitmap, filter: BlurMaskFilter, previous: ByteBuffer?): ByteBuffer {
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.maskFilter = filter
        canvas!!.drawPath(path, paint)
        val size = bitmap.rowBytes * bitmap.height
        val bytes = previous?.takeIf { it.capacity() == size } ?: ByteBuffer.allocateDirect(size)
        bytes.clear()
        bitmap.copyPixelsToBuffer(bytes)
        bytes.flip()
        return bytes
    }

    override fun close() {
        bitmap?.recycle()
        bitmap = null
        canvas = null
    }
}
