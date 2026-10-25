package com.uacspoofer.mobile.engine.tor

import android.content.Context
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.DomesticDirect
import com.uacspoofer.mobile.mci.DomesticSocksSplit
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver
import com.uacspoofer.mobile.vpn.TunStats
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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
    private val scoreStore = TorBridgeScoreStore(appContext)
    @Volatile private var ownsTunRelay = false
    @Volatile private var lastBridges: List<WebTunnelBridge> = emptyList()
    @Volatile private var fragmentProxy: TorFragmentProxy? = null
    @Volatile private var sessionNetworkKey: String = ""
    @Volatile private var watchdogFailures = 0
    @Volatile private var sessionBridge: WebTunnelBridge? = null
    @Volatile private var throughputMeasured = false
    @Volatile private var sessionFingerprint: com.uacspoofer.mobile.vpn.NetworkFingerprint? = null
    @Volatile private var pendingNetChange = false
    @Volatile private var fastRelaysApplied = false
    @Volatile private var lastTrafficRead = 0L
    @Volatile private var lastTrafficAt = 0L
    @Volatile private var slowTicks = 0
    @Volatile private var lastSlowSwapAt = 0L
    @Volatile private var connectedAt = 0L
    @Volatile private var lastTunBytes = -1L

    suspend fun connect(
        settings: AdvancedSettingsData,
        establishTun: (AdvancedSettingsData) -> ParcelFileDescriptor?,
    ) {
        TorLiveFlow.reset()
        val torSettings = engineStore.snapshot()
        val networkKey = currentNetworkKey()
        sessionNetworkKey = networkKey
        sessionFingerprint = runCatching { fingerprintResolver.captureAdaptive() }.getOrNull()
        pendingNetChange = false
        fastRelaysApplied = false
        lastTrafficRead = 0L
        lastTrafficAt = 0L
        slowTicks = 0
        watchdogFailures = 0
        connectedAt = 0L
        lastTunBytes = -1L
        TorLiveFlow.start(TorFlowStageId.POOL)
        val bridges = orderedBridges(torSettings, networkKey, publish = true)
        if (bridges.isEmpty()) {
            error("No WebTunnel bridges available. Paste webtunnel lines in Connection engine.")
        }
        val startedAt = System.currentTimeMillis()
        val winner = try {
            bootstrap(torSettings, bridges, networkKey)
        } catch (error: Throwable) {
            TorLiveFlow.failRunning(error.message.orEmpty())
            TorLiveFlow.finish("")
            throw error
        }
        val bootstrapMs = System.currentTimeMillis() - startedAt
        TorLiveFlow.done(
            TorFlowStageId.CIRCUITS,
            winner?.label ?: "unknown bridge",
            listOf(
                "guards" to if (torSettings.dualGuards && lastBridges.size >= 2) "2 (conflux throughput)" else "1",
                "conflux" to "on",
                "bridge" to (winner?.label ?: "?"),
                "exit" to exitLabel(torSettings),
            ),
        )
        DomesticDirect.ensure(appContext)
        val iranDirect = NetworkGuardStore.get(appContext).snapshot().iranDirect
        DomesticDirect.applyEnabled(iranDirect)
        check(DomesticSocksSplit.start(torSettings.socksPort, torSettings.daemonSocksPort)) {
            "DomesticSocksSplit failed on ${torSettings.socksPort} -> ${torSettings.daemonSocksPort}"
        }
        if (settings.connectionMode != CONNECTION_MODE_PROXY) {
            TorStatusStore.update(TorPhase.BRIDGING, 100, "Routing device traffic through Tor")
            TorLiveFlow.start(TorFlowStageId.TUN)
            tunRelay.start(
                yaml = TorTunRelayConfig.yaml(
                    mtu = torSettings.effectiveMtu(settings.tunMtu),
                    socksPort = torSettings.socksPort,
                    tunIpv4 = settings.tunAddress,
                    blockUdp = torSettings.blockUdp,
                    bigBuffers = torSettings.bigBuffers,
                ),
                establishTun = { establishTun(settings) },
            )
            ownsTunRelay = true
            TorLiveFlow.done(
                TorFlowStageId.TUN,
                "tun2socks",
                listOf(
                    "MTU" to torSettings.effectiveMtu(settings.tunMtu).toString(),
                    "buffers" to if (torSettings.bigBuffers) "256 KB" else "64 KB",
                    "UDP" to if (torSettings.blockUdp) "blocked (1 s)" else "open",
                ),
            )
        } else {
            TorLiveFlow.skip(TorFlowStageId.TUN, "Proxy mode: SOCKS 127.0.0.1:${torSettings.socksPort}")
        }
        TorLiveFlow.finish(winner?.label.orEmpty())
        if (torSettings.throughputScoring) {
            TorLiveFlow.fact(TorFlowStageId.THROUGHPUT, "when", "first watchdog tick, screen on")
        } else {
            TorLiveFlow.skip(TorFlowStageId.THROUGHPUT, "off")
        }
        if (torSettings.watchdog) {
            TorLiveFlow.start(TorFlowStageId.WATCHDOG, "every 60 s, screen on")
        } else {
            TorLiveFlow.skip(TorFlowStageId.WATCHDOG, "off")
        }
        sessionBridge = winner
        throughputMeasured = false
        connectedAt = System.currentTimeMillis()
        lastTunBytes = -1L
        if (winner != null) {
            engineStore.saveLastGoodBridge(networkKey, winner.raw)
            scoreStore.recordSuccess(networkKey, winner)
        }
        val fragmented = fragmentProxy != null
        engineStore.saveLastConnect(
            TorLastConnect(
                atMillis = System.currentTimeMillis(),
                bridgeEndpoint = winner?.label.orEmpty(),
                bootstrapMs = bootstrapMs,
                fragment = fragmented,
                warm = !daemon.lastStartWasCold,
            ),
        )
        val readyDetail = if (settings.connectionMode == CONNECTION_MODE_PROXY) {
            "Tor / WebTunnel ready · Proxy SOCKS 127.0.0.1:${torSettings.socksPort}"
        } else {
            "Tor / WebTunnel ready · Tunnel VPN through Tor"
        }
        TorStatusStore.update(TorPhase.CONNECTED, 100, readyDetail)
        AppLogRepository.info(
            LogSource.TOR,
            "Tor engine connected mode=${settings.connectionMode} bridge=${winner?.label ?: "unknown"} " +
                "bootstrap=${bootstrapMs}ms warm=${!daemon.lastStartWasCold} fragment=$fragmented",
        )
    }

    fun isDaemonRunning(): Boolean = daemon.isRunning()

    fun isRelayRunning(): Boolean = tunRelay.isRunning()

    fun tunStats(): TunStats = tunRelay.stats()

    suspend fun applyExitCountry(settings: TorEngineSettings) = withContext(Dispatchers.IO) {
        check(daemon.isRunning()) { "Tor is not running" }
        val validated = liveExitSettings(settings)
        val country = exitLabel(validated)
        if (daemon.applyExitCountry(validated)) {
            AppLogRepository.info(
                LogSource.TOR,
                "Applied ExitNodes={$country} via control port (no restart)",
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
        AppLogRepository.warning(LogSource.TOR, "Rebuilding Tor circuits for ExitNodes={${exitLabel(validated)}}")
        restartDaemon(validated)
    }

    /**
     * Light watchdog, called about every 60 s by the service. Screen off: nothing at all
     * (Tor goes dormant on its own). Network change: SIGNAL ACTIVE + NEWNYM, no restart.
     * Returns false only after repeated failed SOCKS checks.
     */
    suspend fun watchdogTick(screenOn: Boolean): Boolean = withContext(Dispatchers.IO) {
        val settings = engineStore.snapshot()
        if (!settings.watchdog || !screenOn || !daemon.isRunning()) return@withContext true
        checkNetworkChange()
        val inGrace = connectedAt == 0L || System.currentTimeMillis() - connectedAt < WATCHDOG_GRACE_MS
        val tunFlowing = tunTrafficGrew()
        val ok = socksCheck(settings.socksPort)
        TorLiveFlow.fact(
            TorFlowStageId.WATCHDOG,
            "last check",
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + if (ok) " ok" else " failed",
        )
        if (ok) {
            watchdogFailures = 0
            if (!inGrace) maybeApplyFastRelays(settings)
            maybeMeasureThroughput(settings)
            if (!inGrace) maybeSwapSlowCircuit(settings)
            maybeRefreshBridges(settings)
            return@withContext true
        }
        if (inGrace || tunFlowing) {
            // Fresh circuits or user traffic still moving: a single failed probe is not an outage.
            AppLogRepository.info(
                LogSource.TOR,
                "Tor watchdog probe failed but ignored (" + (if (inGrace) "grace period" else "TUN traffic flowing") + ")",
            )
            return@withContext true
        }
        watchdogFailures++
        AppLogRepository.warning(LogSource.TOR, "Tor watchdog SOCKS check failed ($watchdogFailures/$WATCHDOG_MAX_FAILURES)")
        if (watchdogFailures == WATCHDOG_NEWNYM_AFTER) daemon.signal("NEWNYM")
        watchdogFailures < WATCHDOG_MAX_FAILURES
    }

    /** Pin middle hops to the fastest relays (consensus bandwidth); cached for a day. */
    private fun maybeApplyFastRelays(settings: TorEngineSettings) {
        if (fastRelaysApplied) return
        fastRelaysApplied = true
        if (!settings.fastRelays) return
        var list = engineStore.fastRelayList.split(',').filter { it.length == 40 }
        if (list.size < TorFastRelays.MIN_USABLE || System.currentTimeMillis() - engineStore.fastRelayAt > TorFastRelays.REFRESH_MS) {
            val body = daemon.controlBigInfo("ns/all")
            val picked = body?.let { TorFastRelays.pickMiddles(TorFastRelays.parse(it)) }.orEmpty()
            if (picked.size >= TorFastRelays.MIN_USABLE) {
                list = picked
                engineStore.fastRelayList = picked.joinToString(",")
            }
        }
        val conf = TorFastRelays.middleNodesConf(list)
        val ok = daemon.setConf(conf)
        val label = if (list.size >= TorFastRelays.MIN_USABLE) "${list.size} fast middles" else "default (list empty)"
        AppLogRepository.info(LogSource.TOR, "Fast relays: $label applied=$ok")
        TorLiveFlow.fact(TorFlowStageId.CIRCUITS, "middle relays", if (ok) label else "default")
        if (ok && list.size >= TorFastRelays.MIN_USABLE) daemon.signal("NEWNYM")
    }

    /**
     * Uses Tor's own traffic counters at each watchdog tick (no extra polling). Under load
     * (≥ 3 MB in the interval) but below 150 KB/s on two ticks in a row → NEWNYM, max once / 2 min.
     */
    private fun maybeSwapSlowCircuit(settings: TorEngineSettings) {
        if (!settings.slowCircuitSwap) return
        val read = daemon.controlInfo("traffic/read")
            ?.let { Regex("""traffic/read=(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return
        val now = System.currentTimeMillis()
        val prevBytes = lastTrafficRead
        val prevAt = lastTrafficAt
        lastTrafficRead = read
        lastTrafficAt = now
        if (prevAt == 0L || read < prevBytes) return
        val delta = read - prevBytes
        val kbps = ((delta / 1024.0) / ((now - prevAt) / 1000.0)).toInt()
        TorLiveFlow.fact(TorFlowStageId.WATCHDOG, "Tor read rate", "$kbps KB/s")
        val slowUnderLoad = delta >= SLOW_LOAD_BYTES && kbps < SLOW_KBPS
        slowTicks = if (slowUnderLoad) slowTicks + 1 else 0
        if (slowTicks >= 2 && now - lastSlowSwapAt >= SLOW_SWAP_MIN_INTERVAL_MS) {
            slowTicks = 0
            lastSlowSwapAt = now
            AppLogRepository.info(LogSource.TOR, "Slow circuits under load ($kbps KB/s); NEWNYM")
            daemon.signal("NEWNYM")
            TorLiveFlow.fact(TorFlowStageId.WATCHDOG, "slow-circuit swap", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        }
    }

    /** True when TUN byte counters grew since the previous tick (user traffic is flowing). */
    private fun tunTrafficGrew(): Boolean {
        if (!ownsTunRelay) return false
        val stats = runCatching { tunRelay.stats() }.getOrNull() ?: return false
        val total = stats.txBytes + stats.rxBytes
        val prev = lastTunBytes
        lastTunBytes = total
        return prev >= 0 && total > prev
    }

    /** Once per session, screen on: 256 KB download through Tor, KB/s stored on the bridge. */
    private fun maybeMeasureThroughput(settings: TorEngineSettings) {
        if (!settings.throughputScoring || throughputMeasured) return
        val bridge = sessionBridge ?: return
        throughputMeasured = true
        TorLiveFlow.start(TorFlowStageId.THROUGHPUT, "256 KB through Tor")
        val kbps = runCatching { TorThroughputProbe.measureKbps(settings.socksPort) }.getOrDefault(0)
        if (kbps > 0) TorLiveFlow.done(TorFlowStageId.THROUGHPUT, "$kbps KB/s", listOf("bridge" to bridge.label))
        else TorLiveFlow.fail(TorFlowStageId.THROUGHPUT, "test download failed")
        if (kbps > 0) {
            scoreStore.recordThroughput(sessionNetworkKey, bridge, kbps)
            AppLogRepository.info(LogSource.TOR, "Bridge ${bridge.label} throughput ${kbps} KB/s")
        }
    }

    /**
     * Compare the underlying (non-VPN) network by handle, not learningKey (its ASN part fills in
     * late once our VPN is up). Act only when the change is seen on two ticks in a row.
     */
    private suspend fun checkNetworkChange() {
        val current = runCatching { fingerprintResolver.captureAdaptive() }.getOrNull() ?: return
        val base = sessionFingerprint
        if (base == null) {
            sessionFingerprint = current
            return
        }
        val vpn = current.transport.contains("vpn", ignoreCase = true)
        if (vpn || current.isSameUnderlyingNetwork(base)) {
            pendingNetChange = false
            return
        }
        if (!pendingNetChange) {
            pendingNetChange = true
            return
        }
        pendingNetChange = false
        fastRelaysApplied = false
        lastTrafficRead = 0L
        lastTrafficAt = 0L
        slowTicks = 0
        AppLogRepository.info(
            LogSource.TOR,
            "Network changed (${base.transport} → ${current.transport}); SIGNAL ACTIVE + NEWNYM instead of restart",
        )
        daemon.signal("ACTIVE")
        daemon.signal("NEWNYM")
        sessionFingerprint = current
        sessionNetworkKey = current.learningKey()
        TorLiveFlow.fact(TorFlowStageId.WATCHDOG, "network change", current.transport)
    }

    private fun maybeRefreshBridges(settings: TorEngineSettings) {
        if (!settings.bridgeRefresh) return
        runCatching { TorBridgeRefresh.run(engineStore, settings) }
    }

    /** One real stream through Tor: SOCKS CONNECT + a tiny HTTP HEAD. */
    private fun socksCheck(port: Int): Boolean = runCatching {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { socket ->
            socket.soTimeout = WATCHDOG_TIMEOUT_MS
            socket.connect(InetSocketAddress.createUnresolved(WATCHDOG_HOST, 80), WATCHDOG_TIMEOUT_MS)
            socket.getOutputStream().apply {
                write("HEAD /generate_204 HTTP/1.0\r\nHost: $WATCHDOG_HOST\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            socket.getInputStream().read() >= 0
        }
    }.getOrDefault(false)

    private fun exitLabel(settings: TorEngineSettings): String =
        settings.exitCountryCode.ifBlank { if (settings.fastExit) "fast:" + TorExitCountry.FAST_EXIT_CODES.joinToString(",") else "auto" }

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
            orderedBridges(settings, currentNetworkKey())
        }
        check(bridges.isNotEmpty()) { "No WebTunnel bridges to restart Tor" }
        daemon.start(settings, bridges, TorDaemon.BRIDGE_BOOTSTRAP_TIMEOUT_MS, fragmentProxy?.port ?: 0)
        lastBridges = bridges
    }

    fun stop() {
        DomesticSocksSplit.stop()
        if (ownsTunRelay) {
            runCatching { tunRelay.stop() }
            ownsTunRelay = false
        }
        daemon.stop()
        stopFragmentProxy()
        connectedAt = 0L
        watchdogFailures = 0
        if (TorLiveFlow.snapshot.value.stage(TorFlowStageId.WATCHDOG).state == TorFlowState.RUNNING) {
            TorLiveFlow.done(TorFlowStageId.WATCHDOG, "stopped (disconnected)")
        }
    }

    private fun stopFragmentProxy() {
        fragmentProxy?.stop()
        fragmentProxy = null
    }

    /** Local ClientHello fragmenter for the WebTunnel hop, or 0 when fragment is off / refused. */
    private fun ensureFragmentProxy(settings: TorEngineSettings): Int {
        val wanted = when (settings.fragmentMode) {
            TorEngineSettings.FRAGMENT_OFF -> false
            TorEngineSettings.FRAGMENT_ON -> true
            else -> !engineStore.ptProxyUnsupported
        }
        if (!wanted) {
            stopFragmentProxy()
            return 0
        }
        fragmentProxy?.let { if (it.port > 0) return it.port }
        val proxy = TorFragmentProxy(
            chunkBytes = settings.fragmentLength,
            delayMs = settings.fragmentDelayMs,
            helloOnly = settings.fragmentPacket.equals("tlshello", ignoreCase = true),
            socketBufferBytes = if (settings.bigBuffers) TorTunRelayConfig.BIG_TCP_BUFFER else 0,
        )
        return runCatching { proxy.start() }
            .onSuccess { fragmentProxy = proxy }
            .onFailure { AppLogRepository.warning(LogSource.TOR, "Fragment proxy failed to start", it) }
            .getOrDefault(0)
    }

    private suspend fun bootstrap(
        torSettings: TorEngineSettings,
        bridges: List<WebTunnelBridge>,
        networkKey: String,
    ): WebTunnelBridge? {
        TorStatusStore.update(TorPhase.BRIDGING, 0, "Checking WebTunnel bridges")
        AppLogRepository.info(LogSource.TOR, "WebTunnel candidates=${bridges.size}")
        TorLiveFlow.start(TorFlowStageId.SCAN, "TLS reachability probe")
        val scan = WebTunnelHandshake.scanLive(bridges)
        if (scan.isNotEmpty()) scoreStore.recordScan(networkKey, scan)
        val best = scan.minByOrNull { it.value }
        TorLiveFlow.done(
            TorFlowStageId.SCAN,
            "${scan.size}/${minOf(bridges.size, WebTunnelHandshake.MAX_PROBE)} live",
            listOf(
                "probed" to minOf(bridges.size, WebTunnelHandshake.MAX_PROBE).toString(),
                "live" to scan.size.toString(),
                "best latency" to (best?.let { "${it.value} ms · ${it.key.label}" } ?: "-"),
            ),
        )
        val scores = scoreStore.scores(networkKey)
        val live = TorBridgeRanking.order(
            scan.entries.sortedBy { it.value }.map { it.key },
            scores,
            System.currentTimeMillis(),
        )
        val ranked = (live + bridges.filterNot { it in scan.keys }).distinct()
        val batches = ranked.chunked(WebTunnelHandshake.MAX_LAUNCH).take(MAX_BATCHES)
        var lastError: Throwable? = null
        var processUp = false
        var fragmentPort = ensureFragmentProxy(torSettings)
        publishFragment(torSettings, fragmentPort)
        var blameFragmentOnSuccess = false
        var index = 0
        while (index < batches.size) {
            val batch = batches[index]
            TorLiveFlow.start(
                TorFlowStageId.BATCH,
                "${index + 1}/${batches.size}",
                listOf(
                    "batch" to "${index + 1}/${batches.size}",
                    "bridges" to batch.joinToString { it.label },
                    "start" to if (processUp && torSettings.singleProcess) "control-port swap" else "new tor process",
                ),
            )
            TorStatusStore.update(
                TorPhase.BRIDGING,
                0,
                "WebTunnel batch ${index + 1}/${batches.size} · ${batch.size} bridge(s)",
            )
            AppLogRepository.info(
                LogSource.TOR,
                "Starting Tor with ${batch.size} WebTunnel bridge(s) batch=${index + 1}/${batches.size} " +
                    "fragment=${fragmentPort > 0} reuse=${processUp && torSettings.singleProcess}",
            )
            try {
                val swapped = processUp && torSettings.singleProcess && daemon.isRunning() &&
                    daemon.swapBridges(batch, TorDaemon.BRIDGE_BOOTSTRAP_TIMEOUT_MS)
                if (!swapped) {
                    daemon.start(
                        torSettings,
                        batch,
                        TorDaemon.BRIDGE_BOOTSTRAP_TIMEOUT_MS,
                        fragmentProxyPort = fragmentPort,
                        keepOnFailure = torSettings.singleProcess,
                    )
                }
                lastBridges = batch
                TorLiveFlow.done(TorFlowStageId.BATCH, facts = listOf("cache" to if (daemon.lastStartWasCold) "cold" else "warm"))
                if (blameFragmentOnSuccess && torSettings.fragmentMode == TorEngineSettings.FRAGMENT_AUTO) {
                    engineStore.ptProxyUnsupported = true
                    AppLogRepository.info(LogSource.TOR, "Fragment Auto will skip the proxy from now on (PT crashed with it)")
                }
                return resolveUsedBridge(batch)
            } catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                lastError = error
                processUp = daemon.isRunning()
                if (error is TorPtCrashLoopException) {
                    // A crashing PT says nothing about the bridges: never score these.
                    if (fragmentPort > 0) {
                        AppLogRepository.warning(
                            LogSource.TOR,
                            "WebTunnel PT crash loop with fragment proxy; retrying this batch without fragment",
                        )
                        blameFragmentOnSuccess = true
                        stopFragmentProxy()
                        fragmentPort = 0
                        TorLiveFlow.branch("batch ${index + 1}", "PT crash loop with fragment", "same batch, fragment off")
                        publishFragment(torSettings, 0)
                        processUp = false
                        continue
                    }
                    runCatching { daemon.stop() }
                    throw error
                }
                if (error is TorPtProxyRefusedException && fragmentPort > 0) {
                    AppLogRepository.warning(
                        LogSource.TOR,
                        "WebTunnel PT refused the fragment proxy; retrying this batch without fragment",
                    )
                    if (torSettings.fragmentMode == TorEngineSettings.FRAGMENT_AUTO) engineStore.ptProxyUnsupported = true
                    stopFragmentProxy()
                    fragmentPort = 0
                    TorLiveFlow.branch("batch ${index + 1}", "PT refused fragment proxy", "same batch, fragment off")
                    publishFragment(torSettings, 0)
                    processUp = false
                    continue
                }
                scoreStore.recordFailure(networkKey, batch)
                AppLogRepository.warning(
                    LogSource.TOR,
                    "WebTunnel batch ${index + 1} failed: ${error.message.orEmpty()}",
                )
                if (!torSettings.singleProcess) runCatching { daemon.stop() }
                TorLiveFlow.branch(
                    "batch ${index + 1}/${batches.size}",
                    error.message.orEmpty(),
                    if (index + 1 < batches.size) "batch ${index + 2}" else "no batches left",
                )
                index++
            }
        }
        runCatching { daemon.stop() }
        throw IllegalStateException(
            lastError?.message?.ifBlank { null }
                ?: "None of the WebTunnel bridges bootstrapped",
            lastError,
        )
    }

    /** The bridge Tor really uses (entry-guards / circuit-status), not batch.first(). */
    private fun resolveUsedBridge(batch: List<WebTunnelBridge>): WebTunnelBridge? {
        if (batch.size == 1) return batch.first()
        val used = runCatching { daemon.usedBridgeFingerprints() }.getOrDefault(emptyList())
        val match = used.firstNotNullOfOrNull { fp -> batch.firstOrNull { it.fingerprint == fp } }
        if (match == null) {
            AppLogRepository.info(LogSource.TOR, "Could not tell which bridge Tor used; last-good not updated")
        } else {
            AppLogRepository.info(LogSource.TOR, "Tor is using bridge ${match.label}")
        }
        return match
    }

    private suspend fun currentNetworkKey(): String =
        runCatching { fingerprintResolver.captureAdaptive().learningKey() }.getOrDefault("unknown")

    private fun publishFragment(settings: TorEngineSettings, port: Int) {
        if (port > 0) {
            TorLiveFlow.done(
                TorFlowStageId.FRAGMENT,
                "127.0.0.1:$port",
                listOf(
                    "mode" to settings.fragmentMode,
                    "effective" to "on",
                    "port" to port.toString(),
                    "packet/bytes/delay" to "${settings.fragmentPacket} · ${settings.fragmentLength} · ${settings.fragmentDelayMs} ms",
                ),
            )
        } else {
            val why = if (settings.fragmentMode == TorEngineSettings.FRAGMENT_OFF) "off" else "auto → off (PT unsupported)"
            TorLiveFlow.skip(TorFlowStageId.FRAGMENT, why)
            TorLiveFlow.fact(TorFlowStageId.FRAGMENT, "mode", settings.fragmentMode)
        }
    }

    private fun orderedBridges(settings: TorEngineSettings, networkKey: String, publish: Boolean = false): List<WebTunnelBridge> {
        val bundled = WebTunnelBridgeCatalog.loadBundled(appContext)
        val refreshed = WebTunnelBridgeParser.parseAll(engineStore.refreshedBridges)
        AppLogRepository.info(
            LogSource.TOR,
            "WebTunnel pool user=${WebTunnelBridgeParser.parseAll(settings.bridgeLines).size} " +
                "refreshed=${refreshed.size} bundled=${bundled.size}",
        )
        val merged = WebTunnelBridgeCatalog.merge(
            userLines = settings.bridgeLines,
            lastGoodRaw = engineStore.lastGoodBridge(networkKey),
            bundled = bundled,
            refreshed = refreshed,
        )
        val scores = scoreStore.scores(networkKey)
        val now = System.currentTimeMillis()
        if (publish) {
            TorLiveFlow.done(
                TorFlowStageId.POOL,
                "${merged.size} bridges",
                listOf(
                    "last-good" to if (engineStore.lastGoodBridge(networkKey).isNullOrBlank()) "0" else "1",
                    "scored" to merged.count { scores.containsKey(TorBridgeRanking.key(it)) }.toString(),
                    "pasted" to WebTunnelBridgeParser.parseAll(settings.bridgeLines).size.toString(),
                    "bundled" to bundled.size.toString(),
                    "refreshed" to refreshed.size.toString(),
                    "cooled-down" to merged.count { scores[TorBridgeRanking.key(it)]?.coolingDown(now) == true }.toString(),
                ),
            )
        }
        return TorBridgeRanking.order(merged, scores, now)
    }

    private companion object {
        const val MAX_BATCHES = 3
        const val SLOW_LOAD_BYTES = 3L * 1024 * 1024
        const val SLOW_KBPS = 150
        const val SLOW_SWAP_MIN_INTERVAL_MS = 120_000L
        const val WATCHDOG_MAX_FAILURES = 5
        const val WATCHDOG_NEWNYM_AFTER = 3
        const val WATCHDOG_GRACE_MS = 60_000L
        const val WATCHDOG_TIMEOUT_MS = 15_000
        const val WATCHDOG_HOST = "clients3.google.com"
    }
}