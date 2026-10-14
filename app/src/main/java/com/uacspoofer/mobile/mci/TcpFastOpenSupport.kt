package com.uacspoofer.mobile.mci

/**
 * Xray fails the dial when TCP_FASTOPEN_CONNECT cannot be set, so TFO is only emitted on Linux
 * kernels 4.11+ (where that option exists). Older kernels and non-Android test JVMs get nothing.
 */
internal object TcpFastOpenSupport {
    val kernelSupports: Boolean by lazy { supports(System.getProperty("os.name"), System.getProperty("os.version")) }

    fun field(enabled: Boolean): String = if (enabled && kernelSupports) "\"tcpFastOpen\":true," else ""

    internal fun supports(osName: String?, osVersion: String?): Boolean {
        if (osName?.contains("linux", ignoreCase = true) != true) return false
        val parts = osVersion.orEmpty().split('.', '-').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return false
        val minor = parts.getOrNull(1) ?: 0
        return major > 4 || (major == 4 && minor >= 11)
    }
}
