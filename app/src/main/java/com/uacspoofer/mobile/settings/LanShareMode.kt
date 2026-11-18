package com.uacspoofer.mobile.settings

enum class LanShareMode(val id: String, val proxyType: String) {
    SOCKS5("socks5", "SOCKS5"),
    PLAYSTATION_5("ps5", "HTTP / CONNECT");

    companion object {
        fun fromId(value: String?): LanShareMode = entries.firstOrNull { it.id == value } ?: SOCKS5
    }
}
