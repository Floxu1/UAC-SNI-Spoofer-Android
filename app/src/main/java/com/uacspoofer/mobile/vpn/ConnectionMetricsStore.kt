package com.uacspoofer.mobile.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionMetrics(
    val latencyMs: Long? = null,
    val isMeasuringLatency: Boolean = false,
    val minimumLatencyMs: Long? = null,
    val maximumLatencyMs: Long? = null,
    val averageLatencyMs: Long? = null,
    val jitterMs: Long? = null,
    val sampleCount: Int = 0,
    val measuredAtMs: Long? = null,
)


object ConnectionMetricsStore {
    private val mutableMetrics = MutableStateFlow(ConnectionMetrics())
    val metrics: StateFlow<ConnectionMetrics> = mutableMetrics.asStateFlow()
    private val latencySamples = ArrayDeque<Long>()

    @Synchronized
    fun beginLatencyMeasurement() {
        latencySamples.clear()
        mutableMetrics.value = ConnectionMetrics(isMeasuringLatency = true)
    }

    @Synchronized
    fun addLatencySample(latencyMs: Long?) {
        if (latencyMs != null && latencyMs > 0L) {
            latencySamples.addLast(latencyMs)
            while (latencySamples.size > 5) latencySamples.removeFirst()
        }
    }

    @Synchronized
    fun finishLatencyMeasurement() {
        mutableMetrics.value = buildMetrics(isMeasuring = false)
    }

    @Synchronized
    fun updateLatency(latencyMs: Long?) {
        addLatencySample(latencyMs)
        if (!mutableMetrics.value.isMeasuringLatency) {
            mutableMetrics.value = buildMetrics(isMeasuring = false)
        }
    }

    @Synchronized
    fun reset() {
        latencySamples.clear()
        mutableMetrics.value = ConnectionMetrics()
    }

    private fun medianLatency(): Long? {
        val samples = latencySamples.sorted()
        if (samples.isEmpty()) return null
        return samples[(samples.size - 1) / 2]
    }

    private fun buildMetrics(isMeasuring: Boolean): ConnectionMetrics {
        val samples = latencySamples.toList()
        if (samples.isEmpty()) return ConnectionMetrics(isMeasuringLatency = isMeasuring)
        val jitter = samples
            .zipWithNext { previous, current -> kotlin.math.abs(current - previous) }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toLong()
            ?: 0L
        return ConnectionMetrics(
            latencyMs = medianLatency(),
            isMeasuringLatency = isMeasuring,
            minimumLatencyMs = samples.minOrNull(),
            maximumLatencyMs = samples.maxOrNull(),
            averageLatencyMs = samples.average().toLong(),
            jitterMs = jitter,
            sampleCount = samples.size,
            measuredAtMs = System.currentTimeMillis(),
        )
    }
}
