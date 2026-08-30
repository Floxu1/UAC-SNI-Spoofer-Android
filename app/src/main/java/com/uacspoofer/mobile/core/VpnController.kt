package com.uacspoofer.mobile.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.vpn.UacVpnService

object VpnController {
    fun start(context: Context) {
        val intent = Intent(context, UacVpnService::class.java).setAction(UacVpnService.ACTION_CONNECT)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, UacVpnService::class.java).setAction(UacVpnService.ACTION_DISCONNECT)
        context.startService(intent)
    }

    fun close(context: Context) {
        val intent = Intent(context, UacVpnService::class.java).setAction(UacVpnService.ACTION_CLOSE)
        context.startService(intent)
    }

    fun switchProfile(context: Context) {
        val intent = Intent(context, UacVpnService::class.java).setAction(UacVpnService.ACTION_SWITCH_PROFILE)
        context.startService(intent)
    }

    fun applyTorExit(context: Context) {
        val state = ConnectionStateStore.state.value
        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING) return
        val intent = Intent(context, UacVpnService::class.java).setAction(UacVpnService.ACTION_APPLY_TOR_EXIT)
        context.startService(intent)
    }
}
