package com.uacspoofer.mobile.vpn

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectRescuePhase {
    HIDDEN,
    COLLECTING,
    PREFLIGHT,
    SCREENING,
    SELECTING,
    RETRYING,
    SUCCEEDED,
    FAILED,
}

data class ConnectRescueSnapshot(
    val phase: ConnectRescuePhase = ConnectRescuePhase.HIDDEN,
    val generation: Long = 0L,
    val completed: Int = 0,
    val total: Int = 0,
    val healthy: Int = 0,
    val currentTarget: String = "",
    val foundCount: Int = 0,
    val retryIndex: Int = 0,
    val retryTotal: Int = 0,
) {
    val visible: Boolean get() = phase != ConnectRescuePhase.HIDDEN

    val stepNumber: Int get() = when (phase) {
        ConnectRescuePhase.HIDDEN -> 0
        ConnectRescuePhase.COLLECTING -> 1
        ConnectRescuePhase.PREFLIGHT -> 2
        ConnectRescuePhase.SCREENING -> 3
        ConnectRescuePhase.SELECTING -> 4
        ConnectRescuePhase.RETRYING,
        ConnectRescuePhase.SUCCEEDED,
        ConnectRescuePhase.FAILED,
        -> 5
    }

    val overallProgress: Float
        get() {
            if (phase == ConnectRescuePhase.SUCCEEDED) return 1f
            val stepProgress = when {
                phase == ConnectRescuePhase.RETRYING && retryTotal > 0 ->
                    retryIndex.toFloat() / retryTotal.toFloat()
                total > 0 -> completed.toFloat() / total.toFloat()
                else -> 0f
            }.coerceIn(0f, 1f)
            val step = stepNumber.coerceIn(1, TOTAL_STEPS)
            return (((step - 1) + stepProgress) / TOTAL_STEPS.toFloat()).coerceIn(0f, 1f)
        }

    companion object {
        const val TOTAL_STEPS = 5
        val Hidden = ConnectRescueSnapshot()
    }
}

object ConnectRescueStore {
    private val generation = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(ConnectRescueSnapshot.Hidden)
    val snapshot: StateFlow<ConnectRescueSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun begin(): Long {
        val next = generation.incrementAndGet()
        mutableSnapshot.value = ConnectRescueSnapshot(
            phase = ConnectRescuePhase.COLLECTING,
            generation = next,
        )
        return next
    }

    @Synchronized
    fun update(generation: Long, transform: (ConnectRescueSnapshot) -> ConnectRescueSnapshot) {
        val current = mutableSnapshot.value
        if (current.generation != generation || current.phase == ConnectRescuePhase.HIDDEN) return
        mutableSnapshot.value = transform(current).copy(generation = generation)
    }

    @Synchronized
    fun hide() {
        mutableSnapshot.value = ConnectRescueSnapshot.Hidden
    }

    @Synchronized
    fun hideIf(generation: Long) {
        if (mutableSnapshot.value.generation == generation) hide()
    }
}
