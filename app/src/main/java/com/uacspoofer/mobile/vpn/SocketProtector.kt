package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import java.net.Socket

fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

class VpnSocketProtector(
    private val service: VpnService,
) : SocketProtector {
    private val connectivityManager =
        service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun protect(socket: Socket): Boolean {
        if (!service.protect(socket)) return false
        val underlying = findUnderlyingNetwork()
            ?: error("No non-VPN underlying network available")
        underlying.bindSocket(socket)
        Log.i(TAG, "protected socket bound to underlying=$underlying")
        return true
    }

    private fun findUnderlyingNetwork(): Network? = connectivityManager.allNetworks.firstOrNull { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    companion object {
        private const val TAG = "UAC-MCI"
    }
}
