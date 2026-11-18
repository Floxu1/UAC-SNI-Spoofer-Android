package com.uacspoofer.mobile.ui

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class CometMaskWorkerTest {
    private fun request(span: Float = 100f) = CometMaskRequest(8, 8, 4f, 1f, span, 2f, 1f, true, true)

    private open class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.addLast(command)
        }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = tasks.toMutableList().also { tasks.clear(); stopped = true }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class Renderer(private val action: (CometMaskRequest) -> Unit = {}) : CometMaskRasterizer {
        val spans = mutableListOf<Float>()
        var closes = 0
        override fun draw(request: CometMaskRequest, pixels: CometMaskPixels) {
            spans.add(request.span)
            pixels.far = (pixels.far ?: ByteBuffer.allocate(4)).also { it.putInt(0, request.span.toInt()) }
            action(request)
        }
        override fun close() { closes++ }
    }

    @Test fun burstKeepsOnlyNewestRequestAndOneScheduledTask() {
        val executor = ManualExecutor()
        val renderer = Renderer()
        val worker = CometMaskWorker(executor = executor, rasterizerFactory = { renderer })
        repeat(1000) { worker.request(request(100f + it)) }
        assertEquals(1, executor.tasks.size)
        executor.runAll()
        assertEquals(listOf(1099f), renderer.spans)
        val result = worker.takeReady()!!
        assertEquals(1099f, result.request.span)
        worker.release(result)
        worker.request(request(1099f))
        assertTrue(executor.tasks.isEmpty())
        worker.close(); executor.runAll()
    }

    @Test fun requestArrivingDuringRenderDoesNotStarveCompletedFrames() {
        val executor = ManualExecutor()
        lateinit var worker: CometMaskWorker
        val completed = mutableListOf<Float>()
        val renderer = Renderer {
            if (it.span == 100f) {
                worker.request(request(101f))
                worker.request(request(102f))
            }
        }
        worker = CometMaskWorker(onReady = { source ->
            source.takeReady()?.let { completed.add(it.request.span); source.release(it) }
        }, executor = executor, rasterizerFactory = { renderer })
        worker.request(request()); executor.runAll()
        assertEquals(listOf(100f, 102f), completed)
        assertEquals(listOf(100f, 102f), renderer.spans)
        worker.close(); executor.runAll()
    }

    @Test fun heldOutputsAreNeverOverwrittenAndExhaustionDoesNotSpin() {
        val executor = ManualExecutor()
        val worker = CometMaskWorker(executor = executor, rasterizerFactory = { Renderer() })
        val held = (100..102).map {
            worker.request(request(it.toFloat())); executor.runAll(); worker.takeReady()!!
        }
        worker.request(request(103f)); executor.runAll()
        assertNull(worker.takeReady())
        assertTrue(executor.tasks.isEmpty())
        held.forEachIndexed { index, pixels -> assertEquals(100 + index, pixels.far!!.getInt(0)) }
        worker.release(held[0]); executor.runAll()
        val newest = worker.takeReady()!!
        assertEquals(103f, newest.request.span)
        assertEquals(101, held[1].far!!.getInt(0))
        worker.release(newest); worker.release(held[1]); worker.release(held[2])
        worker.close(); executor.runAll()
    }

    @Test fun closeDropsQueuedWorkAndDoesNotConstructRasterizer() {
        val executor = ManualExecutor()
        var constructed = false
        val worker = CometMaskWorker(executor = executor, rasterizerFactory = { constructed = true; Renderer() })
        worker.request(request()); worker.close(); executor.runAll()
        assertFalse(constructed)
        assertNull(worker.takeReady())
        worker.request(request(120f))
        assertTrue(executor.tasks.isEmpty())
        assertTrue(worker.isClosed)
    }

    @Test fun closeDuringNativeWorkNeverPublishesAndDisposesAfterWork() {
        val executor = ManualExecutor()
        lateinit var worker: CometMaskWorker
        var publications = 0
        val renderer = Renderer { worker.close(); assertEquals(0, rendererCloseCount) }
        worker = CometMaskWorker(onReady = { publications++ }, executor = executor, rasterizerFactory = {
            object : CometMaskRasterizer {
                override fun draw(request: CometMaskRequest, pixels: CometMaskPixels) = renderer.draw(request, pixels)
                override fun close() { rendererCloseCount++ }
            }
        })
        worker.request(request()); executor.runAll()
        assertEquals(0, publications)
        assertNull(worker.takeReady())
        assertEquals(1, rendererCloseCount)
    }
    private var rendererCloseCount = 0

    @Test fun failureDisablesRetriesSoUiCanUseExistingFallback() {
        val executor = ManualExecutor()
        val renderer = Renderer { throw IllegalStateException("native raster failure") }
        var publications = 0
        val worker = CometMaskWorker(onReady = { publications++ }, executor = executor, rasterizerFactory = { renderer })
        worker.request(request()); executor.runAll()
        assertTrue(worker.hasFailed)
        assertEquals(1, publications)
        assertEquals(1, renderer.closes)
        repeat(10) { worker.request(request(110f + it)) }
        assertTrue(executor.tasks.isEmpty())
        worker.close(); executor.runAll()
        assertEquals(1, renderer.closes)
    }

    @Test fun inabilityToStartExecutorEnablesFallback() {
        val executor = ManualExecutor().apply { shutdown() }
        var notices = 0
        val worker = CometMaskWorker(onReady = { notices++ }, executor = executor)
        worker.request(request())
        assertTrue(worker.hasFailed)
        assertEquals(1, notices)
        assertNull(worker.takeReady())
        worker.close()
    }

    @Test fun nativeThreadAllocationFailureDoesNotEscapeToUi() {
        val executor = object : ManualExecutor() {
            override fun execute(command: Runnable) { throw OutOfMemoryError("cannot start native thread") }
        }
        val worker = CometMaskWorker(executor = executor)
        worker.request(request())
        assertTrue(worker.hasFailed)
        worker.close()
        assertTrue(executor.isShutdown)
    }

    @Test fun neverUsedGpuPathDoesNotStartWorkerAtDisposal() {
        val executor = ManualExecutor()
        val worker = CometMaskWorker(executor = executor)
        worker.close()
        assertTrue(executor.tasks.isEmpty())
        assertTrue(executor.isShutdown)
    }

    @Test fun rasterizationUsesWorkerThreadAndTerminates() {
        val executor = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        val caller = Thread.currentThread()
        var renderedOn: Thread? = null
        val worker = CometMaskWorker(onReady = { ready.countDown() }, executor = executor,
            rasterizerFactory = { Renderer { renderedOn = Thread.currentThread() } })
        try {
            worker.request(request())
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertNotSame(caller, renderedOn)
        } finally { worker.close() }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test fun staleGeometryIsRejectedButOldSpanCanKeepRotating() {
        val original = request()
        assertTrue(original.sameGeometry(original.copy(span = 120f)))
        assertFalse(original.sameGeometry(original.copy(width = 20)))
        assertFalse(original.sameGeometry(original.copy(nearBlur = 3f)))
        assertFalse(original.sameGeometry(original.copy(farVisible = false)))
    }

    @Test fun concurrentProducerCannotChangeAnOutputOwnedByUi() {
        val executor = Executors.newSingleThreadExecutor()
        val first = CountDownLatch(1)
        val last = CountDownLatch(1)
        val worker = CometMaskWorker(onReady = { first.countDown() }, executor = executor,
            rasterizerFactory = { Renderer { if (it.span == 600f) last.countDown() } })
        try {
            worker.request(request())
            assertTrue(first.await(2, TimeUnit.SECONDS))
            val held = worker.takeReady()!!
            repeat(500) { worker.request(request(101f + it)) }
            worker.request(request(600f))
            assertTrue(last.await(2, TimeUnit.SECONDS))
            assertEquals(100, held.far!!.getInt(0))
            worker.release(held)
        } finally { worker.close() }
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }
}
