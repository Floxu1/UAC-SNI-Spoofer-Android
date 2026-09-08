package com.uacspoofer.mobile.engine.pow

import android.os.Build
import java.io.File

object GoJniLoader {
    const val XRAY_LIBRARY = "gojni"
    const val POW_LIBRARY = "gopsi"
    const val POW_PROCESS_SUFFIX = ":uacpow"

    @Volatile
    private var forcedLibrary: String? = null

    @JvmStatic
    fun forcePowLibrary() {
        forcedLibrary = POW_LIBRARY
    }

    @JvmStatic
    fun load() {
        System.loadLibrary(forcedLibrary ?: if (isPowProcess()) POW_LIBRARY else XRAY_LIBRARY)
    }

    @JvmStatic
    fun isPowProcess(): Boolean = isPowProcessName(processName())

    @JvmStatic
    fun isPowProcessName(name: String): Boolean = name.endsWith(POW_PROCESS_SUFFIX)

    private fun processName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { return android.app.Application.getProcessName() }
        }
        return runCatching {
            File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        }.getOrDefault("").trim { it <= ' ' || it == '\u0000' }
    }
}
