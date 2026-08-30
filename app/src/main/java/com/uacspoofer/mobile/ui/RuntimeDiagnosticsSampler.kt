package com.uacspoofer.mobile.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.uacspoofer.mobile.logging.AppLogRepository
import kotlin.math.abs

internal data class RuntimeSnapshot(
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val nativeUsedBytes: Long,
    val pssBytes: Long,
    val deviceAvailBytes: Long,
    val cpuPercent: Float,
    val cores: Int,
    val suspects: List<RankedMemorySuspect> = emptyList(),
    val primarySuspectId: String? = null,
)

internal enum class LeakSignal {
    STABLE,
    WATCHING,
    JAVA_GROWTH,
    NATIVE_GROWTH,
    BOTH,
}

internal class RuntimeDiagnosticsSampler(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val javaHistory = ArrayDeque<Long>()
    private val nativeHistory = ArrayDeque<Long>()
    private val bucketHistory = HashMap<String, ArrayDeque<Long>>()
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()

    fun capture(): RuntimeSnapshot {
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val nativeUsed = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val device = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(device)
        val nowCpu = Process.getElapsedCpuTime()
        val nowWall = SystemClock.elapsedRealtime()
        val deltaCpu = (nowCpu - lastCpuMs).coerceAtLeast(0L)
        val deltaWall = (nowWall - lastWallMs).coerceAtLeast(1L)
        lastCpuMs = nowCpu
        lastWallMs = nowWall
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuPercent = ((deltaCpu.toFloat() / deltaWall.toFloat()) * 100f).coerceIn(0f, cores * 100f)
        remember(javaUsed, nativeUsed)
        val buckets = collectBuckets(memoryInfo)
        buckets.forEach { rememberBucket(it.id, it.bytes) }
        val slopes = bucketHistory.mapValues { slopeMbPerMin(it.value) }
        val ready = javaHistory.size >= MIN_SAMPLES
        val suspects = ProcMemoryMaps.rankSuspects(
            latest = buckets,
            slopes = slopes,
            minSamplesReady = ready,
            leakSlope = PART_LEAK_SLOPE_MB_PER_MIN,
        )
        return RuntimeSnapshot(
            javaUsedBytes = javaUsed,
            javaMaxBytes = runtime.maxMemory(),
            nativeUsedBytes = nativeUsed,
            pssBytes = memoryInfo.totalPss.toLong() * 1024L,
            deviceAvailBytes = device.availMem,
            cpuPercent = cpuPercent,
            cores = cores,
            suspects = suspects.take(8),
            primarySuspectId = suspects.firstOrNull { it.growing }?.id ?: suspects.firstOrNull()?.id,
        )
    }

    fun leakSignal(): LeakSignal {
        val javaSlope = slopeMbPerMin(javaHistory)
        val nativeSlope = slopeMbPerMin(nativeHistory)
        val javaLeak = javaHistory.size >= MIN_SAMPLES && javaSlope > LEAK_SLOPE_MB_PER_MIN
        val nativeLeak = nativeHistory.size >= MIN_SAMPLES && nativeSlope > LEAK_SLOPE_MB_PER_MIN
        return when {
            javaLeak && nativeLeak -> LeakSignal.BOTH
            javaLeak -> LeakSignal.JAVA_GROWTH
            nativeLeak -> LeakSignal.NATIVE_GROWTH
            javaHistory.size < MIN_SAMPLES -> LeakSignal.WATCHING
            else -> LeakSignal.STABLE
        }
    }

    fun javaHistoryMb(): List<Float> = javaHistory.map { bytesToMb(it) }

    fun requestGc() {
        System.gc()
        Runtime.getRuntime().gc()
    }

    private fun remember(javaUsed: Long, nativeUsed: Long) {
        javaHistory.addLast(javaUsed)
        nativeHistory.addLast(nativeUsed)
        while (javaHistory.size > HISTORY) javaHistory.removeFirst()
        while (nativeHistory.size > HISTORY) nativeHistory.removeFirst()
    }

    private fun rememberBucket(id: String, bytes: Long) {
        val history = bucketHistory.getOrPut(id) { ArrayDeque() }
        history.addLast(bytes)
        while (history.size > HISTORY) history.removeFirst()
    }

    private fun collectBuckets(memoryInfo: Debug.MemoryInfo): List<MemoryBucket> {
        val merged = LinkedHashMap<String, Long>()
        fun add(id: String, bytes: Long) {
            if (bytes <= 0L) return
            merged[id] = (merged[id] ?: 0L) + bytes
        }
        memoryStats(memoryInfo).forEach { add(it.id, it.bytes) }
        ProcMemoryMaps.readPssBuckets().forEach { bucket ->
            if (bucket.id == "art" && merged.containsKey("java_heap")) return@forEach
            add(bucket.id, bucket.bytes)
        }
        add("logs", AppLogRepository.estimatedBytes())
        val soLibs = merged.filterKeys { it.startsWith("so:") }.toList().sortedByDescending { it.second }
        soLibs.drop(3).forEach { merged.remove(it.first) }
        val leftoverSo = soLibs.drop(3).sumOf { it.second }
        if (leftoverSo > 0L) add("other", leftoverSo)
        return merged.map { MemoryBucket(it.key, it.value) }
    }

    private fun memoryStats(info: Debug.MemoryInfo): List<MemoryBucket> {
        fun kb(key: String, fallbackKb: Int): Long {
            val parsed = info.getMemoryStat(key)?.toLongOrNull()
            return ((parsed ?: fallbackKb.toLong()) * 1024L).coerceAtLeast(0L)
        }
        return listOf(
            MemoryBucket("java_heap", kb("summary.java-heap", info.dalvikPss)),
            MemoryBucket("native_heap", kb("summary.native-heap", info.nativePss)),
            MemoryBucket("graphics", kb("summary.graphics", 0)),
            MemoryBucket("code", kb("summary.code", 0)),
            MemoryBucket("stack", kb("summary.stack", 0)),
            MemoryBucket("private_other", kb("summary.private-other", info.otherPss)),
        )
    }

    companion object {
        private const val HISTORY = 90
        private const val MIN_SAMPLES = 24
        internal const val LEAK_SLOPE_MB_PER_MIN = 2.5f
        internal const val PART_LEAK_SLOPE_MB_PER_MIN = 1.2f

        fun bytesToMb(bytes: Long): Float = bytes / (1024f * 1024f)

        fun formatMb(bytes: Long): String = "%.1f MB".format(bytesToMb(bytes))

        internal fun slopeMbPerMin(history: Collection<Long>): Float {
            val values = history.map { bytesToMb(it) }
            if (values.size < MIN_SAMPLES) return 0f
            val n = values.size.toFloat()
            var sumX = 0f
            var sumY = 0f
            var sumXY = 0f
            var sumXX = 0f
            values.forEachIndexed { index, y ->
                val x = index.toFloat()
                sumX += x
                sumY += y
                sumXY += x * y
                sumXX += x * x
            }
            val denom = n * sumXX - sumX * sumX
            if (abs(denom) < 0.0001f) return 0f
            val slopePerSample = (n * sumXY - sumX * sumY) / denom
            return slopePerSample * (60_000f / SAMPLE_INTERVAL_MS)
        }

        const val SAMPLE_INTERVAL_MS = 750L
    }
}
