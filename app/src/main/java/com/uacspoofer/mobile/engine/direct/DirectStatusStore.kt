package com.uacspoofer.mobile.engine.direct

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DirectPhase { IDLE, BUILDING, STARTING, CHECKING, CONNECTED, FAILED }

data class DirectStatus(
    val phase: DirectPhase = DirectPhase.IDLE,
    val progressPercent: Int = 0,
    val detail: String = "",
    val startedAt: Long = 0L,
)

object DirectStatusStore {
    private val mutable = MutableStateFlow(DirectStatus())
    val status: StateFlow<DirectStatus> = mutable.asStateFlow()

    fun update(phase: DirectPhase, percent: Int, detail: String) {
        val prev = mutable.value
        val started = if (prev.phase == DirectPhase.IDLE || prev.startedAt == 0L) System.currentTimeMillis() else prev.startedAt
        mutable.value = DirectStatus(phase, percent.coerceIn(0, 100), detail, started)
    }

    fun reset() {
        mutable.value = DirectStatus()
    }
}
