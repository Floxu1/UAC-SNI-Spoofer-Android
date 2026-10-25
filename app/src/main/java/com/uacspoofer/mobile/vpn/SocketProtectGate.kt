package com.uacspoofer.mobile.vpn

import android.content.Context

/**
 * Central gate for [android.net.VpnService.protect].
 * User-facing toggle removed; always ON so real devices keep hairpin
 * protection and Engine-3 OOB protect wiring stays healthy.
 */
object SocketProtectGate {
    const val PREF_KEY = "socket_protect_enabled"

    @Suppress("UNUSED_PARAMETER")
    fun isEnabled(context: Context): Boolean = true
}
