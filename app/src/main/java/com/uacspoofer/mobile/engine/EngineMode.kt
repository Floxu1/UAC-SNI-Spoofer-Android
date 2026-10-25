package com.uacspoofer.mobile.engine

import com.uacspoofer.mobile.core.ConnectionState

enum class EngineMode(val id: String) {
    XRAY_CF("xray_cf"),
    TOR_WEBTUNNEL("tor_webtunnel"),
    UAC_POW("uac_pow"),
    /** Engine 4: UAC Direct, serverless direct DPI bypass on the bundled Xray core. */
    UAC_DIRECT("uac_direct");

    val isTor: Boolean get() = this == TOR_WEBTUNNEL
    val isXray: Boolean get() = this == XRAY_CF
    val isPow: Boolean get() = this == UAC_POW
    val isDirect: Boolean get() = this == UAC_DIRECT

    fun next(engine4Unlocked: Boolean = true): EngineMode = when (this) {
        XRAY_CF -> TOR_WEBTUNNEL
        TOR_WEBTUNNEL -> UAC_POW
        UAC_POW -> if (engine4Unlocked) UAC_DIRECT else XRAY_CF
        UAC_DIRECT -> XRAY_CF
    }

    fun toggled(engine4Unlocked: Boolean = true): EngineMode = next(engine4Unlocked)

    companion object {
        /** Engines shown in the home switch rail. Direct only when unlocked. */
        fun visible(engine4Unlocked: Boolean): List<EngineMode> =
            if (engine4Unlocked) entries.toList() else entries.filter { !it.isDirect }

        fun fromStored(raw: String?): EngineMode {
            val key = raw?.trim().orEmpty()
            // Old uncommitted engine-4 id from the ACT WIP; keep the user's engine-4 selection.
            if (key.equals("act_hitch", ignoreCase = true)) return UAC_DIRECT
            return entries.firstOrNull { it.id.equals(key, ignoreCase = true) } ?: XRAY_CF
        }
    }
}

enum class EngineModeChangeResult {
    APPLIED,
    BLOCKED_WHILE_ACTIVE,
}

fun canChangeEngineMode(state: ConnectionState): Boolean =
    state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR
