package com.uacspoofer.mobile.engine.direct

import android.net.VpnService

internal object DirectNative {
    private val loadError = runCatching {
        System.loadLibrary("Direct")
        System.loadLibrary("direct_jni")
    }.exceptionOrNull()

    fun attach(service: VpnService) {
        check(loadError == null) { "UAC Direct native core failed to load: ${loadError?.message}" }
        nativeAttach(service)
    }

    fun detach() = nativeDetach()
    fun startProxy(config: String): Int = nativeStart(config)
    fun stop(): Int = nativeStop()
    fun isRunning(): Boolean = nativeIsRunning()
    fun isReady(): Boolean = nativeIsReady()
    fun lastError(): String = nativeLastError()
    fun lastLog(): String = nativeLastLog()

    @JvmStatic private external fun nativeAttach(service: VpnService)
    @JvmStatic private external fun nativeDetach()
    @JvmStatic private external fun nativeStart(config: String): Int
    @JvmStatic private external fun nativeStop(): Int
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeIsReady(): Boolean
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeLastLog(): String
}
