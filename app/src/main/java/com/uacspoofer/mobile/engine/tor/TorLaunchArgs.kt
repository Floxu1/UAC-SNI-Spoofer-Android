package com.uacspoofer.mobile.engine.tor

import java.io.File

internal object TorLaunchArgs {
    const val SYSLOG_TAG = "UacTor"
    const val CONTROL_SOCKET_NAME = "ControlSocket"

    fun commandLine(
        torrc: File,
        defaultsTorrc: File,
        dataDir: File,
        cacheDir: File,
        controlSocket: File,
        verifyConfig: Boolean = false,
    ): Array<String> {
        val args = buildList {
            add("tor")
            if (verifyConfig) add("--verify-config")
            add("--RunAsDaemon")
            add("0")
            add("-f")
            add(torrc.absolutePath)
            add("--defaults-torrc")
            add(defaultsTorrc.absolutePath)
            add("--ignore-missing-torrc")
            add("--SyslogIdentityTag")
            add(SYSLOG_TAG)
            add("--CacheDirectory")
            add(cacheDir.absolutePath)
            add("--DataDirectory")
            add(dataDir.absolutePath)
            add("--ControlSocket")
            add(controlSocket.absolutePath)
            add("--CookieAuthentication")
            add("0")
            add("--LogMessageDomains")
            add("1")
            add("--TruncateLogFile")
            add("1")
        }
        check(args.none { it.isEmpty() || it.any(Char::isWhitespace) }) {
            "Tor argv cannot contain spaces: ${args.joinToString(" ")}"
        }
        return args.toTypedArray()
    }
}
