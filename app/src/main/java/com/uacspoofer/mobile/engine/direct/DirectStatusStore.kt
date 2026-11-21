package com.uacspoofer.mobile.engine.direct

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DirectPhase { IDLE, BUILDING, SCANNING, STARTING, CHECKING, CONNECTED, FAILED }

enum class DirectIpStage { HTTPS, WIREGUARD, HTTPS_FAILED, WIREGUARD_FAILED, SELECTED }

data class DirectIpCheck(
    val address: String,
    val fromRange: Boolean,
    val stage: DirectIpStage,
    val pingMs: Long? = null,
) {
    val finished: Boolean get() = stage != DirectIpStage.HTTPS && stage != DirectIpStage.WIREGUARD
}

data class DirectIpScan(
    val total: Int = 0,
    val checks: List<DirectIpCheck> = emptyList(),
    val sweepChecked: Int? = null,
) {
    val selectedPingMs: Long? get() = checks.firstOrNull { it.stage == DirectIpStage.SELECTED }?.pingMs

    companion object {
        private const val PREFIX = "Direct IP "

        internal fun selectedCarrierIp(log: String): String? = log.lineSequence()
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX).split(' ', limit = 4) }
            .lastOrNull { it.size >= 3 && it[2] == "selected" && (it[1] == "known" || it[1] == "range") }
            ?.first()
            ?.substringBeforeLast(':', "")
            ?.trim('[', ']')
            ?.takeIf(String::isNotEmpty)

        internal fun fromNativeLog(log: String): DirectIpScan? {
            var total = 0
            var sweepChecked: Int? = null
            val checks = linkedMapOf<String, DirectIpCheck>()
            for (line in log.lineSequence().filter { it.startsWith(PREFIX) }) {
                val payload = line.removePrefix(PREFIX)
                if (payload.startsWith("scan ")) {
                    total = payload.removePrefix("scan ").toIntOrNull() ?: 0
                    sweepChecked = null
                    checks.clear()
                    continue
                }
                if (payload.startsWith("sweep ") && total > 0) {
                    sweepChecked = payload.removePrefix("sweep ").toIntOrNull()?.coerceIn(0, total)
                    continue
                }
                val fields = payload.split(' ')
                if (total <= 0 || fields.size !in 3..4 || (fields[1] != "known" && fields[1] != "range")) continue
                val stage = when (fields[2]) {
                    "https" -> DirectIpStage.HTTPS
                    "wireguard" -> DirectIpStage.WIREGUARD
                    "https_failed" -> DirectIpStage.HTTPS_FAILED
                    "wireguard_failed" -> DirectIpStage.WIREGUARD_FAILED
                    "selected" -> DirectIpStage.SELECTED
                    else -> continue
                }
                val address = fields[0]
                val pingMs = fields.getOrNull(3)?.toLongOrNull()?.takeIf { stage == DirectIpStage.SELECTED && it >= 0 }
                checks[address] = DirectIpCheck(address, fields[1] == "range", stage, pingMs)
            }
            return if (total > 0) DirectIpScan(total, checks.values.toList(), sweepChecked) else null
        }
    }
}

data class DirectStatus(
    val phase: DirectPhase = DirectPhase.IDLE,
    val progressPercent: Int = 0,
    val detail: String = "",
    val startedAt: Long = 0L,
    val usingWireGuard: Boolean = false,
    val exitCountryCode: String = "",
    val ipScan: DirectIpScan? = null,
    val selectedDnsLabel: String? = null,
    val selectedDnsUrl: String? = null,
    val selectedDnsLatencyMs: Long? = null,
    val lastActivePhase: DirectPhase = DirectPhase.BUILDING,
) {
    val exitLookupKey: String
        get() = if (usingWireGuard) exitCountryCode.lowercase().ifBlank { "warp" } else "direct"
}

object DirectStatusStore {
    private val mutable = MutableStateFlow(DirectStatus())
    val status: StateFlow<DirectStatus> = mutable.asStateFlow()

    fun update(
        phase: DirectPhase,
        percent: Int,
        detail: String,
        usingWireGuard: Boolean = false,
        exitCountryCode: String = "",
    ) {
        mutable.update { previous ->
            previous.copy(
                phase = phase,
                progressPercent = percent.coerceIn(0, 100),
                detail = detail,
                startedAt = previous.startedAt.takeIf { it != 0L } ?: System.currentTimeMillis(),
                usingWireGuard = usingWireGuard,
                exitCountryCode = exitCountryCode,
                lastActivePhase = if (phase == DirectPhase.FAILED) previous.lastActivePhase else phase,
            )
        }
    }

    internal fun beginIpScan() = mutable.update { it.copy(ipScan = DirectIpScan()) }

    internal fun setDnsSelection(selection: DirectDnsSelection?) = mutable.update {
        it.copy(
            selectedDnsLabel = selection?.resolver?.label,
            selectedDnsUrl = selection?.resolver?.url,
            selectedDnsLatencyMs = selection?.latencyMs,
        )
    }

    internal fun updateIpScan(scan: DirectIpScan) = mutable.update {
        if (it.ipScan == null || (it.phase != DirectPhase.BUILDING && it.phase != DirectPhase.SCANNING)) it
        else it.copy(phase = DirectPhase.SCANNING, progressPercent = 25, ipScan = scan, lastActivePhase = DirectPhase.SCANNING)
    }

    fun reset() {
        mutable.value = DirectStatus()
    }
}
