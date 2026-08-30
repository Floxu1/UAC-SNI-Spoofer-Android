package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.tor.TorPhase

internal object TorStatusCopy {
    private val batchPattern = Regex(
        """WebTunnel batch (\d+)/(\d+)\s*·\s*(\d+) bridge""",
    )

    fun bootstrapHint(percent: Int, persian: Boolean): String =
        if (persian) {
            "راه‌اندازی ${homeLtr("Tor $percent%")}"
        } else {
            "Tor bootstrap $percent%"
        }

    fun connectingHint(
        persian: Boolean,
        percent: Int,
        phase: TorPhase,
        detail: String,
        showRouteProgress: Boolean,
    ): String = when {
        percent in 1..99 -> bootstrapHint(percent, persian)
        persian -> persianDetail(detail, phase, showRouteProgress)
        else -> englishDetail(detail, phase, showRouteProgress)
    }

    fun errorHint(persian: Boolean, detail: String): String? {
        if (detail.isBlank()) return null
        return if (persian) persianDetail(detail, TorPhase.FAILED, showRouteProgress = false) else detail
    }

    private fun englishDetail(detail: String, phase: TorPhase, showRouteProgress: Boolean): String {
        val trimmed = detail.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return when {
            phase == TorPhase.BRIDGING -> "Applying WebTunnel bridge"
            showRouteProgress -> "Starting Tor / WebTunnel"
            else -> "Starting Tor / WebTunnel"
        }
    }

    private fun persianDetail(detail: String, phase: TorPhase, showRouteProgress: Boolean): String {
        val trimmed = detail.trim()
        batchPattern.find(trimmed)?.let { match ->
            val current = match.groupValues[1]
            val total = match.groupValues[2]
            val count = match.groupValues[3]
            return "دسته ${homeLtr("$current/$total")} · ${homeLtr(count)} بریج ${homeLtr("WebTunnel")}"
        }
        return when {
            trimmed.startsWith("Checking WebTunnel") ->
                "در حال بررسی بریج‌های ${homeLtr("WebTunnel")}"
            trimmed.startsWith("Applying WebTunnel") ->
                "در حال اعمال بریج ${homeLtr("WebTunnel")}"
            trimmed.startsWith("Starting Tor") ->
                "در حال شروع ${homeLtr("Tor / WebTunnel")}"
            trimmed.startsWith("Routing device") ->
                "در حال عبور ترافیک دستگاه از ${homeLtr("Tor")}"
            trimmed.startsWith("Bootstrapping Tor") ->
                bootstrapHint(percentFromDetail(trimmed), persian = true)
            trimmed.startsWith("Tor circuit is ready") || trimmed.startsWith("Tor / WebTunnel ready") ->
                "${homeLtr("Tor / WebTunnel")} آماده است"
            trimmed.startsWith("Reconnecting for ExitNodes") ->
                "اتصال مجدد برای کشور خروجی"
            trimmed.startsWith("Tor connect failed") || trimmed.startsWith("Tor reconnect failed") ->
                "اتصال ${homeLtr("Tor")} برقرار نشد"
            trimmed.isNotEmpty() -> homeLtr(trimmed)
            phase == TorPhase.BRIDGING -> "در حال اعمال بریج ${homeLtr("WebTunnel")}"
            showRouteProgress -> "در حال شروع ${homeLtr("Tor / WebTunnel")}"
            else -> "در حال شروع ${homeLtr("Tor / WebTunnel")}"
        }
    }

    private fun percentFromDetail(detail: String): Int =
        Regex("""(\d+)%""").find(detail)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
}
