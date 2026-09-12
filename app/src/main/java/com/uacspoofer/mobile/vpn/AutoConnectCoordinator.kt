package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.NetworkGuardStore

enum class AutoConnectOrigin {
    BOOT,
    APP_START,
    NETWORK,
    SETTING,
}

object AutoConnectCoordinator {
    @Volatile private var watching = false
    @Volatile private var appContext: Context? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val context = appContext ?: return
            tryStart(context, AutoConnectOrigin.NETWORK)
        }
    }

    fun ensureWatching(context: Context) {
        val app = context.applicationContext
        appContext = app
        NetworkGuardStore.get(app)
        if (watching) return
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, networkCallback) }
            .onSuccess { watching = true }
            .onFailure {
                AppLogRepository.warning(LogSource.SERVICE, "Auto-connect network watch failed", it)
            }
    }

    fun tryStart(context: Context, origin: AutoConnectOrigin): Boolean {
        val app = context.applicationContext
        appContext = app
        val guard = NetworkGuardStore.get(app)
        if (origin == AutoConnectOrigin.BOOT || origin == AutoConnectOrigin.SETTING) {
            NetworkGuardStore.clearUserStopped()
        }
        val respectStop = origin == AutoConnectOrigin.NETWORK || origin == AutoConnectOrigin.APP_START
        val proxyMode = AdvancedSettingsStore(app).snapshot().connectionMode == CONNECTION_MODE_PROXY
        val vpnPrepared = proxyMode || runCatching { VpnService.prepare(app) == null }.getOrDefault(false)
        if (
            !NetworkGuardPolicy.canAutoStart(
                autoConnect = guard.snapshot().autoConnect,
                state = ConnectionStateStore.state.value,
                userStopped = respectStop && NetworkGuardStore.userStoppedThisProcess,
                hasConsent = vpnPrepared,
            )
        ) {
            return false
        }
        if (!ConnectionStateStore.tryBeginConnect()) return false
        AppLogRepository.info(LogSource.SERVICE, "Auto-connect starting origin=${origin.name.lowercase()}")
        return runCatching {
            VpnController.start(app)
            true
        }.getOrElse {
            ConnectionStateStore.markError()
            AppLogRepository.error(LogSource.SERVICE, "Auto-connect start failed", it)
            false
        }
    }
}
