package com.uacspoofer.mobile.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.VpnService
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.CONNECTION_MODE_TUNNEL
import com.uacspoofer.mobile.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class UacVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val runtimeHealthSuccesses = AtomicLong(0L)
    @Volatile private var connectJob: Job? = null
    @Volatile private var healthJob: Job? = null
    @Volatile private var statsJob: Job? = null
    @Volatile private var latencyJob: Job? = null
    @Volatile private var adaptiveLearningJob: Job? = null
    @Volatile private var networkWatchJob: Job? = null
    @Volatile private var resourcesActive = false
    @Volatile private var activeEdge: MciEdge? = null
    @Volatile private var activeCandidate: AdaptiveCandidate? = null
    @Volatile private var activeFingerprint: NetworkFingerprint? = null
    @Volatile private var activeSignature: String? = null
    @Volatile private var activeConnectionMode = CONNECTION_MODE_TUNNEL

    private lateinit var nativeTunEngine: XrayNativeTunEngine
    private lateinit var proxyCore: MciXrayCore
    private lateinit var connectivityProbe: VpnConnectivityProbe
    private lateinit var tunConnectivityProbe: VpnConnectivityProbe
    private lateinit var dnsProbe: SocksDnsProbe
    private lateinit var adaptiveProbe: AdaptiveConnectionProbe
    private lateinit var adaptiveProfileStore: AdaptiveProfileStore
    private lateinit var adaptivePlanner: AdaptiveCandidatePlanner
    private lateinit var fingerprintResolver: NetworkFingerprintResolver
    private lateinit var advancedSettingsStore: AdvancedSettingsStore
    private lateinit var profileStore: ProfileStore

    override fun onCreate() {
        super.onCreate()
        AppLogRepository.info(LogSource.SERVICE, "Connection service created")
        createNotificationChannel()
        nativeTunEngine = XrayNativeTunEngine(this)
        proxyCore = MciXrayCore(this)
        connectivityProbe = VpnConnectivityProbe(::activeProbeStats)
        tunConnectivityProbe = VpnConnectivityProbe(::activeStats)
        dnsProbe = SocksDnsProbe()
        adaptiveProbe = AdaptiveConnectionProbe(connectivityProbe, tunConnectivityProbe, dnsProbe)
        adaptiveProfileStore = AdaptiveProfileStore(this)
        adaptivePlanner = AdaptiveCandidatePlanner(adaptiveProfileStore)
        fingerprintResolver = NetworkFingerprintResolver(this)
        advancedSettingsStore = AdvancedSettingsStore(this)
        profileStore = ProfileStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> requestDisconnect()
            ACTION_SWITCH_PROFILE -> requestSwitchProfile()
            ACTION_CONNECT, null -> requestConnect()
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        requestDisconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        AppLogRepository.info(LogSource.SERVICE, "Connection service stopping")
        generation.incrementAndGet()
        connectJob?.cancel()
        healthJob?.cancel()
        statsJob?.cancel()
        latencyJob?.cancel()
        adaptiveLearningJob?.cancel()
        networkWatchJob?.cancel()
        serviceScope.cancel()
        runBlocking(Dispatchers.IO) { runCatching { cleanupRoute() } }
        if (ConnectionStateStore.state.value != ConnectionState.ERROR) {
            ConnectionStateStore.markDisconnected()
        }
        super.onDestroy()
    }

    private fun requestConnect() {
        if (resourcesActive || connectJob?.isActive == true) return
        val settings = advancedSettingsStore.snapshot()
        activeConnectionMode = settings.connectionMode
        AppLogRepository.info(LogSource.SERVICE, "Connection requested mode=$activeConnectionMode")
        profileStore.clearActive()
        ConnectionStateStore.markConnecting()
        try {
            startForegroundNotification(connected = false)
        } catch (error: Throwable) {
            Log.e(TAG, "foreground start failed", error)
            AppLogRepository.error(LogSource.SERVICE, "Foreground service start failed", error)
            ConnectionStateStore.markError()
            stopSelf()
            return
        }

        val token = generation.incrementAndGet()
        val job = serviceScope.launch {
            try {
                val profile = profileStore.selectedProfile()
                lifecycleMutex.withLock { connectRoutes(token, settings, profile) }
            } catch (_: CancellationException) {
                
            } catch (error: Throwable) {
                Log.e(TAG, "connection worker failed", error)
                AppLogRepository.error(LogSource.SERVICE, "Connection worker failed", error)
                lifecycleMutex.withLock {
                    cleanupRoute()
                    resourcesActive = false
                }
                if (token == generation.get()) {
                    ConnectionStateStore.markError()
                    runCatching { updateFailureNotification() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        connectJob = job
        job.invokeOnCompletion { if (connectJob === job) connectJob = null }
    }

    private fun requestDisconnect() {
        AppLogRepository.info(LogSource.SERVICE, "Disconnect requested")
        ConnectionStateStore.tryBeginDisconnect()
        generation.incrementAndGet()
        val pendingConnect = connectJob
        val pendingHealth = healthJob
        val pendingStats = statsJob
        val pendingLatency = latencyJob
        val pendingLearning = adaptiveLearningJob
        val pendingNetworkWatch = networkWatchJob
        serviceScope.launch {
            pendingConnect?.cancelAndJoin()
            pendingHealth?.cancelAndJoin()
            pendingStats?.cancelAndJoin()
            pendingLatency?.cancelAndJoin()
            pendingLearning?.cancelAndJoin()
            pendingNetworkWatch?.cancelAndJoin()
            healthJob = null
            statsJob = null
            latencyJob = null
            adaptiveLearningJob = null
            networkWatchJob = null
            lifecycleMutex.withLock {
                cleanupRoute()
                resourcesActive = false
            }
            ConnectionStateStore.markDisconnected()
            AppLogRepository.info(LogSource.SERVICE, "Disconnected; connection resources released")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun requestSwitchProfile() {
        val selected = profileStore.selectedProfile()
        val active = profileStore.activeProfile()
        if (active?.id == selected.id) {
            AppLogRepository.info(LogSource.SERVICE, "Profile switch skipped; ${selected.name} is already active")
            return
        }
        if (ConnectionStateStore.state.value != ConnectionState.CONNECTED || !resourcesActive) {
            AppLogRepository.info(LogSource.SERVICE, "Profile switch deferred; ${selected.name} is selected for the next connection")
            return
        }

        AppLogRepository.info(LogSource.SERVICE, "Switching active profile to ${selected.name}")
        val token = generation.incrementAndGet()
        ConnectionStateStore.markConnecting()
        runCatching { updateNotification(connected = false) }

        val pendingHealth = healthJob
        val pendingStats = statsJob
        val pendingLatency = latencyJob
        val pendingLearning = adaptiveLearningJob
        val pendingNetworkWatch = networkWatchJob
        val job = serviceScope.launch {
            try {
                pendingHealth?.cancelAndJoin()
                pendingStats?.cancelAndJoin()
                pendingLatency?.cancelAndJoin()
                pendingLearning?.cancelAndJoin()
                pendingNetworkWatch?.cancelAndJoin()
                healthJob = null
                statsJob = null
                latencyJob = null
                adaptiveLearningJob = null
                networkWatchJob = null
                lifecycleMutex.withLock {
                    cleanupRoute()
                    resourcesActive = false
                    profileStore.clearActive()
                    val settings = advancedSettingsStore.snapshot()
                    val latestSelection = profileStore.selectedProfile()
                    AppLogRepository.info(LogSource.SERVICE, "Reconnecting with ${latestSelection.name}")
                    connectRoutes(token, settings, latestSelection)
                }
            } catch (_: CancellationException) {
                
            } catch (error: Throwable) {
                Log.e(TAG, "profile switch failed", error)
                AppLogRepository.error(LogSource.SERVICE, "Profile switch failed", error)
                lifecycleMutex.withLock {
                    runCatching { cleanupRoute() }
                    resourcesActive = false
                }
                if (token == generation.get()) {
                    ConnectionStateStore.markError()
                    runCatching { updateFailureNotification() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        connectJob = job
        job.invokeOnCompletion { if (connectJob === job) connectJob = null }
    }

    private suspend fun connectRoutes(
        token: Long,
        settings: AdvancedSettingsData,
        profile: ProxyProfile,
    ) {
        activeConnectionMode = settings.connectionMode
        var lastFailure: Throwable? = null
        var bestReport: AdaptiveProbeReport? = null
        val fingerprint = fingerprintResolver.captureAdaptive()
        val signature = adaptivePlanner.signature(settings, profile)
        val candidates = adaptivePlanner.candidates(settings, fingerprint, profile)
        AppLogRepository.info(LogSource.SERVICE, "Selected ${profile.protocol.name} profile: ${profile.name}")
        AppLogRepository.info(LogSource.ADAPTIVE, "Session=$token network fingerprint ${fingerprint.summary()}")
        AppLogRepository.info(
            LogSource.ADAPTIVE,
            "Planner signature=$signature candidates=${candidates.joinToString(",") { it.id }}",
        )
        for ((index, candidate) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            if (token != generation.get()) throw CancellationException("stale connect generation")
            try {
                cleanupRoute()
                val edge = candidate.edge
                val candidateSettings = candidate.settings
                Log.i(TAG, "starting adaptive candidate ${candidate.id} ${edge.role}=${edge.address}:${edge.port}")
                AppLogRepository.info(
                    LogSource.ADAPTIVE,
                    "Candidate ${index + 1}/${candidates.size} start ${candidate.summary()}",
                )
                activeConnectionMode = candidateSettings.connectionMode
                if (isProxyMode()) {
                    val timing = proxyCore.start(edge, candidateSettings, profile, candidate.runtimeOptions)
                    AppLogRepository.info(
                        LogSource.PROXY,
                        "Local SOCKS5 ready ${candidateSettings.socksAddress}:${candidateSettings.socksPort} " +
                            "config=${timing.configPrepareMs}ms core=${timing.coreStartupMs}ms ready=${timing.proxyReadyMs}ms",
                    )
                } else {
                    nativeTunEngine.start(
                        edge = edge,
                        settings = candidateSettings,
                        profile = profile,
                        runtimeOptions = candidate.runtimeOptions,
                    ) { establishTun(candidateSettings) }
                }
                delay(ADAPTIVE_PROBE_WARMUP_MS)
                val report = adaptiveProbe.verify(candidate)
                if (bestReport == null || report.score > bestReport!!.score) bestReport = report
                AppLogRepository.info(LogSource.ADAPTIVE, "Candidate ${candidate.id} result ${report.detail()}")
                check(report.accepted) { report.detail() }
                coroutineContext.ensureActive()
                if (token != generation.get()) throw CancellationException("stale connect generation")

                resourcesActive = true
                runtimeHealthSuccesses.set(0L)
                activeEdge = edge
                activeCandidate = candidate
                activeFingerprint = fingerprint
                activeSignature = signature
                profileStore.markActive(
                    profile.id,
                    com.uacspoofer.mobile.profiles.ProfileEndpoint(edge.address, edge.port),
                )
                if (!ConnectionStateStore.markConnected()) {
                    cleanupRoute()
                    resourcesActive = false
                    return
                }
                adaptiveProfileStore.recordWinner(
                    network = fingerprint,
                    profile = profile,
                    signature = signature,
                    candidate = candidate,
                    score = report.score,
                )
                AppLogRepository.info(
                    LogSource.ADAPTIVE,
                    "Stored probe winner ${candidate.id} fingerprint=${fingerprint.key} " +
                        "cohort=${fingerprint.learningKey()} score=${report.score}",
                )
                Log.i(TAG, "adaptive connectivity gate passed on ${candidate.id}: ${report.detail()}")
                AppLogRepository.info(
                    LogSource.SERVICE,
                    "Connected with ${candidate.label}: score=${report.score}, ${report.http.detail}",
                )
                runCatching { updateNotification(connected = true) }
                    .onFailure { Log.w(TAG, "connected notification update failed", it) }
                startHealthMonitor(token)
                startStatsMonitor(token)
                startLatencySampler(token)
                startAdaptiveLearningMonitor(token, candidate, fingerprint, profile, signature, report.score)
                startNetworkWatch(token, fingerprint)
                return
            } catch (cancelled: CancellationException) {
                cleanupRoute()
                resourcesActive = false
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = error
                adaptiveProfileStore.recordFailure(fingerprint, profile, signature, candidate.id)
                Log.w(TAG, "adaptive candidate ${candidate.id} failed", error)
                AppLogRepository.warning(LogSource.ADAPTIVE, "Candidate ${candidate.id} rejected", error)
                cleanupRoute()
                resourcesActive = false
                if (index + 1 < candidates.size) {
                    val settleDelayMs = candidateRouteSettleDelayMs(fingerprint.transport)
                    delay(settleDelayMs)
                    AppLogRepository.debug(
                        LogSource.ADAPTIVE,
                        "Underlying ${fingerprint.transport} route settled for ${settleDelayMs}ms " +
                            "before candidate ${index + 2}/${candidates.size}",
                    )
                }
            }
        }

        Log.e(TAG, "all adaptive candidates failed", lastFailure)
        AppLogRepository.error(
            LogSource.SERVICE,
            "All adaptive candidates failed; best=${bestReport?.detail() ?: "none"}",
            lastFailure,
        )
        if (token == generation.get()) {
            ConnectionStateStore.markError()
            runCatching { updateFailureNotification() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun establishTun(settings: AdvancedSettingsData): android.os.ParcelFileDescriptor? {
        val route = TunRouteParser.parse(settings.tunRoute)
        AppLogRepository.debug(
            LogSource.TUN,
            "Establish request mtu=${settings.tunMtu} address=${settings.tunAddress} route=${settings.tunRoute} " +
                "dns=${settings.nativeDns} ipv4Only=${settings.ipv4Only}",
        )
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setBlocking(false)
            .setMtu(settings.tunMtu)
            .addAddress(settings.tunAddress, 32)
            .addRoute(route.first, route.second)
            .addDnsServer(settings.nativeDns)
            .apply { if (settings.ipv4Only) allowFamily(OsConstants.AF_INET) }

        AppRoutingPreferences.applyTo(builder, this)
        return builder.establish()
    }

    private suspend fun cleanupRoute() {
        profileStore.clearActive()
        adaptiveLearningJob?.cancel()
        adaptiveLearningJob = null
        networkWatchJob?.cancel()
        networkWatchJob = null
        statsJob?.cancel()
        statsJob = null
        latencyJob?.cancel()
        latencyJob = null
        nativeTunEngine.stop()
        proxyCore.stop()
        activeEdge = null
        activeCandidate = null
        activeFingerprint = null
        activeSignature = null
        runtimeHealthSuccesses.set(0L)
        ConnectionMetricsStore.reset()
        TrafficStatsStore.reset()
    }

    private fun startLatencySampler(token: Long) {
        latencyJob?.cancel()
        ConnectionMetricsStore.beginLatencyMeasurement()
        val job = serviceScope.launch {
            try {
                repeat(LATENCY_SAMPLE_COUNT) { index ->
                    if (token != generation.get() || !resourcesActive) return@launch
                    val probe = connectivityProbe.verifyRuntime()
                    if (probe.success) ConnectionMetricsStore.addLatencySample(probe.latencyMs)
                    if (index + 1 < LATENCY_SAMPLE_COUNT) delay(LATENCY_SAMPLE_DELAY_MS)
                }
                if (token == generation.get() && resourcesActive) {
                    ConnectionMetricsStore.finishLatencyMeasurement()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogRepository.warning(LogSource.SERVICE, "Latency sampling failed", error)
                if (token == generation.get() && resourcesActive) {
                    ConnectionMetricsStore.finishLatencyMeasurement()
                }
            }
        }
        latencyJob = job
        job.invokeOnCompletion { if (latencyJob === job) latencyJob = null }
    }

    private fun activeStats(): TunStats = if (isProxyMode()) TunStats.ZERO else nativeTunEngine.stats()

    private fun activeProbeStats(): TunStats = if (isProxyMode()) TunStats.ZERO else nativeTunEngine.probeStats()

    private fun activeCoreRunning(): Boolean = if (isProxyMode()) proxyCore.isRunning() else nativeTunEngine.isRunning()

    private fun isProxyMode(): Boolean = activeConnectionMode == CONNECTION_MODE_PROXY

    private fun activeModeLabel(): String = if (isProxyMode()) "proxy" else "tunnel"

    private fun startAdaptiveLearningMonitor(
        token: Long,
        candidate: AdaptiveCandidate,
        fingerprint: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        initialScore: Int,
    ) {
        adaptiveLearningJob?.cancel()
        val initialStats = activeStats()
        val job = serviceScope.launch {
            delay(ADAPTIVE_STABILITY_WINDOW_MS)
            if (token != generation.get() || !resourcesActive || activeCandidate?.id != candidate.id) return@launch
            val currentNetwork = fingerprintResolver.captureAdaptive()
            if (!currentNetwork.isSameUnderlyingNetwork(fingerprint)) {
                AppLogRepository.warning(
                    LogSource.ADAPTIVE,
                    "Learning skipped for ${candidate.id}; network changed ${fingerprint.key}->${currentNetwork.key}",
                )
                return@launch
            }
            val currentStats = activeStats()
            val stable = if (candidate.settings.connectionMode == CONNECTION_MODE_PROXY) {
                try {
                    val http = connectivityProbe.verifyRuntime()
                    val dns = dnsProbe.verify(candidate.settings)
                    AppLogRepository.debug(
                        LogSource.ADAPTIVE,
                        "Proxy stability gate candidate=${candidate.id} http=${http.success} dns=${dns.success} " +
                            "detail=[${http.detail}; ${dns.detail}]",
                    )
                    http.success && dns.success
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogRepository.warning(LogSource.ADAPTIVE, "Proxy stability gate failed", error)
                    false
                }
            } else {
                currentStats.hasBidirectionalGrowthSince(initialStats) || runtimeHealthSuccesses.get() >= 2L
            }
            if (!activeCoreRunning()) {
                adaptiveProfileStore.recordFailure(fingerprint, profile, signature, candidate.id)
                AppLogRepository.warning(
                    LogSource.ADAPTIVE,
                    "Candidate ${candidate.id} was not learned because the ${activeModeLabel()} core stopped",
                )
                return@launch
            }
            if (!stable) {
                AppLogRepository.info(
                    LogSource.ADAPTIVE,
                    "Learning deferred for ${candidate.id}; ${activeModeLabel()} stability gate was not satisfied",
                )
                return@launch
            }
            adaptiveProfileStore.recordWinner(currentNetwork, profile, signature, candidate, initialScore)
            if (currentNetwork.learningKey() != fingerprint.learningKey()) {
                adaptiveProfileStore.recordWinner(fingerprint, profile, signature, candidate, initialScore)
            }
            AppLogRepository.info(
                LogSource.ADAPTIVE,
                "Learned stable candidate ${candidate.id} mode=${candidate.settings.connectionMode} " +
                    "network=${currentNetwork.key} cohort=${currentNetwork.learningKey()} score=$initialScore " +
                    "healthPasses=${runtimeHealthSuccesses.get()} tx=${currentStats.txBytes - initialStats.txBytes} " +
                    "rx=${currentStats.rxBytes - initialStats.rxBytes}",
            )
        }
        adaptiveLearningJob = job
        job.invokeOnCompletion { if (adaptiveLearningJob === job) adaptiveLearningJob = null }
    }

    private fun startNetworkWatch(token: Long, initial: NetworkFingerprint) {
        networkWatchJob?.cancel()
        val job = serviceScope.launch {
            var mismatchKey: String? = null
            var mismatchCount = 0
            var observedIdentity = "${initial.carrierClass}|${initial.networkAsn}|${initial.networkProvider}"
            while (true) {
                delay(NETWORK_WATCH_INTERVAL_MS)
                if (token != generation.get() || !resourcesActive) return@launch
                val current = fingerprintResolver.captureAdaptive()
                if (current.isSameUnderlyingNetwork(initial)) {
                    val currentIdentity = "${current.carrierClass}|${current.networkAsn}|${current.networkProvider}"
                    if (currentIdentity != observedIdentity) {
                        observedIdentity = currentIdentity
                        AppLogRepository.info(
                            LogSource.ADAPTIVE,
                            "Underlying network metadata updated without reconnect carrier=${current.carrierClass} " +
                                "asn=${current.networkAsn} provider=${current.networkProvider}",
                        )
                    }
                    mismatchKey = null
                    mismatchCount = 0
                    continue
                }
                val currentMismatchKey = "${current.networkHandle}:${current.key}"
                if (mismatchKey == currentMismatchKey) {
                    mismatchCount += 1
                } else {
                    mismatchKey = currentMismatchKey
                    mismatchCount = 1
                }
                AppLogRepository.debug(
                    LogSource.ADAPTIVE,
                    "Underlying network mismatch sample=$mismatchCount old=${initial.key} new=${current.key} transport=${current.transport}",
                )
                if (mismatchCount >= NETWORK_CHANGE_CONFIRMATIONS) {
                    AppLogRepository.info(
                        LogSource.ADAPTIVE,
                        "Underlying network changed; adaptive reconnect old=${initial.key} new=${current.key}",
                    )
                    scheduleRuntimeRecovery(token, "underlying network changed", penalizeCandidate = false)
                    return@launch
                }
            }
        }
        networkWatchJob = job
        job.invokeOnCompletion { if (networkWatchJob === job) networkWatchJob = null }
    }

    private fun startStatsMonitor(token: Long) {
        statsJob?.cancel()
        TrafficStatsStore.reset()
        val job = serviceScope.launch {
            while (true) {
                if (token != generation.get() || !resourcesActive) return@launch
                TrafficStatsStore.update(activeStats(), SystemClock.elapsedRealtime())
                delay(STATS_INTERVAL_MS)
            }
        }
        statsJob = job
        job.invokeOnCompletion { if (statsJob === job) statsJob = null }
    }

    private fun startHealthMonitor(token: Long) {
        healthJob?.cancel()
        val job = serviceScope.launch {
            val guard = RuntimeHealthGuard(MciConfig.RUNTIME_HEALTH_MAX_FAILURES)
            var nextDelayMs = POST_CONNECT_HEALTH_DELAY_MS
            var previousStats = activeStats()
            while (true) {
                delay(nextDelayMs)
                if (token != generation.get() || !resourcesActive) return@launch

                if (!activeCoreRunning()) {
                    AppLogRepository.warning(LogSource.SERVICE, "Active ${activeModeLabel()} core exited; recovering")
                    scheduleRuntimeRecovery(token, "active ${activeModeLabel()} core exited")
                    return@launch
                }

                val currentStats = activeStats()
                val hasUplink = currentStats.hasUplinkGrowthSince(previousStats)
                val hasDownlink = currentStats.hasDownlinkGrowthSince(previousStats)
                if (hasDownlink) {
                    previousStats = currentStats
                    guard.recordHealthy()
                    runtimeHealthSuccesses.incrementAndGet()
                    nextDelayMs = MciConfig.HEALTH_CHECK_INTERVAL_MS
                    AppLogRepository.debug(
                        LogSource.TUN,
                        "Runtime user traffic healthy tx=${currentStats.txBytes} rx=${currentStats.rxBytes}",
                    )
                    continue
                }
                previousStats = currentStats

                val settings = activeCandidate?.settings ?: advancedSettingsStore.snapshot()
                val control = try {
                    val http = connectivityProbe.verifyRuntime()
                    val dns = dnsProbe.verify(settings)
                    val tun = if (isProxyMode()) null else tunConnectivityProbe.verifyTunRuntime()
                    val tunReady = tun == null || tun.success || tun.hasSuccessfulPayload()
                    val dnsReady = dns.success || (!isProxyMode() && http.success && tunReady)
                    RuntimeControlGate(
                        healthy = isRuntimeControlHealthy(isProxyMode(), http, dns, tun),
                        latencyMs = tun?.latencyMs ?: http.latencyMs,
                        detail = "http=[${http.detail}] dns=[${dns.detail}] " +
                            "dnsDegraded=${!dns.success && dnsReady} " +
                            "tun=[${tun?.detail ?: "proxy-mode"}] tunCounters=${tun?.success ?: true}",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    RuntimeControlGate(false, null, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                }

                if (control.healthy) {
                    guard.recordHealthy()
                    runtimeHealthSuccesses.incrementAndGet()
                    ConnectionMetricsStore.updateLatency(control.latencyMs)
                    nextDelayMs = MciConfig.HEALTH_CHECK_INTERVAL_MS
                    AppLogRepository.debug(
                        LogSource.SERVICE,
                        "Runtime control gate passed uplinkOnly=$hasUplink ${control.detail}",
                    )
                    continue
                }

                val failureDetail = if (hasUplink) {
                    "user TUN uplink advanced without downlink and control gate failed; ${control.detail}"
                } else {
                    control.detail
                }

                when (guard.recordFailure(coreRunning = true)) {
                    RuntimeHealthAction.KEEP_CONNECTED -> {
                        nextDelayMs = MciConfig.RUNTIME_HEALTH_RETRY_DELAY_MS
                        AppLogRepository.warning(
                            LogSource.SERVICE,
                            "Transient health failure ${guard.consecutiveFailures}/${MciConfig.RUNTIME_HEALTH_MAX_FAILURES}; ${activeModeLabel()} kept active: $failureDetail",
                        )
                    }
                    RuntimeHealthAction.RECOVER -> {
                        AppLogRepository.warning(
                            LogSource.SERVICE,
                            "Runtime health failed ${guard.consecutiveFailures} times; reconnecting: $failureDetail",
                        )
                        scheduleRuntimeRecovery(token, failureDetail)
                        return@launch
                    }
                }
            }
        }
        healthJob = job
        job.invokeOnCompletion { if (healthJob === job) healthJob = null }
    }

    
    private fun scheduleRuntimeRecovery(
        failedToken: Long,
        reason: String,
        penalizeCandidate: Boolean = true,
    ) {
        val recoveryToken = failedToken + 1L
        if (!generation.compareAndSet(failedToken, recoveryToken)) return

        val failedEdge = activeEdge
        val failedCandidate = activeCandidate
        val failedFingerprint = activeFingerprint
        val failedSignature = activeSignature
        val profile = profileStore.activeProfile() ?: profileStore.selectedProfile()
        if (penalizeCandidate && failedCandidate != null && failedFingerprint != null && failedSignature != null) {
            adaptiveProfileStore.recordFailure(failedFingerprint, profile, failedSignature, failedCandidate.id)
            AppLogRepository.warning(
                LogSource.ADAPTIVE,
                "Runtime failure recorded candidate=${failedCandidate.id} network=${failedFingerprint.key} reason=$reason",
            )
        }
        ConnectionStateStore.markConnecting()
        runCatching { updateNotification(connected = false) }
        AppLogRepository.warning(
            LogSource.SERVICE,
            "Self-healing connection${failedEdge?.let { " from ${it.role}" }.orEmpty()}: $reason",
        )

        val job = serviceScope.launch {
            try {
                lifecycleMutex.withLock {
                    if (recoveryToken != generation.get()) return@withLock
                    cleanupRoute()
                    resourcesActive = false
                    delay(MciConfig.RUNTIME_RECOVERY_BACKOFF_MS)
                    if (recoveryToken != generation.get()) throw CancellationException("stale recovery generation")
                    connectRoutes(recoveryToken, advancedSettingsStore.snapshot(), profile)
                }
            } catch (_: CancellationException) {
                
            } catch (error: Exception) {
                Log.e(TAG, "runtime recovery failed", error)
                AppLogRepository.error(LogSource.SERVICE, "Runtime recovery failed", error)
                lifecycleMutex.withLock {
                    runCatching { cleanupRoute() }
                    resourcesActive = false
                }
                if (recoveryToken == generation.get()) {
                    ConnectionStateStore.markError()
                    runCatching { updateFailureNotification() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        connectJob = job
        job.invokeOnCompletion { if (connectJob === job) connectJob = null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.vpn_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification(connected: Boolean) {
        val notification = buildNotification(
            connectionNotificationText(connected),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(connected: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(
                connectionNotificationText(connected),
            ),
        )
    }

    private fun connectionNotificationText(connected: Boolean): Int = when {
        isProxyMode() && connected -> R.string.proxy_connected_notification
        isProxyMode() -> R.string.proxy_connecting_notification
        connected -> R.string.vpn_connected_notification
        else -> R.string.vpn_connecting_notification
    }

    private fun updateFailureNotification() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(R.string.vpn_failed_notification),
        )
    }

    private fun buildNotification(textRes: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.uacspoofer.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.uacspoofer.mobile.DISCONNECT"
        const val ACTION_SWITCH_PROFILE = "com.uacspoofer.mobile.SWITCH_PROFILE"

        private const val TAG = "UAC-MCI"
        private const val NOTIFICATION_CHANNEL = "uac_mci_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val STATS_INTERVAL_MS = 1_000L
        private const val LATENCY_SAMPLE_COUNT = 3
        private const val LATENCY_SAMPLE_DELAY_MS = 350L
        private const val ADAPTIVE_STABILITY_WINDOW_MS = 60_000L
        private const val ADAPTIVE_PROBE_WARMUP_MS = 800L
        private const val POST_CONNECT_HEALTH_DELAY_MS = 8_000L
        private const val NETWORK_WATCH_INTERVAL_MS = 3_000L
        private const val NETWORK_CHANGE_CONFIRMATIONS = 2

    }
}

private data class RuntimeControlGate(
    val healthy: Boolean,
    val latencyMs: Long?,
    val detail: String,
)
