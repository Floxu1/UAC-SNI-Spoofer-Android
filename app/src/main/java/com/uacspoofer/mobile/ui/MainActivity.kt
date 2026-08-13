package com.uacspoofer.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.ui.theme.UacSniSpooferTheme
import com.uacspoofer.mobile.update.AppUpdateManager

class MainActivity : ComponentActivity() {
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK || VpnService.prepare(this) == null
        if (granted) {
            startVpnService()
        } else {
            ConnectionStateStore.markError()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        continueConnectionStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val state = ConnectionStateStore.state.collectAsStateWithLifecycle().value
            UacSniSpooferTheme {
                TvFocusProvider {
                    MainScreen(
                        state = state,
                        onConnect = ::beginConnect,
                        onDisconnect = ::beginDisconnect,
                        onSwitchProfile = ::beginProfileSwitch,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateManager.resumePendingInstall(this)
    }

    private fun beginConnect() {
        if (!ConnectionStateStore.tryBeginConnect()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueConnectionStart()
        }
    }

    private fun continueConnectionStart() {
        if (AdvancedSettingsStore(this).snapshot().connectionMode == CONNECTION_MODE_PROXY) {
            startVpnService()
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        try {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                startVpnService()
            } else {
                vpnPermissionLauncher.launch(prepareIntent)
            }
        } catch (_: Throwable) {
            ConnectionStateStore.markError()
        }
    }

    private fun startVpnService() {
        try {
            VpnController.start(this)
        } catch (_: Throwable) {
            ConnectionStateStore.markError()
        }
    }

    private fun beginDisconnect() {
        if (!ConnectionStateStore.tryBeginDisconnect()) return
        try {
            VpnController.stop(this)
        } catch (_: Throwable) {
            ConnectionStateStore.markDisconnected()
        }
    }

    private fun beginProfileSwitch() {
        try {
            VpnController.switchProfile(this)
        } catch (_: Throwable) {
            ConnectionStateStore.markError()
        }
    }
}
