package com.uacspoofer.mobile.engine.pow

import android.net.VpnService
import org.json.JSONObject

object AetherNative {
    @Volatile
    private var loadError: String? = null

    init {
        loadError = runCatching {
            System.loadLibrary("aether")
            System.loadLibrary("aether_jni")
            null
        }.exceptionOrNull()?.message
    }

    val available: Boolean get() = loadError == null

    val unavailableReason: String
        get() = loadError ?: "UAC PoW native libraries are not packaged for this ABI"

    data class TunnelAddresses(
        val ipv4: String,
        val ipv6: String,
        val gatewayProxy: String = "",
        val organization: String = "",
    )

    fun prepare(config: String): TunnelAddresses {
        checkAvailable()
        check(nativePrepare(config) == 0) { nativeLastError().ifBlank { "prepare failed" } }
        val result = JSONObject(nativeLastResult())
        return TunnelAddresses(
            result.getString("ipv4"),
            result.optString("ipv6"),
            result.optString("gateway_proxy"),
            result.optString("organization"),
        )
    }

    fun startProxy(config: String): Int {
        checkAvailable()
        return nativeStartProxy(config)
    }

    fun stop(): Int {
        if (!available) return 0
        return nativeStop()
    }

    fun isRunning(): Boolean = available && nativeIsRunning()

    fun isReady(): Boolean = available && nativeIsReady()

    fun lastError(): String = if (available) nativeLastError() else unavailableReason

    fun attach(service: VpnService) {
        checkAvailable()
        nativeAttach(service)
    }

    fun detach() {
        if (!available) return
        nativeDetach()
    }

    private fun checkAvailable() {
        check(available) { unavailableReason }
    }

    @JvmStatic private external fun nativePrepare(config: String): Int
    @JvmStatic private external fun nativeLastResult(): String
    @JvmStatic private external fun nativeStartProxy(config: String): Int
    @JvmStatic private external fun nativeStop(): Int
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeIsReady(): Boolean
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeAttach(service: VpnService)
    @JvmStatic private external fun nativeDetach()
}
