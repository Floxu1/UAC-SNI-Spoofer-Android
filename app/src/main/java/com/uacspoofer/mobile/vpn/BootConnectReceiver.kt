package com.uacspoofer.mobile.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return
        AutoConnectCoordinator.ensureWatching(context)
        AutoConnectCoordinator.tryStart(context, AutoConnectOrigin.BOOT)
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
