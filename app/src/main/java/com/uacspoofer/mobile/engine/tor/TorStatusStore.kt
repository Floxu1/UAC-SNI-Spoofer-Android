package com.uacspoofer.mobile.engine.tor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TorPhase {
    IDLE,
    STARTING,
    BOOTSTRAPPING,
    BRIDGING,
    CONNECTED,
    FAILED,
}

data class TorUiStatus(
    val phase: TorPhase = TorPhase.IDLE,
    val bootstrapPercent: Int = 0,
    val detail: String = "",
) {
    val isActive: Boolean get() = phase != TorPhase.IDLE

    companion object {
        val Idle = TorUiStatus()
    }
}

object TorStatusStore {
    private val mutableStatus = MutableStateFlow(TorUiStatus.Idle)
    val status: StateFlow<TorUiStatus> = mutableStatus.asStateFlow()

    fun update(phase: TorPhase, bootstrapPercent: Int = 0, detail: String = "") {
        mutableStatus.value = TorUiStatus(
            phase = phase,
            bootstrapPercent = bootstrapPercent.coerceIn(0, 100),
            detail = detail,
        )
    }

    fun reset() {
        mutableStatus.value = TorUiStatus.Idle
    }
}
