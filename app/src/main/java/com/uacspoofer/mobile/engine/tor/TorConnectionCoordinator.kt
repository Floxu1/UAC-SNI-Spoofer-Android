package com.uacspoofer.mobile.engine.tor

import android.content.Context
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver
import com.uacspoofer.mobile.vpn.TunStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

class TorConnectionCoordinator(
    context: Context,
    private val daemon: TorDaemon,
    private val engineStore: TorEngineStore,
    private val fingerprintResolver: NetworkFingerprintResolver,
) {
    private val appContext = context.applicationContext
    private val tunRelay = TorTunRelay(appContext)
    @Volatile private var ownsTunRelay = false
    @Volatile private var lastBridges: List<WebTunnelBridge> = emptyList()

    suspend fun connect(
        settings: AdvancedSettingsData,
        establishTun: (AdvancedSettingsData) -> ParcelFileDescriptor?,
    ) {
        val torSettings = engineStore.snapshot()
        val networkKey = runCatching { fingerprintResolver.captureAdaptive().learningKey() }
            .getOrDefault("unknown")
        val bridges = orderedBridges(torSettings, networkKey)
        if (bridges.isEmpty()) {
            error("No WebTunnel bridges available. Paste webtunnel lines in Connection engine.")
        }
        val winner = bootstrap(torSettings, bridges)
        if (settings.connectionMode != CONNECTION_MODE_PROXY) {
            TorStatusStore.update(TorPhase.BRIDGING, 100, "Routing device traffic through Tor")
            tunRelay.start(
                yaml = TorTunRelayConfig.yaml(
                    mtu = settings.tunMtu,
                    socksPort = torSettings.socksPort,
                    tunIpv4 = settings.tunAddress,
                ),
                establishTun = { establishTun(settings) },
            )
            ownsTunRelay = true
        }
        winner?.let { engineStore.saveLastGoodBridge(networkKey, it.raw) }
        val readyDetail = if (settings.connectionMode == CONNECTION_MODE_PROXY) {
            "Tor / WebTunnel ready · Proxy SOCKS 127.0.0.1:${torSettings.socksPort}"
        } else {
            "Tor / WebTunnel ready · Tunnel VPN through Tor"
        }
        TorStatusStore.update(TorPhase.CONNECTED, 100, readyDetail)
        AppLogRepository.info(
            LogSource.TOR,
            "Tor engine connected mode=${settings.connectionMode} " +
                "bridge=${winner?.endpoint ?: "batch"} fragment=${torSettings.fragmentEnabled}",
        )
    }

    fun isDaemonRunning(): Boolean = daemon.isRunning()

    fun isRelayRunning(): Boolean = tunRelay.isRunning()

    fun tunStats(): TunStats = tunRelay.stats()

    suspend fun applyExitCountry(settings: TorEngineSettings) = withContext(Dispatchers.IO) {
        check(daemon.isRunning()) { "Tor is not running" }
        val validated = liveExitSettings(settings)
        val country = validated.exitCountryCode.ifBlank { "auto" }
        if (daemon.applyExitCountry(validated)) {
            AppLogRepository.info(
                LogSource.TOR,
                "Applied ExitNodes={$country} strict=${validated.exitStrict} via control port",
            )
            return@withContext
        }
        AppLogRepository.warning(
            LogSource.TOR,
            "Control-port ExitNodes={$country} failed; restarting Tor with pinned torrc",
        )
        restartDaemon(validated)
        AppLogRepository.info(LogSource.TOR, "Restarted Tor with ExitNodes={$country} strict=${validated.exitStrict}")
    }

    suspend fun rebuildForExitCountry(settings: TorEngineSettings) = withContext(Dispatchers.IO) {
        val validated = liveExitSettings(settings)
        AppLogRepository.warning(
            LogSource.TOR,
            "Rebuilding Tor circuits for ExitNodes={${validated.exitCountryCode.ifBlank { "auto" }}}",
        )
        restartDaemon(validated)
    }

    private fun liveExitSettings(settings: TorEngineSettings): TorEngineSettings {
        val validated = settings.validated()
        return if (validated.exitCountryCode.isNotEmpty()) {
            validated.copy(exitStrict = true)
        } else {
            validated
        }
    }

    private suspend fun restartDaemon(settings: TorEngineSettings) {
        val bridges = lastBridges.ifEmpty {
            val networkKey = runCatching { fingerprintResolver.captureAdaptive().learningKey() }
                .getOrDefault("unknown")
            orderedBridges(settings, networkKey)
        }
        check(bridges.isNotEmpty()) { "No WebTunnel bridges to restart Tor" }
        daemon.start(settings, bridges, TorDaemon.BRIDGE_BOOTSTRAP_TIMEOUT_MS)
        lastBridges = bridges
    }

    fun stop() {
        if (ownsTunRelay) {
            runCatching { tunRelay.stop() }
            ownsTunRelay = false
        }
        daemon.stop()
    }

    private suspend fun bootstrap(
        torSettings: TorEngineSettings,
        bridges: List<WebTunnelBridge>,
    ): WebTunnelBridge? {
        TorStatusStore.update(TorPhase.BRIDGING, 0, "Checking WebTunnel bridges")
        AppLogRepository.info(LogSource.TOR, "WebTunnel candidates=${bridges.size}")
        val ranked = WebTunnelHandshake.rankLive(bridges)
        val batches = ranked.chunked(WebTunnelHandshake.MAX_LAUNCH).take(MAX_BATCHES)
        var lastError: Throwable? = null
        for ((index, batch) in batches.withIndex()) {
            TorStatusStore.update(
                TorPhase.BRIDGING,
                0,
                "WebTunnel batch ${index + 1}/${batches.size} · ${batch.size} bridge(s)",
            )
            AppLogRepository.info(
                LogSource.TOR,
                "Starting Tor with ${batch.size} WebTunnel bridge(s) batch=${index + 1}/${batches.size}",
            )
            try {
                daemon.start(torSettings, batch, TorDaemon.BRIDGE_BOOTSTRAP_TIMEOUT_MS)
                lastBridges = batch
                return batch.first()
            } catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                lastError = error
                AppLogRepository.warning(
                    LogSource.TOR,
                    "WebTunnel batch ${index + 1} failed: ${error.message.orEmpty()}",
                )
                runCatching { daemon.stop() }
            }
        }
        throw IllegalStateException(
            lastError?.message?.ifBlank { null }
                ?: "None of the WebTunnel bridges bootstrapped",
            lastError,
        )
    }

    private fun orderedBridges(settings: TorEngineSettings, networkKey: String): List<WebTunnelBridge> {
        val bundled = WebTunnelBridgeCatalog.loadBundled(appContext)
        AppLogRepository.info(
            LogSource.TOR,
            "WebTunnel pool user=${WebTunnelBridgeParser.parseAll(settings.bridgeLines).size} " +
                "bundled=${bundled.size}",
        )
        return WebTunnelBridgeCatalog.merge(
            userLines = settings.bridgeLines,
            lastGoodRaw = engineStore.lastGoodBridge(networkKey),
            bundled = bundled,
        )
    }

    private companion object {
        const val MAX_BATCHES = 3
    }
}
