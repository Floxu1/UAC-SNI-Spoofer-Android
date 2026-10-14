package com.uacspoofer.mobile.auto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class XrayFlowState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

enum class XrayFlowStageId {
    OPERATOR, DPI, LIST, ECH, CLEAN_IP, BATCH, RANKING, CONNECT, RESCUE, DNS_ROUTING, FEATURES, SPEED_RANK, HEALTH,
}

data class XrayFlowStage(
    val id: XrayFlowStageId,
    val state: XrayFlowState = XrayFlowState.PENDING,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val detail: String = "",
    val facts: List<Pair<String, String>> = emptyList(),
) {
    fun elapsedMs(now: Long): Long = when {
        startedAt <= 0L -> 0L
        endedAt > 0L -> endedAt - startedAt
        state == XrayFlowState.RUNNING -> now - startedAt
        else -> 0L
    }
}

/** A failed attempt that branched to a retry (next config, rescue, failover). */
data class XrayFlowBranch(val title: String, val reason: String, val retry: String, val at: Long)

data class XrayFlowSnapshot(
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val stages: Map<XrayFlowStageId, XrayFlowStage> = XrayFlowStageId.entries.associateWith { XrayFlowStage(it) },
    val branches: List<XrayFlowBranch> = emptyList(),
    val percent: Int = 0,
    val phaseText: String = "",
    val bridge: String = "",
) {
    fun stage(id: XrayFlowStageId): XrayFlowStage = stages.getValue(id)
}

/** Event-driven view of the Xray (engine 1) connect path for «مسیر زنده». Nothing polls. */
object XrayLiveFlow {
    private val mutable = MutableStateFlow(XrayFlowSnapshot())
    val snapshot: StateFlow<XrayFlowSnapshot> = mutable.asStateFlow()

    val AUTO_STAGES = listOf(
        XrayFlowStageId.OPERATOR, XrayFlowStageId.DPI, XrayFlowStageId.LIST, XrayFlowStageId.ECH,
        XrayFlowStageId.CLEAN_IP, XrayFlowStageId.BATCH, XrayFlowStageId.RANKING,
    )

    fun reset(now: Long = System.currentTimeMillis()) {
        mutable.value = XrayFlowSnapshot(startedAt = now, phaseText = "")
    }

    /** A user config: the Auto discovery stages do not apply. */
    fun beginManual(name: String) {
        reset()
        AUTO_STAGES.forEach { skip(it, "کانفیگ خودت؛ این مرحله لازم نیست") }
        mutable.update { it.copy(bridge = name) }
    }

    fun start(id: XrayFlowStageId, detail: String = "", facts: List<Pair<String, String>> = emptyList()) =
        set(id) { it.copy(state = XrayFlowState.RUNNING, startedAt = now(), endedAt = 0L, detail = detail, facts = merge(it.facts, facts)) }

    fun done(id: XrayFlowStageId, detail: String? = null, facts: List<Pair<String, String>> = emptyList()) =
        set(id) {
            it.copy(
                state = XrayFlowState.DONE,
                startedAt = if (it.startedAt > 0L) it.startedAt else now(),
                endedAt = now(),
                detail = detail ?: it.detail,
                facts = merge(it.facts, facts),
            )
        }

    fun fail(id: XrayFlowStageId, detail: String) =
        set(id) { it.copy(state = XrayFlowState.FAILED, endedAt = now(), detail = detail.take(160)) }

    fun skip(id: XrayFlowStageId, detail: String) =
        set(id) { it.copy(state = XrayFlowState.SKIPPED, startedAt = 0L, endedAt = 0L, detail = detail) }

    fun fact(id: XrayFlowStageId, key: String, value: String) = set(id) { it.copy(facts = merge(it.facts, listOf(key to value))) }

    fun progress(done: Int, total: Int, text: String) =
        mutable.update { it.copy(percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0, phaseText = text) }

    fun branch(title: String, reason: String, retry: String) =
        mutable.update { it.copy(branches = (it.branches + XrayFlowBranch(title, reason.take(160), retry, now())).takeLast(12)) }

    fun failRunning(detail: String) = mutable.update { snap ->
        snap.copy(
            endedAt = now(),
            stages = snap.stages.mapValues { (_, s) ->
                if (s.state == XrayFlowState.RUNNING) s.copy(state = XrayFlowState.FAILED, endedAt = now(), detail = detail.take(160)) else s
            },
        )
    }

    fun finish(config: String) = mutable.update { it.copy(endedAt = now(), bridge = config) }

    fun isRunning(id: XrayFlowStageId) = snapshot.value.stage(id).state == XrayFlowState.RUNNING

    private inline fun set(id: XrayFlowStageId, crossinline block: (XrayFlowStage) -> XrayFlowStage) =
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