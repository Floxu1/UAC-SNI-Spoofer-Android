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

enum class PowStepState {
    RUNNING,
    DONE,
    FAILED,
}

data class PowStep(
    val text: String,
    val state: PowStepState,
)

data class PowUiStatus(
    val phase: PowPhase = PowPhase.IDLE,
    val progressPercent: Int = 0,
    val detail: String = "",
    val outerLabel: String = "",
    val exitRegion: String = "",
    val steps: List<PowStep> = emptyList(),
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
        val current = mutableStatus.value
        mutableStatus.value = PowUiStatus(
            phase = phase,
            progressPercent = progressPercent.coerceIn(0, 100),
            detail = detail,
            outerLabel = outerLabel,
            exitRegion = exitRegion,
            steps = current.steps,
        )
        if (detail.isNotBlank()) note(detail)
        if (phase == PowPhase.CONNECTED) finish(PowStepState.DONE)
        if (phase == PowPhase.FAILED) finish(PowStepState.FAILED)
    }

    fun pulse(text: String) {
        val clean = text.trim().take(180)
        if (clean.isEmpty()) return
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.state == PowStepState.RUNNING && last.text.startsWith("Scan ")) {
            if (last.text == clean) return
            steps[steps.lastIndex] = last.copy(text = clean)
            mutableStatus.value = current.copy(steps = steps)
            return
        }
        note(clean)
    }

    fun scan(text: String, outcome: String) {
        val clean = text.trim().take(180)
        if (clean.isEmpty()) return
        val state = when (outcome) {
            "trying" -> PowStepState.RUNNING
            "accepted" -> PowStepState.DONE
            else -> PowStepState.FAILED
        }
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.state == PowStepState.RUNNING && last.text.startsWith("Scan ")) {
            steps[steps.lastIndex] = PowStep(clean, state)
            mutableStatus.value = current.copy(steps = steps)
            return
        }
        if (last != null && last.state == PowStepState.RUNNING) {
            steps[steps.lastIndex] = last.copy(state = PowStepState.DONE)
        }
        steps += PowStep(clean, state)
        mutableStatus.value = current.copy(steps = steps.takeLast(24))
    }

    fun note(text: String, failed: Boolean = false) {
        val clean = text.trim().take(180)
        if (clean.isEmpty()) return
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.text == clean) return
        if (last != null && last.state == PowStepState.RUNNING) {
            steps[steps.lastIndex] = last.copy(state = PowStepState.DONE)
        }
        steps += PowStep(clean, if (failed) PowStepState.FAILED else PowStepState.RUNNING)
        mutableStatus.value = current.copy(steps = steps.takeLast(24))
    }

    private fun finish(state: PowStepState) {
        val current = mutableStatus.value
        val last = current.steps.lastOrNull() ?: return
        if (last.state != PowStepState.RUNNING) return
        val steps = current.steps.toMutableList()
        steps[steps.lastIndex] = last.copy(state = state)
        mutableStatus.value = current.copy(steps = steps)
    }

    fun reset() {
        mutableStatus.value = PowUiStatus.Idle
    }
}
