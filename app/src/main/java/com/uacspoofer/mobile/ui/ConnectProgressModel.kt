package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.auto.AutoBootstrapSnapshot
import com.uacspoofer.mobile.auto.AutoStage
import com.uacspoofer.mobile.auto.AutoStageState
import com.uacspoofer.mobile.engine.pow.PowPhase
import com.uacspoofer.mobile.engine.direct.DirectPhase
import com.uacspoofer.mobile.engine.tor.TorFlowSnapshot
import com.uacspoofer.mobile.engine.tor.TorFlowStageId
import com.uacspoofer.mobile.engine.tor.TorFlowState

enum class MiniStepState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class MiniStep(
    val key: String,
    val english: String,
    val persian: String,
    val state: MiniStepState,
    val detail: String = "",
)

/** Pure mapping of engine live state into the compact connect stepper (unit-tested). */
object ConnectProgressModel {

    private data class Group(val key: String, val english: String, val persian: String, val ids: List<TorFlowStageId>)

    private val torGroups = listOf(
        Group("bridges", "Bridges", "پل‌ها", listOf(TorFlowStageId.ARCH, TorFlowStageId.POOL, TorFlowStageId.SCAN, TorFlowStageId.BATCH)),
        Group("transport", "Transport", "انتقال", listOf(TorFlowStageId.FRAGMENT, TorFlowStageId.PT, TorFlowStageId.HANDSHAKE)),
        Group("directory", "Directory", "دایرکتوری", listOf(TorFlowStageId.DIRECTORY, TorFlowStageId.BOOTSTRAP)),
        Group("circuits", "Circuits", "مدارها", listOf(TorFlowStageId.CIRCUITS)),
        Group("tunnel", "Tunnel", "تونل", listOf(TorFlowStageId.TUN)),
    )

    fun combine(states: List<TorFlowState>): MiniStepState = when {
        states.isEmpty() -> MiniStepState.PENDING
        states.any { it == TorFlowState.RUNNING } -> MiniStepState.RUNNING
        states.any { it == TorFlowState.FAILED } -> MiniStepState.FAILED
        states.all { it == TorFlowState.SKIPPED } -> MiniStepState.SKIPPED
        states.all { it == TorFlowState.DONE || it == TorFlowState.SKIPPED } -> MiniStepState.DONE
        states.any { it == TorFlowState.DONE } -> MiniStepState.RUNNING
        else -> MiniStepState.PENDING
    }

    fun torSteps(snap: TorFlowSnapshot): List<MiniStep> {
        val raw = torGroups.map { g ->
            val stages = g.ids.map { snap.stage(it) }
            val state = combine(stages.map { it.state })
            val detail = (stages.lastOrNull { it.state == TorFlowState.FAILED }
                ?: stages.lastOrNull { it.state == TorFlowState.RUNNING }
                ?: stages.lastOrNull { it.state == TorFlowState.DONE })?.detail.orEmpty()
            MiniStep(g.key, g.english, g.persian, state, detail)
        }
        // Partial group (some done, rest pending) counts as done once a later group has started.
        val lastStarted = raw.indexOfLast { it.state != MiniStepState.PENDING && it.state != MiniStepState.SKIPPED }
        return raw.mapIndexed { i, s ->
            if (s.state == MiniStepState.RUNNING && i < lastStarted &&
                torGroups[i].ids.all { snap.stage(it).state != TorFlowState.RUNNING }
            ) s.copy(state = MiniStepState.DONE) else s
        }
    }

    private val powOrder = listOf(PowPhase.STARTING, PowPhase.OUTER, PowPhase.INNER, PowPhase.BRIDGING)
    private val powLabels = listOf(
        Triple("start", "Start", "شروع"),
        Triple("outer", "Outer", "لایه بیرونی"),
        Triple("inner", "Inner", "لایه درونی"),
        Triple("bridge", "Bridge", "پل"),
    )

    /** [lastActive] is the last non-failed phase seen, so a failure can be pinned to its stage. */
    fun powSteps(phase: PowPhase, lastActive: PowPhase): List<MiniStep> {
        val failed = phase == PowPhase.FAILED
        val pos = when (val p = if (failed) lastActive else phase) {
            PowPhase.CONNECTED -> powOrder.size
            PowPhase.IDLE, PowPhase.FAILED -> 0
            else -> powOrder.indexOf(p).coerceAtLeast(0)
        }
        return powLabels.mapIndexed { i, (k, en, fa) ->
            val st = when {
                i < pos -> MiniStepState.DONE
                i == pos && failed -> MiniStepState.FAILED
                i == pos -> MiniStepState.RUNNING
                else -> MiniStepState.PENDING
            }
            MiniStep(k, en, fa, st)
        }
    }

    /** Engine 1 Auto bootstrap stages (operator, list, clean IP, test, connect). */
    fun autoSteps(snap: AutoBootstrapSnapshot): List<MiniStep> = AutoStage.values().map { stage ->
        val state = when (snap.state(stage)) {
            AutoStageState.PENDING -> MiniStepState.PENDING
            AutoStageState.RUNNING -> MiniStepState.RUNNING
            AutoStageState.DONE -> MiniStepState.DONE
            AutoStageState.FAILED -> MiniStepState.FAILED
            AutoStageState.SKIPPED -> MiniStepState.SKIPPED
        }
        MiniStep(stage.key, stage.english, stage.persian, state, snap.details[stage].orEmpty())
    }

    private val directOrder = listOf(DirectPhase.BUILDING, DirectPhase.STARTING, DirectPhase.CHECKING)
    private val directLabels = listOf(
        Triple("config", "Config", "کانفیگ"),
        Triple("core", "Core", "هسته"),
        Triple("check", "Check", "بررسی"),
    )

    /** Engine 4 (UAC Direct) steps. */
    fun directSteps(phase: DirectPhase, lastActive: DirectPhase): List<MiniStep> {
        val failed = phase == DirectPhase.FAILED
        val pos = when (val p = if (failed) lastActive else phase) {
            DirectPhase.CONNECTED -> directOrder.size
            DirectPhase.IDLE, DirectPhase.FAILED -> 0
            else -> directOrder.indexOf(p).coerceAtLeast(0)
        }
        return directLabels.mapIndexed { i, (k, en, fa) ->
            val st = when {
                i < pos -> MiniStepState.DONE
                i == pos && failed -> MiniStepState.FAILED
                i == pos -> MiniStepState.RUNNING
                else -> MiniStepState.PENDING
            }
            MiniStep(k, en, fa, st)
        }
    }

    /** Index of the step the card highlights. */
    fun focusIndex(steps: List<MiniStep>): Int {
        if (steps.isEmpty()) return -1
        steps.indexOfFirst { it.state == MiniStepState.FAILED }.let { if (it >= 0) return it }
        steps.indexOfLast { it.state == MiniStepState.RUNNING }.let { if (it >= 0) return it }
        steps.indexOfFirst { it.state == MiniStepState.PENDING }.let { if (it >= 0) return it }
        return steps.lastIndex
    }

    fun progress(steps: List<MiniStep>): Float {
        if (steps.isEmpty()) return 0f
        val done = steps.count { it.state == MiniStepState.DONE || it.state == MiniStepState.SKIPPED }
        val running = if (steps.any { it.state == MiniStepState.RUNNING }) 0.5f else 0f
        return ((done + running) / steps.size).coerceIn(0f, 1f)
    }

    fun formatElapsed(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return "%d:%02d".format(s / 60, s % 60)
    }
}