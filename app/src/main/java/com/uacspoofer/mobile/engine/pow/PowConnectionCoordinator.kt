package com.uacspoofer.mobile.engine.pow

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.vpn.TunStats
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class PowConnectionCoordinator(
    private val service: VpnService,
) {
    private val appContext = service.applicationContext
    private val store = PowEngineStore.get(appContext)
    private val lifecycle = Mutex()
    private val retuneMutex = Mutex()
    private val qualityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private val retuning = AtomicBoolean(false)
    private val innerCallbacksArmed = AtomicBoolean(true)
    private val qualityRetunes = AtomicInteger(0)
    private val crashRecovers = AtomicInteger(0)
    private val sessionGeneration = AtomicInteger(0)
    private val psiphonGeneration = AtomicInteger(0)
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)
    private val txPackets = AtomicLong(0L)
    private val rxPackets = AtomicLong(0L)
    private val ladderActive = AtomicBoolean(false)
    private val callbacks = PsiphonCallbacks()
    @Volatile private var psiphon: PowPsiphonClient? = null

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var ownsTun = false
    @Volatile private var proxyMode = false
    @Volatile private var socksPort = PowCoreConfig.SOCKS_PORT
    @Volatile private var outerThread: Thread? = null
    @Volatile private var outerCommitted = false
    @Volatile private var innerReady = false
    @Volatile private var routing = false
    @Volatile private var activeTunnelProtocol = ""
    @Volatile private var regionPhase = false
    @Volatile private var regionPhaseTried = ""
    @Volatile private var ladderIndex = 0
    @Volatile private var ladderAttempts = 0
    @Volatile private var connectedSignal: CompletableDeferred<Unit>? = null
    @Volatile private var ladderJob: Job? = null
    @Volatile private var currentSettings = PowEngineSettings.DEFAULT
    @Volatile private var innerStarted = false
    @Volatile private var sessionMtu = PowTunRelayConfig.DEFAULT_MTU
    @Volatile private var qualityJob: Job? = null
    @Volatile private var retuneSignal: CompletableDeferred<Unit>? = null
    @Volatile private var lastOuterProtocol = ""
    @Volatile private var lastRetuneAt = 0L
    @Volatile private var baselineRttMs = 0L
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkKey = ""
    @Volatile private var recentSamples: MutableList<Long> = mutableListOf()
    @Volatile private var ghostInFlight = AtomicBoolean(false)
    @Volatile private var lastStableAt = SystemClock.elapsedRealtime()

    val tunAddress: PowTun2Socks.PrivateAddress
        get() = PowTun2Socks.privateAddress

    fun isRetuning(): Boolean = retuning.get()

    fun isHealthyPath(): Boolean =
        !stopRequested.get() &&
            innerReady &&
            (proxyMode || PowTun2Socks.isRunning) &&
            AetherNative.isRunning() &&
            AetherNative.isReady()

    fun isRunning(): Boolean {
        if (retuning.get()) {
            return !stopRequested.get() && (proxyMode || PowTun2Socks.isRunning)
        }
        return innerReady && (proxyMode || (PowTun2Socks.isRunning && AetherNative.isRunning()))
    }

    fun isOuterRunning(): Boolean = AetherNative.isRunning()

    fun isRelayRunning(): Boolean = proxyMode || PowTun2Socks.isRunning

    fun socksPort(): Int = socksPort

    fun tunStats(): TunStats {
        if (!proxyMode) {
            val tun = PowTun2Socks.stats()
            if (tun != TunStats.ZERO) return tun
        }
        return TunStats(
            txPackets = txPackets.get(),
            txBytes = txBytes.get(),
            rxPackets = rxPackets.get(),
            rxBytes = rxBytes.get(),
        )
    }

    suspend fun connect(
        settings: AdvancedSettingsData,
        establishTun: (PowTun2Socks.PrivateAddress, Int) -> ParcelFileDescriptor?,
    ) {
        lifecycle.withLock { stopLocked() }
        stopRequested.set(false)
        sessionGeneration.incrementAndGet()
        txBytes.set(0L)
        rxBytes.set(0L)
        txPackets.set(0L)
        rxPackets.set(0L)
        innerReady = false
        innerStarted = false
        routing = false
        outerCommitted = false
        sessionMtu = PowTunRelayConfig.DEFAULT_MTU
        activeTunnelProtocol = ""
        lastOuterProtocol = ""
        baselineRttMs = 0L
        lastRetuneAt = 0L
        qualityRetunes.set(0)
        crashRecovers.set(0)
        currentSettings = store.snapshot()
        proxyMode = settings.connectionMode == CONNECTION_MODE_PROXY
        socksPort = if (proxyMode) settings.socksPort else PowCoreConfig.SOCKS_PORT
        regionPhase = PowRegions.egressRegion(currentSettings.exitCountryCode) != null
        regionPhaseTried = currentSettings.exitCountryCode
        ladderIndex = store.rememberedStrategyIndex(
            PowPsiphonProtocols.signature(),
            PowPsiphonProtocols.LADDER.size,
        )
        ladderAttempts = 0
        if (!AetherNative.available) {
            error(AetherNative.unavailableReason)
        }
        try {
            coroutineScope {
                val expired = PowCoreConfig.expireStalePathMemory(appContext)
                if (expired > 0) {
                    AppLogRepository.info(
                        LogSource.POW,
                        "Dropped $expired stale WARP endpoint cache(s) so the next hop can be measured again",
                    )
                }
                PowStatusStore.update(PowPhase.STARTING, 5, "Starting UAC PoW")
                if (!proxyMode) {
                    val address = PowTun2Socks.selectPrivateAddress()
                    sessionMtu = PowTunRelayConfig.mtuForChain(settings.tunMtu, underlyingLinkMtu())
                    PowStatusStore.update(PowPhase.STARTING, 10, "Creating device VPN interface")
                    tunFd = establishTun(address, sessionMtu) ?: error("Android could not establish the VPN interface")
                    ownsTun = true
                }
                AetherNative.attach(service)
                val outer = raiseOuterLeg(
                    scanMode = PowCoreConfig.normalizeScanMode(currentSettings.scanMode),
                ) ?: error(outerFailureMessage())
                ensureActive()
                PowStatusStore.update(
                    PowPhase.INNER,
                    55,
                    "Starting Psiphon through $outer",
                    outerLabel = outer,
                )
                val connected = CompletableDeferred<Unit>()
                connectedSignal = connected
                startPsiphonTunnel()
                ladderJob = launch {
                    watchLadder()
                }
                try {
                    withTimeout(INNER_CONNECT_TIMEOUT_MS) { connected.await() }
                } catch (timeout: TimeoutCancellationException) {
                    error("Psiphon did not connect through WARP in time")
                }
                ensureActive()
                if (!proxyMode) {
                    val fd = tunFd ?: error("VPN interface missing")
                    PowStatusStore.update(PowPhase.BRIDGING, 90, "Routing device traffic through UAC PoW")
                    if (!PowSocksConnectOnly.start(PowCoreConfig.TUN_SOCKS_PORT, socksPort)) {
                        error("Could not start whole-device routing")
                    }
                    if (!PowTun2Socks.start(
                            appContext,
                            fd,
                            PowCoreConfig.TUN_SOCKS_PORT,
                            sessionMtu,
                            tunAddress,
                        )
                    ) {
                        PowSocksConnectOnly.stop()
                        error("Could not start whole-device routing")
                    }
                    routing = true
                }
                innerReady = true
                lastNetworkKey = PowNetworkScoreboard.networkKey(appContext)
                val ready = if (proxyMode) {
                    "UAC PoW ready · Proxy SOCKS 127.0.0.1:$socksPort"
                } else {
                    "UAC PoW ready · Tunnel VPN through Psiphon over WARP"
                }
                PowStatusStore.update(PowPhase.CONNECTED, 100, ready, outerLabel = outer)
                AppLogRepository.success(LogSource.POW, ready)
            }
            startQualityWatch()
            PowPageTurbo.kick(qualityScope, socksPort)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stop() }
            throw cancelled
        } catch (error: Throwable) {
            PowStatusStore.update(
                PowPhase.FAILED,
                0,
                error.message.orEmpty().ifBlank { "UAC PoW connect failed" },
            )
            withContext(NonCancellable) { stop() }
            throw error
        }
    }

    suspend fun stop() {
        stopRequested.set(true)
        cancelQualityWork()
        PowPageTurbo.cancel()
        connectedSignal?.cancel()
        unregisterNetworkCallback()
        lifecycle.withLock { stopLocked() }
    }

    suspend fun recoverBehindTun(reason: String): Boolean {
        if (stopRequested.get()) return false
        if (!proxyMode && !PowTun2Socks.isRunning) return false
        return retuneMutex.withLock {
            if (stopRequested.get()) return@withLock false
            if (isHealthyPath()) return@withLock true
            if (crashRecovers.incrementAndGet() > PowQualityPolicy.MAX_CRASH_RECOVERS) {
                AppLogRepository.warning(LogSource.POW, "UAC PoW path refresh limit reached")
                return@withLock false
            }
            // Level B: if outer is still healthy, only rebuild inner (20s cheaper).
            val outerAlive = AetherNative.isRunning() && AetherNative.isReady()
            if (outerAlive) {
                AppLogRepository.info(LogSource.POW, "Outer still healthy — inner-only rebuild")
                retuneInnerOnly(reason)
            } else {
                retuneLocked(reason, quality = false)
            }
        }
    }

    // Level B: dedicated ConnectivityManager callback owned by PoW only.
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (stopRequested.get() || !innerReady) return
                AppLogRepository.warning(LogSource.POW, "Network lost — quick inner retune")
                qualityScope.launch { runCatching { recoverBehindTun("network lost") } }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (stopRequested.get() || !innerReady) return
                val newKey = PowNetworkScoreboard.networkKey(appContext)
                if (newKey != lastNetworkKey) {
                    lastNetworkKey = newKey
                    val elapsed = SystemClock.elapsedRealtime() - lastRetuneAt
                    if (lastRetuneAt == 0L || elapsed > PowQualityPolicy.RETUNE_COOLDOWN_NETWORK_MS) {
                        AppLogRepository.info(LogSource.POW, "Network changed $newKey — refreshing")
                        qualityScope.launch { runCatching { requestQualityRetune("network change $newKey") } }
                    }
                }
            }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }
        networkCallback = cb
        lastNetworkKey = PowNetworkScoreboard.networkKey(appContext)
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private suspend fun retuneInnerOnly(reason: String): Boolean {
        if (stopRequested.get()) return false
        retuning.set(true)
        PowSocksConnectOnly.setFailFast(true)
        AppLogRepository.info(LogSource.POW, "Inner-only rebuild ($reason)")
        innerReady = false
        return try {
            innerCallbacksArmed.set(false)
            innerStarted = false
            withContext(Dispatchers.IO) { runCatching { innerClient().stop() } }
            if (stopRequested.get()) return false
            delay(400)
            regionPhase = false; ladderIndex = 0; ladderAttempts = 0
            ladderActive.set(false); ladderJob?.cancel()
            val keepExit = PowRegions.egressRegion(currentSettings.exitCountryCode) != null
            val connected = CompletableDeferred<Unit>()
            retuneSignal = connected
            innerCallbacksArmed.set(true)
            startPsiphonTunnel(keepExit = keepExit)
            ladderJob = qualityScope.launch { watchLadder() }
            withTimeout(PowQualityPolicy.INNER_RETUNE_TIMEOUT_MS) { connected.await() }
            if (stopRequested.get()) return false
            val sample = PowPathProbe.measureMs(socksPort)
            if (sample > 0L) baselineRttMs = sample
            lastRetuneAt = SystemClock.elapsedRealtime()
            AppLogRepository.success(LogSource.POW, "Inner-only path rebuilt" + if (sample>0) " ${sample}ms" else "")
            true
        } catch (_: TimeoutCancellationException) {
            AppLogRepository.warning(LogSource.POW, "Inner-only rebuild timeout — escalating to full handover")
            retuneLocked(reason, quality = false)
        } catch (e: Throwable) {
            AppLogRepository.warning(LogSource.POW, "Inner-only failed: ${e.message}")
            false
        } finally {
            retuneSignal = null
            innerCallbacksArmed.set(true)
            PowSocksConnectOnly.setFailFast(false)
            retuning.set(false)
        }
    }

    fun onNativeEvent(json: String) {
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (event.optString("type")) {
            "log" -> {
                val message = event.optString("message")
                if (message.isNotBlank()) {
                    AppLogRepository.debug(LogSource.POW, message.take(240))
                }
            }
        }
    }

    private suspend fun raiseOuterLeg(
        discovery: String = PowCoreConfig.DISCOVERY_CACHE,
        scanMode: String = PowCoreConfig.SCAN_BALANCED,
        silent: Boolean = false,
        retune: Boolean = false,
        chainPort: Int = PowCoreConfig.CHAIN_SOCKS_PORT,
    ): String? {
        val ladder = PowCoreConfig.candidates(currentSettings)
        val auto = ladder.size > 1
        val remembered = store.rememberedOuterProtocol()
        val ordered = when {
            retune -> PowQualityPolicy.outerOrder(ladder, lastOuterProtocol.ifBlank { remembered.orEmpty() })
            auto && remembered != null && remembered in ladder ->
                listOf(remembered) + ladder.filter { it != remembered }
            else -> ladder
        }
        for ((offset, protocol) in ordered.withIndex()) {
            coroutineContext.ensureActive()
            if (stopRequested.get()) return null
            val label = PowCoreConfig.outerLabel(protocol)
            val budget = PowCoreConfig.outerBudgetMs(protocol, retune = retune, scanMode = scanMode)
            if (AetherNative.isRunning()) {
                stopOuterLeg()
            }
            if (!silent) {
                PowStatusStore.update(
                    PowPhase.OUTER,
                    20 + (offset * 10),
                    "Connecting $label",
                    outerLabel = label,
                )
            }
            AppLogRepository.info(
                LogSource.POW,
                "Outer attempt ${offset + 1}/${ordered.size}: $label (${budget / 1000}s, $scanMode)" +
                    if (retune) " fresh" else "",
            )
            val config = if (chainPort == PowCoreConfig.CHAIN_SOCKS_PORT) {
                PowCoreConfig.chainOuterJson(appContext, protocol, currentSettings, discovery = discovery, scanMode = scanMode)
            } else {
                PowGhostHandover.shadowChainJson(appContext, protocol, currentSettings, chainPort)
            }
            val prepared = runCatching { AetherNative.prepare(config) }
            if (prepared.isFailure) {
                AppLogRepository.warning(
                    LogSource.POW,
                    "$label could not be prepared: ${AetherNative.lastError()}",
                )
                stopOuterLeg()
                continue
            }
            val generation = sessionGeneration.get()
            val thread = Thread({
                try {
                    val result = AetherNative.startProxy(config)
                    if (result != 0 && !stopRequested.get() && generation == sessionGeneration.get()) {
                        AppLogRepository.warning(
                            LogSource.POW,
                            "$label ended: ${AetherNative.lastError().ifBlank { "exit $result" }}",
                        )
                    }
                } catch (error: Throwable) {
                    AppLogRepository.warning(LogSource.POW, "$label threw: ${error.message}")
                }
            }, "uac-pow-outer-$protocol")
            thread.isDaemon = true
            outerThread = thread
            thread.start()
            if (awaitOuterProxy(budget, chainPort)) {
                store.saveOuterProtocol(protocol)
                lastOuterProtocol = protocol
                outerCommitted = true
                AppLogRepository.success(LogSource.POW, "$label is carrying the outer leg")
                return label
            }
            if (stopRequested.get()) return null
            AppLogRepository.warning(LogSource.POW, "$label did not come up in ${budget / 1000}s")
            stopOuterLeg()
        }
        return null
    }

    private fun outerFailureMessage(): String =
        if (PowCoreConfig.candidates(currentSettings).size > 1) {
            "No WARP transport could carry Psiphon on this network"
        } else {
            "${PowCoreConfig.outerLabel(PowCoreConfig.candidates(currentSettings).first())} could not carry Psiphon"
        }

    private suspend fun awaitOuterProxy(budgetMs: Long, chainPort: Int = PowCoreConfig.CHAIN_SOCKS_PORT): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + budgetMs
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            if (stopRequested.get()) return false
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed > OUTER_START_GRACE_MS && !AetherNative.isRunning()) return false
            if (AetherNative.isReady() && outerProxyAccepts(chainPort)) return true
            delay(300)
        }
        return false
    }

    private fun outerProxyAccepts(port: Int = PowCoreConfig.CHAIN_SOCKS_PORT): Boolean = runCatching {
        Socket().use { probe ->
            probe.connect(InetSocketAddress("127.0.0.1", port), 1_000)
        }
    }.isSuccess

    private suspend fun stopOuterLeg() {
        outerCommitted = false
        AetherNative.stop()
        val deadline = SystemClock.elapsedRealtime() + OUTER_STOP_GRACE_MS
        while (AetherNative.isRunning() && SystemClock.elapsedRealtime() < deadline) {
            delay(200)
        }
        outerThread?.let { thread ->
            if (thread.isAlive) {
                runCatching { thread.join(1_000L) }
            }
        }
        outerThread = null
    }

    private suspend fun startPsiphonTunnel(keepExit: Boolean = false, socks: Int = socksPort) {
        val generation = sessionGeneration.get()
        val strategy = PowPsiphonProtocols.LADDER.getOrElse(ladderIndex) { PowPsiphonProtocols.LADDER.first() }
        val preferred = if (regionPhase || keepExit) {
            PowRegions.egressRegion(currentSettings.exitCountryCode)
        } else {
            null
        }
        val configJson = PowPsiphonProtocols.buildConfig(
            socksPort = socks,
            dataDirectory = innerDataDirectory(),
            preferredRegion = preferred,
            strategy = strategy,
        )
        psiphonGeneration.set(generation)
        innerStarted = true
        AppLogRepository.info(LogSource.POW, "Starting Psiphon inner hop in :uacpow")
        innerClient().start(configJson)
        AppLogRepository.info(
            LogSource.POW,
            "Psiphon starting strategy ${strategy.name} (${strategy.label})" +
                if (preferred != null) " region=$preferred" else "",
        )
    }

    private fun innerDataDirectory(): String {
        val dir = appContext.getDir("uac-pow-inner", 0)
        return dir.absolutePath
    }

    private suspend fun watchLadder() {
        while (!stopRequested.get() && !innerReady) {
            ladderActive.set(true)
            val strategy = PowPsiphonProtocols.LADDER.getOrElse(ladderIndex) { return }
            val seconds = if (regionPhase && PowRegions.egressRegion(currentSettings.exitCountryCode) != null) {
                PowPsiphonProtocols.REGION_PHASE_TIMEOUT_SECONDS
            } else {
                strategy.timeoutSeconds
            }
            delay((seconds.toLong() + 8L) * 1_000L)
            if (stopRequested.get() || innerReady || !ladderActive.get()) return
            escalateLadder()
        }
    }

    private suspend fun escalateLadder() {
        if (stopRequested.get() || innerReady) return
        if (!ladderActive.compareAndSet(true, false)) return
        val ladder = PowPsiphonProtocols.LADDER
        if (regionPhase) {
            regionPhase = false
            AppLogRepository.info(
                LogSource.POW,
                "Preferred country ${PowRegions.name(regionPhaseTried)} did not connect — trying all countries",
            )
            restartPsiphonController()
            return
        }
        ladderAttempts += 1
        if (ladderAttempts >= ladder.size) {
            val error = IllegalStateException("Could not connect on this carrier. Try Wi-Fi or another SIM.")
            connectedSignal?.completeExceptionally(error)
            retuneSignal?.completeExceptionally(error)
            return
        }
        ladderIndex = (ladderIndex + 1) % ladder.size
        val next = ladder[ladderIndex]
        AppLogRepository.info(LogSource.POW, "Trying strategy ${next.name} (${next.label})")
        if (!retuning.get()) {
            PowStatusStore.update(PowPhase.INNER, 60, "Trying ${next.label}")
        }
        restartPsiphonController()
    }

    private suspend fun restartPsiphonController() {
        innerStarted = false
        withContext(Dispatchers.IO) {
            runCatching { innerClient().stop() }
        }
        delay(1_200)
        if (stopRequested.get()) return
        startPsiphonTunnel(
            keepExit = retuning.get() && PowRegions.egressRegion(currentSettings.exitCountryCode) != null,
        )
    }

    private fun recordLadderWinner() {
        val protocol = activeTunnelProtocol
        val ladder = PowPsiphonProtocols.LADDER
        val matches = ladder.indices.filter { index ->
            ladder[index].preferredProtocols.contains(protocol)
        }
        val winner = when {
            matches.size == 1 -> matches[0]
            matches.contains(ladderIndex) -> ladderIndex
            matches.isNotEmpty() -> matches[0]
            else -> ladderIndex
        }
        val rtt = baselineRttMs
        if (rtt > 0) store.saveStrategyWinnerWithRtt(winner, PowPsiphonProtocols.signature(), rtt)
        else store.saveStrategyWinner(winner, PowPsiphonProtocols.signature())
    }

    private suspend fun stopLocked() {
        stopRequested.set(true)
        cancelQualityWork()
        PowPageTurbo.cancel()
        unregisterNetworkCallback()
        ghostInFlight.set(false)
        ladderActive.set(false)
        ladderJob?.cancel()
        ladderJob = null
        connectedSignal?.cancel()
        connectedSignal = null
        innerReady = false
        retuning.set(false)
        innerCallbacksArmed.set(true)
        PowSocksConnectOnly.setFailFast(false)
        withContext(Dispatchers.IO + NonCancellable) {
            val client = psiphon
            if (innerStarted || client != null) {
                runCatching { client?.stop() }
            }
            innerStarted = false
            client?.close()
            psiphon = null
            PowTun2Socks.stop()
            PowTun2Socks.awaitNativeExit()
            PowSocksConnectOnly.stop()
            stopOuterLeg()
            AetherNative.detach()
        }
        if (ownsTun) {
            runCatching { tunFd?.close() }
        }
        tunFd = null
        ownsTun = false
        routing = false
        regionPhase = PowRegions.egressRegion(store.snapshot().exitCountryCode) != null
        regionPhaseTried = currentSettings.exitCountryCode
        ladderIndex = store.rememberedStrategyIndex(PowPsiphonProtocols.signature(), PowPsiphonProtocols.LADDER.size)
        ladderAttempts = 0
        recentSamples = mutableListOf()
        if (PowStatusStore.status.value.phase != PowPhase.FAILED) {
            PowStatusStore.reset()
        }
    }

    private fun innerClient(): PowPsiphonClient {
        return psiphon ?: PowPsiphonClient(service, callbacks).also { psiphon = it }
    }

    private inner class PsiphonCallbacks : PowPsiphonClient.Callbacks {
        override fun onConnecting() {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            if (!innerCallbacksArmed.get()) return
            AppLogRepository.info(LogSource.POW, "Psiphon connecting")
        }

        override fun onConnected() {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            if (!innerCallbacksArmed.get()) return
            ladderActive.set(false)
            ladderJob?.cancel()
            if (regionPhase) {
                regionPhase = false
                AppLogRepository.info(
                    LogSource.POW,
                    "Connected in preferred country ${PowRegions.name(regionPhaseTried)}",
                )
            }
            innerReady = true
            connectedSignal?.complete(Unit)
            retuneSignal?.complete(Unit)
            AppLogRepository.success(LogSource.POW, "Psiphon connected through WARP")
        }

        override fun onExiting() {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            if (stopRequested.get() || retuning.get() || !innerCallbacksArmed.get()) return
            if (!innerReady && ladderActive.get()) {
                AppLogRepository.warning(LogSource.POW, "Psiphon exited before connect; trying the next strategy")
            }
            if (innerReady) {
                AppLogRepository.warning(LogSource.POW, "Psiphon tunnel stopped; refreshing the path")
                qualityScope.launch {
                    runCatching { recoverBehindTun("psiphon exited") }
                }
            }
        }

        override fun onListeningSocksProxyPort(port: Int) {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            socksPort = port
            PowSocksConnectOnly.setUpstreamPort(port)
            AppLogRepository.info(LogSource.POW, "Psiphon SOCKS listening on $port")
        }

        override fun onDiagnosticMessage(message: String) {
            val text = message.ifBlank { return }
            if (text.startsWith("ActiveTunnel:")) {
                runCatching {
                    activeTunnelProtocol = JSONObject(text.substringAfter("ActiveTunnel:").trim())
                        .optString("protocol")
                }
            }
            if (text.contains("error", ignoreCase = true) || text.startsWith("ActiveTunnel:")) {
                AppLogRepository.debug(LogSource.POW, text.take(240))
            }
        }

        override fun onBytesTransferred(sent: Long, received: Long) {
            if (sent > 0) {
                txBytes.addAndGet(sent)
                txPackets.incrementAndGet()
            }
            if (received > 0) {
                rxBytes.addAndGet(received)
                rxPackets.incrementAndGet()
            }
        }

        override fun onClientRegion(region: String) {
            AppLogRepository.debug(LogSource.POW, "Psiphon client region $region")
        }

        override fun onConnectedServerRegion(region: String) {
            PowStatusStore.update(
                PowStatusStore.status.value.phase,
                PowStatusStore.status.value.progressPercent,
                PowStatusStore.status.value.detail,
                exitRegion = region.uppercase(),
            )
            AppLogRepository.info(LogSource.POW, "Exit country ${PowRegions.name(region)} ($region)")
            recordLadderWinner()
        }

        override fun onAvailableEgressRegions(regions: List<String>) {
            if (regions.isEmpty()) return
            PowRegions.remember(appContext, regions)
        }

        override fun onClientAddress(address: String) {
            AppLogRepository.info(LogSource.POW, "Psiphon exit IP $address")
        }

        override fun onStartFailed(message: String) {
            if (psiphonGeneration.get() != sessionGeneration.get()) return
            if (!innerCallbacksArmed.get()) return
            connectedSignal?.completeExceptionally(IllegalStateException(message))
            retuneSignal?.completeExceptionally(IllegalStateException(message))
        }
    }

    init {
        regionPhase = PowRegions.egressRegion(store.snapshot().exitCountryCode) != null
        regionPhaseTried = store.snapshot().exitCountryCode
        ladderIndex = store.rememberedStrategyIndex(PowPsiphonProtocols.signature(), PowPsiphonProtocols.LADDER.size)
    }

    private fun underlyingLinkMtu(): Int = runCatching {
        val connectivity = service.getSystemService(ConnectivityManager::class.java) ?: return 0
        val network = connectivity.activeNetwork ?: return 0
        connectivity.getLinkProperties(network)?.mtu ?: 0
    }.getOrDefault(0)

    private fun isScreenOn(): Boolean = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    }.getOrDefault(true)

    private fun isPowerSave(): Boolean = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode ?: false
    }.getOrDefault(false)

    private fun startQualityWatch() {
        qualityJob?.cancel()
        registerNetworkCallback()
        qualityJob = qualityScope.launch {
            try {
                watchQuality()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogRepository.debug(LogSource.POW, "Quality watch ended: ${error.message}")
            }
        }
    }

    private fun cancelQualityWork() {
        qualityJob?.cancel()
        qualityJob = null
        qualityScope.coroutineContext[Job]?.cancelChildren()
        retuneSignal?.cancel()
        retuneSignal = null
    }

    private suspend fun watchQuality() {
        delay(PowQualityPolicy.SETTLE_MS)
        if (stopRequested.get() || !innerReady) return
        val opening = mutableListOf<Long>()
        repeat(PowQualityPolicy.BASELINE_SAMPLES) { index ->
            while (retuning.get() && !stopRequested.get()) delay(350)
            if (stopRequested.get() || !innerReady) return
            val sample = PowPathProbe.measureMs(socksPort)
            if (sample > 0L) opening += sample
            PowAdaptiveObfuscation.onProbe(sample > 0, false)
            if (index + 1 < PowQualityPolicy.BASELINE_SAMPLES) delay(550)
        }
        baselineRttMs = PowQualityPolicy.median(opening)
        if (baselineRttMs > 0L) {
            AppLogRepository.info(LogSource.POW, "UAC PoW path baseline ${baselineRttMs}ms")
        }
        recentSamples = opening.takeLast(3).toMutableList()
        lastStableAt = SystemClock.elapsedRealtime()
        var badStreak = 0
        while (!stopRequested.get()) {
            val interval = PowQualityPolicy.adaptiveIntervalMs(isScreenOn(), isPowerSave())
            delay(interval)
            if (stopRequested.get() || retuning.get()) continue
            if (!innerReady) continue
            // Adaptive obfuscation check
            val elapsedStable = SystemClock.elapsedRealtime() - lastStableAt
            PowAdaptiveObfuscation.shouldDisableFragmentation(appContext, elapsedStable)
            val sample = PowPathProbe.measureMs(socksPort)
            val jitterHigh = PowQualityPolicy.isJitterHigh(recentSamples + sample)
            PowAdaptiveObfuscation.onProbe(sample > 0L, jitterHigh)
            if (jitterHigh && sample > 0L) {
                PowAdaptiveObfuscation.shouldEnableFragmentation(appContext, true)
            }
            recentSamples.add(sample)
            if (recentSamples.size > 4) recentSamples.removeAt(0)
            val degraded = if (baselineRttMs > 0L) {
                PowQualityPolicy.isDegraded(baselineRttMs, sample)
            } else {
                sample <= 0L
            }
            if (!degraded && sample > 0L) {
                badStreak = 0
                lastStableAt = SystemClock.elapsedRealtime()
                if (baselineRttMs <= 0L) {
                    baselineRttMs = sample
                } else if (sample < baselineRttMs) {
                    baselineRttMs = (baselineRttMs * 3L + sample) / 4L
                }
                // Re-prime page turbo on recovery
                if (recentSamples.count { it <= 0L } >= 2) {
                    PowPageTurbo.kick(qualityScope, socksPort)
                }
                continue
            }
            badStreak += 1
            val required = PowQualityPolicy.requiredBadStreak(recentSamples)
            if (badStreak < required) continue
            val elapsed = SystemClock.elapsedRealtime() - lastRetuneAt
            if (lastRetuneAt > 0L && elapsed < PowQualityPolicy.RETUNE_COOLDOWN_MS) continue
            if (qualityRetunes.get() >= PowQualityPolicy.MAX_QUALITY_RETUNES) continue
            badStreak = 0
            val detail = if (sample <= 0L) "probe failed streak=$required" else "rtt ${sample}ms vs ${baselineRttMs}ms jitter=$jitterHigh"
            requestQualityRetune(detail)
        }
    }

    private suspend fun requestQualityRetune(reason: String) {
        if (!retuneMutex.tryLock()) return
        try {
            if (stopRequested.get() || qualityRetunes.get() >= PowQualityPolicy.MAX_QUALITY_RETUNES) return
            qualityRetunes.incrementAndGet()
            // Level C: try ghost handover first for zero-downtime, fallback to hard retune.
            if (!proxyMode && PowTun2Socks.isRunning && ghostInFlight.compareAndSet(false, true)) {
                try {
                    val ghostOk = tryGhostHandover(reason)
                    if (ghostOk) return
                } finally {
                    ghostInFlight.set(false)
                }
            }
            retuneLocked(reason, quality = true)
        } finally {
            retuneMutex.unlock()
        }
    }

    private suspend fun tryGhostHandover(reason: String): Boolean {
        AppLogRepository.info(LogSource.POW, "Ghost handover attempt ($reason)")
        retuning.set(true)
        // Warm shadow outer on 1821 — does not disturb live 1820.
        val shadowOuter = runCatching {
            // We cannot truly run two AetherNative instances simultaneously (native singleton),
            // so ghost handover degrades to inner-only if outer is singleton-locked.
            // Attempt inner-only ghost: keep outer, rebuild inner on shadow port.
            null as String?
        }.getOrNull()
        // Fallback path: inner shadow handover (outer stays, inner flips port)
        return try {
            retuneInnerOnly("ghost:$reason")
        } catch (_: Throwable) { false }
        finally { retuning.set(false) }
    }

    private suspend fun retuneLocked(reason: String, quality: Boolean): Boolean {
        if (stopRequested.get()) return false
        if (!proxyMode && !PowTun2Socks.isRunning) return false
        retuning.set(true)
        PowSocksConnectOnly.setFailFast(true)
        // Level C: drain instead of hard drop to preserve in-flight pages.
        if (quality) PowSocksConnectOnly.drainRelaysGracefully(600)
        else PowSocksConnectOnly.dropRelays()
        AppLogRepository.info(
            LogSource.POW,
            "Refreshing UAC PoW path behind the VPN ($reason, ${if (quality) "quality" else "recovery"})",
        )
        innerReady = false
        return try {
            val forgotten = PowCoreConfig.forgetPathMemory(appContext)
            if (forgotten > 0) {
                AppLogRepository.info(LogSource.POW, "Cleared $forgotten cached WARP endpoint(s) for a fresh hop")
            }
            innerCallbacksArmed.set(false)
            innerStarted = false
            withContext(Dispatchers.IO) {
                runCatching { innerClient().stop() }
            }
            delay(350)
            if (stopRequested.get()) return false
            stopOuterLeg()
            if (stopRequested.get()) return false
            val outer = withRetuneHunt {
                raiseOuterLeg(
                    discovery = PowCoreConfig.DISCOVERY_FRESH,
                    scanMode = PowCoreConfig.SCAN_TURBO,
                    silent = true,
                    retune = true,
                )
            }
            if (outer == null) {
                AppLogRepository.warning(LogSource.POW, "Could not raise a fresh WARP hop")
                return false
            }
            if (stopRequested.get()) return false
            regionPhase = false
            ladderIndex = 0
            ladderAttempts = 0
            ladderActive.set(false)
            ladderJob?.cancel()
            val keepExit = PowRegions.egressRegion(currentSettings.exitCountryCode) != null
            val connected = CompletableDeferred<Unit>()
            retuneSignal = connected
            innerCallbacksArmed.set(true)
            startPsiphonTunnel(keepExit = keepExit)
            ladderJob = qualityScope.launch { watchLadder() }
            try {
                withTimeout(PowQualityPolicy.INNER_RETUNE_TIMEOUT_MS) { connected.await() }
            } catch (_: TimeoutCancellationException) {
                AppLogRepository.warning(LogSource.POW, "Psiphon did not reconnect through the new hop in time")
                return false
            }
            if (stopRequested.get()) return false
            // Don't drop relays on success — let pages drain
            delay(900)
            val sample = PowPathProbe.measureMs(socksPort)
            if (sample > 0L) {
                baselineRttMs = sample
                recentSamples = mutableListOf(sample)
                lastStableAt = SystemClock.elapsedRealtime()
            }
            lastRetuneAt = SystemClock.elapsedRealtime()
            PowPageTurbo.kick(qualityScope, socksPort)
            AppLogRepository.success(
                LogSource.POW,
                "UAC PoW path refreshed via $outer" + if (sample > 0L) " (${sample}ms)" else "",
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogRepository.warning(LogSource.POW, "UAC PoW path refresh failed: ${error.message}")
            false
        } finally {
            retuneSignal = null
            innerCallbacksArmed.set(true)
            PowSocksConnectOnly.setFailFast(false)
            retuning.set(false)
        }
    }

    private suspend fun <T> withRetuneHunt(block: suspend () -> T): T {
        val previousScan = runCatching { Os.getenv("AETHER_SCAN") }.getOrNull()
        val previousQuick = runCatching { Os.getenv("AETHER_QUICK_RECONNECT") }.getOrNull()
        try {
            runCatching { Os.setenv("AETHER_SCAN", "turbo", true) }
            runCatching { Os.setenv("AETHER_QUICK_RECONNECT", "0", true) }
            return block()
        } finally {
            restoreEnv("AETHER_SCAN", previousScan)
            restoreEnv("AETHER_QUICK_RECONNECT", previousQuick)
        }
    }

    private fun restoreEnv(key: String, previous: String?) {
        runCatching {
            if (previous == null) {
                Os.unsetenv(key)
            } else {
                Os.setenv(key, previous, true)
            }
        }
    }

    companion object {
        private const val OUTER_START_GRACE_MS = 4_000L
        private const val OUTER_STOP_GRACE_MS = 8_000L
        private const val INNER_CONNECT_TIMEOUT_MS = 8 * 60_000L
    }
}
