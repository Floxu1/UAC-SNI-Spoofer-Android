package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.pow.PowPhase

internal object PowStatusCopy {
    fun connectingHint(
        persian: Boolean,
        percent: Int,
        phase: PowPhase,
        detail: String,
        showRouteProgress: Boolean,
    ): String {
        val trimmed = detail.trim()
        if (persian) return persianDetail(trimmed, phase, percent, showRouteProgress)
        if (trimmed.isNotEmpty()) return trimmed
        return when (phase) {
            PowPhase.OUTER -> "Connecting WARP outer leg"
            PowPhase.INNER -> "Starting Psiphon through WARP"
            PowPhase.BRIDGING -> "Routing device traffic through UAC PoW"
            else -> if (showRouteProgress) "Starting UAC PoW" else "Starting UAC PoW"
        }
    }

    fun errorHint(persian: Boolean, detail: String): String? {
        if (detail.isBlank()) return null
        return if (persian) persianDetail(detail, PowPhase.FAILED, 0, false) else detail
    }

    private fun persianDetail(
        detail: String,
        phase: PowPhase,
        percent: Int,
        showRouteProgress: Boolean,
    ): String {
        val trimmed = detail.trim()
        return when {
            trimmed.startsWith("Connecting MASQUE") -> "در حال اتصال لایه ${homeLtr("MASQUE")}"
            trimmed.startsWith("Connecting WireGuard") -> "در حال اتصال لایه ${homeLtr("WireGuard")}"
            trimmed.startsWith("Connecting WoW") -> "در حال اتصال لایه ${homeLtr("WoW")}"
            trimmed.startsWith("Starting Psiphon") -> "در حال شروع ${homeLtr("Psiphon")} روی ${homeLtr("WARP")}"
            trimmed.startsWith("Trying") -> "در حال تلاش برای مسیر بعدی"
            trimmed.startsWith("Routing device") -> "در حال عبور ترافیک دستگاه از ${homeLtr("UAC PoW")}"
            trimmed.startsWith("UAC PoW ready") -> "${homeLtr("UAC PoW")} آماده است"
            trimmed.startsWith("Starting UAC PoW") -> "در حال شروع ${homeLtr("UAC PoW")}"
            trimmed.startsWith("Creating device") -> "در حال ساخت رابط ${homeLtr("VPN")}"
            trimmed.startsWith("Reconnecting") -> "اتصال مجدد برای کشور خروجی"
            trimmed.isNotEmpty() -> homeLtr(trimmed)
            phase == PowPhase.OUTER -> "در حال اتصال لایه ${homeLtr("WARP")}"
            phase == PowPhase.INNER -> "در حال شروع ${homeLtr("Psiphon")}"
            percent in 1..99 -> "راه‌اندازی ${homeLtr("UAC PoW $percent%")}"
            showRouteProgress -> "در حال شروع ${homeLtr("UAC PoW")}"
            else -> "در حال شروع ${homeLtr("UAC PoW")}"
        }
    }
}
