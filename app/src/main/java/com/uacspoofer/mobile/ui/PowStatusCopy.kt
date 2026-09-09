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
            PowPhase.OUTER -> "Connecting..."
            PowPhase.INNER -> "Starting secure tunnel..."
            PowPhase.BRIDGING -> "Almost ready..."
            else -> "Connecting UAC PoW..."
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
            trimmed.startsWith("Connecting MASQUE") -> "دارم به ${homeLtr("MASQUE")} وصل میشم"
            trimmed.startsWith("Connecting WireGuard") -> "دارم به ${homeLtr("WireGuard")} وصل میشم"
            trimmed.startsWith("Connecting WoW") -> "دارم به ${homeLtr("WoW")} وصل میشم"
            trimmed.startsWith("Starting Psiphon") -> "دارم تونل امن رو روشن می‌کنم"
            trimmed.startsWith("Trying") -> "نشد، دارم راه بعدی رو امتحان می‌کنم"
            trimmed.startsWith("Routing device") -> "دارم اینترنت گوشیت رو وصل می‌کنم"
            trimmed.startsWith("UAC PoW ready") -> "${homeLtr("UAC PoW")} وصله، برو حالشو ببر"
            trimmed.startsWith("Starting UAC PoW") -> "دارم ${homeLtr("UAC PoW")} رو روشن می‌کنم"
            trimmed.startsWith("Creating device") -> "دارم ${homeLtr("VPN")} گوشیت رو می‌سازم"
            trimmed.startsWith("Reconnecting") -> "دارم دوباره وصل میشم"
            trimmed.isNotEmpty() -> homeLtr(trimmed)
            phase == PowPhase.OUTER -> "دارم وصل میشم..."
            phase == PowPhase.INNER -> "یه لحظه، دارم تونل رو راه میندازم..."
            percent in 1..99 -> "${homeLtr("UAC PoW")} $percent% ..."
            showRouteProgress -> "دارم ${homeLtr("UAC PoW")} رو روشن می‌کنم..."
            else -> "دارم ${homeLtr("UAC PoW")} رو روشن می‌کنم..."
        }
    }
}
