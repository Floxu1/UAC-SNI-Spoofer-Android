package com.uacspoofer.mobile.engine

import com.uacspoofer.mobile.core.ConnectionState

enum class EngineMode(val id: String) {
    XRAY_CF("xray_cf"),
    TOR_WEBTUNNEL("tor_webtunnel");

    val isTor: Boolean get() = this == TOR_WEBTUNNEL
    val isXray: Boolean get() = this == XRAY_CF

    fun toggled(): EngineMode = if (isTor) XRAY_CF else TOR_WEBTUNNEL

    companion object {
        fun fromStored(raw: String?): EngineMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: XRAY_CF
    }
}

enum class EngineModeChangeResult {
    APPLIED,
    BLOCKED_WHILE_ACTIVE,
}

fun canChangeEngineMode(state: ConnectionState): Boolean =
    state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR
