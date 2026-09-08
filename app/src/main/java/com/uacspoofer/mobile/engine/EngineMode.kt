package com.uacspoofer.mobile.engine

import com.uacspoofer.mobile.core.ConnectionState

enum class EngineMode(val id: String) {
    XRAY_CF("xray_cf"),
    TOR_WEBTUNNEL("tor_webtunnel"),
    UAC_POW("uac_pow");

    val isTor: Boolean get() = this == TOR_WEBTUNNEL
    val isXray: Boolean get() = this == XRAY_CF
    val isPow: Boolean get() = this == UAC_POW

    fun next(): EngineMode = when (this) {
        XRAY_CF -> TOR_WEBTUNNEL
        TOR_WEBTUNNEL -> UAC_POW
        UAC_POW -> XRAY_CF
    }

    fun toggled(): EngineMode = next()

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
