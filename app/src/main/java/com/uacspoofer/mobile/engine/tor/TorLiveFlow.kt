package com.uacspoofer.mobile.engine.tor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TorFlowState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

enum class TorFlowStageId {
    ARCH, POOL, SCAN, BATCH, FRAGMENT, PT, HANDSHAKE, DIRECTORY, BOOTSTRAP, CIRCUITS, TUN, THROUGHPUT, WATCHDOG,
}

data class TorFlowStage(
    val id: TorFlowStageId,
    val state: TorFlowState = TorFlowState.PENDING,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val detail: String = "",
    val facts: List<Pair<String, String>> = emptyList(),
) {
    fun elapsedMs(now: Long): Long = when {
        startedAt <= 0L -> 0L
        endedAt > 0L -> endedAt - startedAt
        state == TorFlowState.RUNNING -> now - startedAt
        else -> 0L
    }
}

/** A failed attempt that branched to a retry (next batch, or same batch without fragment). */
data class TorFlowBranch(val title: String, val reason: String, val retry: String, val at: Long)

data class TorFlowSnapshot(
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val stages: Map<TorFlowStageId, TorFlowStage> = TorFlowStageId.entries.associateWith { TorFlowStage(it) },
    val branches: List<TorFlowBranch> = emptyList(),
    val percent: Int = 0,
    val phaseText: String = "",
    val bridge: String = "",
) {
    fun stage(id: TorFlowStageId): TorFlowStage = stages.getValue(id)
}

/**
 * Live, event-driven view of the Tor connect process for the «مسیر زنده» overlay.
 * Updated only at points the engine already passes through; nothing polls.
 */
object TorLiveFlow {
    private val mutable = MutableStateFlow(TorFlowSnapshot())
    val snapshot: StateFlow<TorFlowSnapshot> = mutable.asStateFlow()

    fun reset(now: Long = System.currentTimeMillis()) {
        mutable.value = TorFlowSnapshot(startedAt = now)
    }

    fun start(id: TorFlowStageId, detail: String = "", facts: List<Pair<String, String>> = emptyList()) =
        set(id) { it.copy(state = TorFlowState.RUNNING, startedAt = now(), endedAt = 0L, detail = detail, facts = merge(it.facts, facts)) }

    fun done(id: TorFlowStageId, detail: String? = null, facts: List<Pair<String, String>> = emptyList()) =
        set(id) {
            it.copy(
                state = TorFlowState.DONE,
                startedAt = if (it.startedAt > 0L) it.startedAt else now(),
                endedAt = now(),
                detail = detail ?: it.detail,
                facts = merge(it.facts, facts),
            )
        }

    fun fail(id: TorFlowStageId, detail: String) =
        set(id) { it.copy(state = TorFlowState.FAILED, endedAt = now(), detail = detail) }

    fun skip(id: TorFlowStageId, detail: String) =
        set(id) { it.copy(state = TorFlowState.SKIPPED, startedAt = 0L, endedAt = 0L, detail = detail) }

    fun fact(id: TorFlowStageId, key: String, value: String) = set(id) { it.copy(facts = merge(it.facts, listOf(key to value))) }

    fun branch(title: String, reason: String, retry: String) =
        mutable.update { it.copy(branches = it.branches + TorFlowBranch(title, reason.take(160), retry, now())) }

    /** Restart the per-attempt stages for a new batch / retry. */
    fun newAttempt() = mutable.update { snap ->
        val reset = listOf(TorFlowStageId.PT, TorFlowStageId.HANDSHAKE, TorFlowStageId.DIRECTORY, TorFlowStageId.BOOTSTRAP)
        snap.copy(stages = snap.stages + reset.associateWith { TorFlowStage(it) }, percent = 0)
    }

    /** Called from the existing bootstrap watcher on every new percentage. */
    fun onBootstrap(percent: Int, phase: String) {
        mutable.update { it.copy(percent = percent, phaseText = phase.ifBlank { it.phaseText }) }
        val ptState = snapshot.value.stage(TorFlowStageId.PT).state
        if (percent < 2 && ptState == TorFlowState.PENDING) start(TorFlowStageId.PT, "webtunnel")
        if (percent >= 2) {
            if (ptState != TorFlowState.DONE) done(TorFlowStageId.PT, "connected to pluggable transport")
            startIfPending(TorFlowStageId.HANDSHAKE)
            startIfPending(TorFlowStageId.BOOTSTRAP)
        }
        if (percent >= 15) {
            doneIfNot(TorFlowStageId.HANDSHAKE)
            startIfPending(TorFlowStageId.DIRECTORY)
        }
        if (percent >= 75) doneIfNot(TorFlowStageId.DIRECTORY)
        if (percent >= 100) doneIfNot(TorFlowStageId.BOOTSTRAP)
    }

    fun failRunning(detail: String) = mutable.update { snap ->
        snap.copy(
            stages = snap.stages.mapValues { (_, s) ->
                if (s.state == TorFlowState.RUNNING) s.copy(state = TorFlowState.FAILED, endedAt = now(), detail = detail.take(160)) else s
            },
        )
    }

    fun finish(bridge: String) = mutable.update { it.copy(endedAt = now(), bridge = bridge) }

    fun phaseOf(line: String): String =
        Regex("""Bootstrapped \d+% \([^)]*\): (.*)""").find(line)?.groupValues?.get(1)?.trim().orEmpty()

    private fun startIfPending(id: TorFlowStageId) {
        if (snapshot.value.stage(id).state == TorFlowState.PENDING) start(id)
    }

    private fun doneIfNot(id: TorFlowStageId) {
        val state = snapshot.value.stage(id).state
        if (state != TorFlowState.DONE) done(id)
    }

    private inline fun set(id: TorFlowStageId, crossinline block: (TorFlowStage) -> TorFlowStage) =
        mutable.update { snap -> snap.copy(stages = snap.stages + (id to block(snap.stage(id)))) }

    private fun merge(old: List<Pair<String, String>>, new: List<Pair<String, String>>): List<Pair<String, String>> {
        if (new.isEmpty()) return old
        val map = LinkedHashMap<String, String>()
        old.forEach { map[it.first] = it.second }
        new.forEach { map[it.first] = it.second }
        return map.entries.map { it.key to it.value }
    }

    private fun now() = System.currentTimeMillis()
}