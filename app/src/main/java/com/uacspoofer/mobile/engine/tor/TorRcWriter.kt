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
            appendLine("AvoidDiskWrites 1")
            if (geoIpFile != null) {
                appendLine("GeoIPFile ${geoIpFile.absolutePath}")
            }
            if (geoIp6File != null) {
                appendLine("GeoIPv6File ${geoIp6File.absolutePath}")
            }
            appendLine("SocksPort 127.0.0.1:${validated.socksPort}")
            appendLine("ControlPort 127.0.0.1:${validated.controlPort}")
            appendLine("CookieAuthentication 0")
            appendLine("SafeLogging 1")
            if (noticeLog != null) {
                appendLine("Log notice file ${noticeLog.absolutePath}")
            }
            appendLine("DormantCanceledByStartup 1")
            appendLine("LearnCircuitBuildTimeout 0")
            appendLine("CircuitBuildTimeout 20")
            appendLine("FetchDirInfoEarly 1")
            appendLine("FetchDirInfoExtraEarly 1")
            appendLine("FetchUselessDescriptors 0")
            appendLine("DownloadExtraInfo 0")
            if (validated.fragmentEnabled) {
                appendLine(
                    "# uac-webtunnel-fragment packet=${validated.fragmentPacket} " +
                        "length=${validated.fragmentLength} delay=${validated.fragmentDelayMs}",
                )
            }
            val exitCountry = validated.exitCountryCode
            if (exitCountry.isNotEmpty()) {
                appendLine("ExitNodes {$exitCountry}")
                appendLine("StrictNodes ${if (validated.exitStrict) 1 else 0}")
            }
            if (bridges.isNotEmpty()) {
                val plugin = webtunnelPlugin
                    ?: error("WebTunnel plugin is required for configured bridges")
                val tokens = execTokens.ifEmpty { listOf(plugin.absolutePath) }
                appendLine("UseBridges 1")
                appendLine("UpdateBridgesFromAuthority 0")
                appendLine("AssumeReachable 1")
                appendLine("ClientUseIPv6 0")
                appendLine("ClientTransportPlugin webtunnel exec ${tokens.joinToString(" ")}")
                bridges.forEach { bridge ->
                    appendLine(bridge.torrcLine())
                }
            }
        }
    }
}
