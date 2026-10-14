package com.uacspoofer.mobile.auto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AutoStage(val key: String, val english: String, val persian: String) {
    OPERATOR("operator", "Operator", "اپراتور"),
    LIST("list", "List", "دریافت لیست"),
    CLEAN_IP("cleanip", "Clean IP", "IP تمیز"),
    TEST("test", "Test", "تست"),
    CONNECT("connect", "Connect", "اتصال"),
}

enum class AutoStageState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class AutoBootstrapSnapshot(
    val active: Boolean = false,
    val startedAt: Long = 0L,
    val states: Map<AutoStage, AutoStageState> = emptyMap(),
    val details: Map<AutoStage, String> = emptyMap(),
    val error: String = "",
) {
    fun state(stage: AutoStage): AutoStageState = states[stage] ?: AutoStageState.PENDING
}

/** Live Auto (engine 1) bootstrap progress for the compact connect card. */
object AutoBootstrapStore {
    private val mutable = MutableStateFlow(AutoBootstrapSnapshot())
    val snapshot: StateFlow<AutoBootstrapSnapshot> = mutable.asStateFlow()

    fun begin() {
        mutable.value = AutoBootstrapSnapshot(active = true, startedAt = System.currentTimeMillis())
    }

    fun stage(stage: AutoStage, state: AutoStageState, detail: String? = null) {
        mutable.update { s ->
            if (!s.active) return@update s
            s.copy(
                states = s.states + (stage to state),
                details = if (detail != null) s.details + (stage to detail) else s.details,
            )
        }
    }

    /** Marks the running stage (or [stage]) failed and keeps the card red with [message]. */
    fun fail(message: String, stage: AutoStage? = null) {
        mutable.update { s ->
            val target = stage ?: AutoStage.values().lastOrNull { s.state(it) == AutoStageState.RUNNING } ?: AutoStage.CONNECT
            s.copy(states = s.states + (target to AutoStageState.FAILED), error = message)
        }
    }

    fun reset() {
        mutable.value = AutoBootstrapSnapshot()
    }
}