package com.uacspoofer.mobile.engine.pow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PowPhase {
    IDLE,
    STARTING,
    OUTER,
    INNER,
    BRIDGING,
    CONNECTED,
    FAILED,
}

data class PowUiStatus(
    val phase: PowPhase = PowPhase.IDLE,
    val progressPercent: Int = 0,
    val detail: String = "",
    val outerLabel: String = "",
    val exitRegion: String = "",
) {
    companion object {
        val Idle = PowUiStatus()
    }
}

object PowStatusStore {
    private val mutableStatus = MutableStateFlow(PowUiStatus.Idle)
    val status: StateFlow<PowUiStatus> = mutableStatus.asStateFlow()

    fun update(
        phase: PowPhase,
        progressPercent: Int = 0,
        detail: String = "",
        outerLabel: String = mutableStatus.value.outerLabel,
        exitRegion: String = mutableStatus.value.exitRegion,
    ) {
        mutableStatus.value = PowUiStatus(
            phase = phase,
            progressPercent = progressPercent.coerceIn(0, 100),
            detail = detail,
            outerLabel = outerLabel,
            exitRegion = exitRegion,
        )
    }

    fun reset() {
        mutableStatus.value = PowUiStatus.Idle
    }
}
