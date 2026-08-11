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

    fun switchProfile(context: Context) {
        val intent = Intent(context, UacVpnService::class.java).setAction(UacVpnService.ACTION_SWITCH_PROFILE)
        context.startService(intent)
    }
}
