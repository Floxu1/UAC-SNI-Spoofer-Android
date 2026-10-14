package com.uacspoofer.mobile.engine.tor

import java.io.File

internal object TorRcWriter {
    fun render(
        settings: TorEngineSettings,
        dataDirectory: File,
        webtunnelPlugin: File?,
        bridges: List<WebTunnelBridge>,
        pluginExecTokens: List<String>? = null,
        noticeLog: File? = null,
        geoIpFile: File? = null,
        geoIp6File: File? = null,
        fragmentProxyPort: Int = 0,
        hashedControlPassword: String = "",
    ): String {
        val validated = settings.validated()
        val execTokens = pluginExecTokens
            ?: listOfNotNull(webtunnelPlugin?.absolutePath).filter { it.isNotBlank() }
        if (bridges.isNotEmpty() && execTokens.any { it.isEmpty() || it.any(Char::isWhitespace) }) {
            error("WebTunnel plugin path cannot contain spaces: ${execTokens.joinToString(" ")}")
        }
        return buildString {
            appendLine("DataDirectory ${dataDirectory.absolutePath}")
            appendLine("RunAsDaemon 0")
            appendLine("ClientOnly 1")
            // Warm start: keep consensus / microdescs / guard state on disk.
            appendLine("AvoidDiskWrites 0")
            if (geoIpFile != null) {
                appendLine("GeoIPFile ${geoIpFile.absolutePath}")
            }
            if (geoIp6File != null) {
                appendLine("GeoIPv6File ${geoIp6File.absolutePath}")
            }
            appendLine("SocksPort 127.0.0.1:${validated.socksPort}" + if (validated.multiCircuit) " IsolateDestAddr" else "")
            appendLine("ControlPort 127.0.0.1:${validated.controlPort}")
            appendLine("CookieAuthentication 0")
            if (hashedControlPassword.isNotBlank()) {
                appendLine("HashedControlPassword $hashedControlPassword")
            }
            appendLine("SafeLogging 1")
            if (noticeLog != null) {
                appendLine("Log notice file ${noticeLog.absolutePath}")
            }
            appendLine("DormantCanceledByStartup 1")
            appendLine("DormantClientTimeout $DORMANT_MINUTES minutes")
            appendLine("LearnCircuitBuildTimeout 1")
            appendLine("CircuitBuildTimeout 30")
            appendLine("KeepalivePeriod 60")
            appendLine("ConfluxEnabled 1")
            if (validated.reducedPadding) {
                appendLine("ReducedConnectionPadding 1")
            }
            appendLine("FetchDirInfoEarly 1")
            appendLine("FetchDirInfoExtraEarly 1")
            appendLine("FetchUselessDescriptors 0")
            appendLine("DownloadExtraInfo 0")
            if (validated.fragmentEnabled) {
                appendLine(
                    "# uac-webtunnel-fragment mode=${validated.fragmentMode} packet=${validated.fragmentPacket} " +
                        "length=${validated.fragmentLength} delay=${validated.fragmentDelayMs} proxy=$fragmentProxyPort",
                )
            }
            exitLines(validated).forEach(::appendLine)
            if (bridges.isNotEmpty()) {
                val plugin = webtunnelPlugin
                    ?: error("WebTunnel plugin is required for configured bridges")
                val tokens = execTokens.ifEmpty { listOf(plugin.absolutePath) }
                appendLine("UseBridges 1")
                appendLine("UpdateBridgesFromAuthority 0")
                appendLine("AssumeReachable 1")
                appendLine("ClientUseIPv6 0")
                if (validated.dualGuards && bridges.size >= 2) {
                    appendLine("NumEntryGuards 2")
                    appendLine("NumPrimaryGuards 2")
                    appendLine("ConfluxClientUX throughput")
                }
                if (fragmentProxyPort > 0) {
                    // Reaches the PT as TOR_PT_PROXY; only the WebTunnel HTTPS hop goes through it.
                    appendLine("Socks5Proxy 127.0.0.1:$fragmentProxyPort")
                }
                appendLine("ClientTransportPlugin webtunnel exec ${tokens.joinToString(" ")}")
                bridges.forEach { bridge ->
                    appendLine(bridge.torrcLine())
                }
            }
        }
    }

    fun exitLines(settings: TorEngineSettings): List<String> {
        val exitCountry = settings.exitCountryCode
        return when {
            exitCountry.isNotEmpty() -> listOf(
                "ExitNodes {$exitCountry}",
                "StrictNodes ${if (settings.exitStrict) 1 else 0}",
            )
            settings.fastExit -> listOf(
                "ExitNodes ${TorExitCountry.FAST_EXIT_NODES}",
                "StrictNodes 0",
            )
            else -> emptyList()
        }
    }

    const val DORMANT_MINUTES = 20
}