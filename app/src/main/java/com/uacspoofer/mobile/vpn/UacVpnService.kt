package com.uacspoofer.mobile.vpn

import android.app.Notification
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.net.VpnService
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.tor.TorConnectionCoordinator
import com.uacspoofer.mobile.engine.tor.TorDaemon
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.TorPhase
import com.uacspoofer.mobile.engine.tor.TorTunRelayConfig
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.RouteTransferMeasurementMode
import com.uacspoofer.mobile.profiles.RouteTransferProbe
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.CONNECTION_MODE_TUNNEL
import com.uacspoofer.mobile.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    @Volatile private var routeProbeJob: Job? = null
    @Volatile private var activeRouteProbeId: String? = null
    @Volatile private var activeRouteProbeStartId: Int? = null
    @Volatile private var resourcesActive = false
    @Volatile private var activeEdge: MciEdge? = null
    @Volatile private var activeCandidate: AdaptiveCandidate? = null
    @Volatile private var activeFingerprint: NetworkFingerprint? = null
    @Volatile private var activeSignature: String? = null
    @Volatile private var activeConnectionMode = CONNECTION_MODE_TUNNEL
    @Volatile private var activeEngine = EngineMode.XRAY_CF

    private lateinit var nativeTunEngine: XrayNativeTunEngine
    private lateinit var proxyCore: MciXrayCore
    private lateinit var connectivityProbe: VpnConnectivityProbe
    private lateinit var tunConnectivityProbe: VpnConnectivityProbe
    private lateinit var dnsProbe: SocksDnsProbe
    private lateinit var adaptiveProbe: AdaptiveConnectionProbe
    private lateinit var adaptiveProfileStore: AdaptiveProfileStore
    private lateinit var connectEdgePoolStore: ConnectEdgePoolStore
    private lateinit var adaptivePlanner: AdaptiveCandidatePlanner
    private lateinit var fingerprintResolver: NetworkFingerprintResolver
    private lateinit var advancedSettingsStore: AdvancedSettingsStore
    private lateinit var profileStore: ProfileStore
    private lateinit var latencyTester: com.uacspoofer.mobile.profiles.ProfileLatencyTester
    private lateinit var engineModeStore: EngineModeStore
    private lateinit var torEngineStore: TorEngineStore
    private lateinit var torCoordinator: TorConnectionCoordinator

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
        connectEdgePoolStore = ConnectEdgePoolStore(this)
        adaptivePlanner = AdaptiveCandidatePlanner(adaptiveProfileStore, connectEdgePoolStore)
        fingerprintResolver = NetworkFingerprintResolver(this)
        advancedSettingsStore = AdvancedSettingsStore(this)
        profileStore = ProfileStore(this)
        latencyTester = com.uacspoofer.mobile.profiles.ProfileLatencyTester(this)
        engineModeStore = EngineModeStore.get(this)
        torEngineStore = TorEngineStore.get(this)
        torCoordinator = TorConnectionCoordinator(
            context = this,
            daemon = TorDaemon(this),
            engineStore = torEngineStore,
            fingerprintResolver = fingerprintResolver,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> requestDisconnect()
            ACTION_CLOSE -> requestDisconnect(closeAppTasks = true)
            ACTION_SWITCH_PROFILE -> requestSwitchProfile()
            ACTION_APPLY_TOR_EXIT -> requestApplyTorExit()
            ACTION_REFRESH_LATENCY -> requestLatencyRefresh()
            ACTION_ROUTE_MTU_PROBE -> requestRouteMtuProbe(intent.getStringExtra(EXTRA_ROUTE_PROBE_ID), startId)
            ACTION_CANCEL_ROUTE_MTU_PROBE -> cancelRouteMtuProbe(intent.getStringExtra(EXTRA_ROUTE_PROBE_ID), startId)
            ACTION_CONNECT, null -> requestConnect()
        }
        return Service.START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        requestDisconnect()
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLogRepository.info(LogSource.SERVICE, "App task removed; disconnecting active connection")
        requestDisconnect()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLogRepository.info(LogSource.SERVICE, "Connection service stopping")
        ConnectRescueStore.hide()
        generation.incrementAndGet()
        connectJob?.cancel()
        healthJob?.cancel()
        statsJob?.cancel()
        latencyJob?.cancel()
        adaptiveLearningJob?.cancel()
        networkWatchJob?.cancel()
        val interruptedRouteProbeId = activeRouteProbeId
        routeProbeJob?.cancel()
        serviceScope.cancel()
        runBlocking(Dispatchers.IO) { runCatching { cleanupRoute() } }
        interruptedRouteProbeId?.let {
            RouteMtuProbeCoordinator.unregisterCancellation(it)
            RouteMtuProbeCoordinator.fail(it, CancellationException("VPN service stopped"))
        }
        activeRouteProbeId = null
        activeRouteProbeStartId = null
        routeProbeJob = null
        if (ConnectionStateStore.state.value != ConnectionState.ERROR) {
            ConnectionStateStore.markDisconnected()
        }
        super.onDestroy()
    }

    private fun requestRouteMtuProbe(requestId: String?, startId: Int) {
        val id = requestId?.takeIf(String::isNotBlank)
        val request = id?.let(RouteMtuProbeCoordinator::request)
        if (id == null || request == null) {
            if (routeProbeJob?.isActive == true) {
                activeRouteProbeStartId = startId
            } else if (!resourcesActive && connectJob?.isActive != true) {
                finishIdleForegroundStart(startId)
            }
            return
        }
        if (resourcesActive || connectJob?.isActive == true) {
            RouteMtuProbeCoordinator.fail(id, RouteProbeBusyException("VPN service is already busy"))
            return
        }
        if (routeProbeJob?.isActive == true) {
            activeRouteProbeStartId = startId
            RouteMtuProbeCoordinator.fail(id, RouteProbeBusyException("VPN service is already busy"))
            return
        }
        if (ConnectionStateStore.state.value !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
            finishIdleForegroundStart(startId)
            RouteMtuProbeCoordinator.fail(id, RouteProbeBusyException("Disconnect before native MTU validation"))
            return
        }
        activeConnectionMode = CONNECTION_MODE_TUNNEL
        try {
            startForegroundNotification(connected = false)
        } catch (error: Throwable) {
            RouteMtuProbeCoordinator.fail(id, error)
            stopSelfResult(startId)
            return
        }
        activeRouteProbeId = id
        activeRouteProbeStartId = startId
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var completedResult: RouteNativeMtuProbeResult? = null
            var completionError: Throwable? = null
            try {
                completedResult = lifecycleMutex.withLock { performRouteMtuProbe(request) }
            } catch (cancelled: CancellationException) {
                completionError = cancelled
            } catch (error: Throwable) {
                AppLogRepository.warning(LogSource.TUN, "Native Route Speed MTU probe failed", error)
                completionError = error
            } finally {
                val runningJob = coroutineContext[Job]
                withContext(NonCancellable) {
                    lifecycleMutex.withLock {
                        if (activeRouteProbeId == id) runCatching { nativeTunEngine.stop() }
                    }
                    val stopStartId = activeRouteProbeStartId ?: startId
                    if (activeRouteProbeId == id) {
                        activeRouteProbeId = null
                        activeRouteProbeStartId = null
                    }
                    if (routeProbeJob === runningJob) routeProbeJob = null
                    RouteMtuProbeCoordinator.unregisterCancellation(id)
                    val stopped = stopSelfResult(stopStartId)
                    if (stopped && connectJob?.isActive != true && !resourcesActive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    when (val error = completionError) {
                        is CancellationException -> RouteMtuProbeCoordinator.cancel(id)
                        null -> RouteMtuProbeCoordinator.complete(id, checkNotNull(completedResult))
                        else -> RouteMtuProbeCoordinator.fail(id, error)
                    }
                }
            }
        }
        routeProbeJob = job
        RouteMtuProbeCoordinator.registerCancellation(id) { job.cancel() }
        job.start()
    }

    private suspend fun performRouteMtuProbe(
        request: RouteNativeMtuProbeRequest,
    ): RouteNativeMtuProbeResult {
        val underlyingNetwork = checkNotNull(request.underlyingNetwork) {
            "Native MTU validation has no underlying network"
        }
        val currentNetwork = fingerprintResolver.captureAdaptiveContext()
        check(
            currentNetwork.fingerprint.exactStorageKey() == request.expectedNetworkKey &&
                currentNetwork.network?.networkHandle == underlyingNetwork.networkHandle,
        ) {
            "Network changed before native MTU validation"
        }
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        check(connectivityManager.getNetworkCapabilities(underlyingNetwork) != null) {
            "Underlying network disappeared before native MTU validation"
        }
        val existingVpnNetworks = vpnNetworks(connectivityManager).toSet()
        val candidate = request.candidate
        val settings = candidate.settings.copy(connectionMode = CONNECTION_MODE_TUNNEL).validated()
        nativeTunEngine.start(
            edge = candidate.edge,
            settings = settings,
            profile = request.profile,
            runtimeOptions = candidate.runtimeOptions.copy(quietLogging = true),
        ) { establishRouteProbeTun(settings, underlyingNetwork) }
        val vpnNetwork = awaitRouteProbeNetwork(connectivityManager, existingVpnNetworks)
        delay(ROUTE_MTU_PROBE_WARMUP_MS)
        val before = nativeTunEngine.stats()
        val dns = dnsProbe.verifyOnNetwork(settings, vpnNetwork)
        val http = tunConnectivityProbe.verifyTunCandidate(vpnNetwork)
        val transfer = RouteTransferProbe().measure(
            measurementMode = RouteTransferMeasurementMode.NATIVE_TUN,
            network = vpnNetwork,
            config = request.transferConfig,
        )
        var after = nativeTunEngine.stats()
        repeat(2) {
            if (after.txBytes <= before.txBytes || after.rxBytes <= before.rxBytes) {
                delay(ROUTE_STATS_SETTLE_MS)
                after = nativeTunEngine.stats()
            }
        }
        val txDelta = (after.txBytes - before.txBytes).coerceAtLeast(0L)
        val rxDelta = (after.rxBytes - before.rxBytes).coerceAtLeast(0L)
        val transferAccepted = transfer.success
        val networkAfterProbe = fingerprintResolver.captureAdaptiveContext()
        val networkStable = networkAfterProbe.fingerprint.exactStorageKey() == request.expectedNetworkKey &&
            networkAfterProbe.network?.networkHandle == underlyingNetwork.networkHandle &&
            connectivityManager.getNetworkCapabilities(underlyingNetwork) != null
        val accepted = http.success && dns.success && transferAccepted && txDelta > 0L && rxDelta > 0L && networkStable
        val detail = buildString {
            append("nativeMtu=${settings.tunMtu}")
            append(" http=${http.succeededTargets}/${http.attemptedTargets}")
            append(" dns=${dns.answerCount}@${dns.latencyMs ?: -1}ms")
            append(" upload=${transfer.uploadBytes}@${transfer.uploadKbps}Kbps")
            append(" download=${transfer.downloadBytes}@${transfer.downloadKbps}Kbps")
            append(" txDelta=$txDelta rxDelta=$rxDelta networkStable=$networkStable accepted=$accepted")
            transfer.endpointFailure?.let { append(" endpoint=${it.kind}:${it.statusCode}") }
            transfer.candidateFailure?.let { append(" failure=[$it]") }
        }
        AppLogRepository.info(LogSource.TUN, "Route Speed native result candidate=${candidate.id} $detail")
        return RouteNativeMtuProbeResult(
            accepted = accepted,
            transfer = transfer,
            httpSucceeded = http.succeededTargets,
            httpAttempted = http.attemptedTargets,
            httpDetail = http.detail,
            dnsSucceeded = dns.success,
            dnsLatencyMs = dns.latencyMs.takeIf { dns.success },
            dnsAnswers = dns.answerCount,
            txDelta = txDelta,
            rxDelta = rxDelta,
            detail = detail,
        )
    }

    private fun establishRouteProbeTun(
        settings: AdvancedSettingsData,
        underlyingNetwork: Network,
    ): android.os.ParcelFileDescriptor? {
        val route = TunRouteParser.parse(settings.tunRoute)
        return Builder()
            .setSession("UAC Route MTU Probe")
            .setBlocking(false)
            .setMtu(settings.tunMtu)
            .addAddress(settings.tunAddress, 32)
            .addRoute(route.first, route.second)
            .addDnsServer(settings.nativeDns)
            .addDisallowedApplication(packageName)
            .apply {
                setUnderlyingNetworks(arrayOf(underlyingNetwork))
                if (settings.ipv4Only) allowFamily(OsConstants.AF_INET)
            }
            .establish()
    }

    private suspend fun awaitRouteProbeNetwork(
        connectivityManager: ConnectivityManager,
        existingVpnNetworks: Set<Network>,
    ): Network {
        val deadline = SystemClock.elapsedRealtime() + ROUTE_VPN_NETWORK_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val available = vpnNetworks(connectivityManager)
            available.firstOrNull { it !in existingVpnNetworks }?.let { return it }
            delay(ROUTE_VPN_NETWORK_POLL_MS)
        }
        error("Android did not expose a new Route MTU VPN network")
    }

    private fun vpnNetworks(connectivityManager: ConnectivityManager): List<Network> =
        connectivityManager.allNetworks.filter { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

    private fun finishIdleForegroundStart(startId: Int) {
        val foregroundStarted = runCatching { startForegroundNotification(connected = false) }.isSuccess
        val stopped = stopSelfResult(startId)
        if (foregroundStarted && stopped) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun cancelRouteMtuProbe(requestId: String?, startId: Int) {
        val id = requestId
        if (id == null || activeRouteProbeId != id) {
            if (!resourcesActive && connectJob?.isActive != true && routeProbeJob?.isActive != true) {
                stopSelfResult(startId)
            }
            return
        }
        activeRouteProbeStartId = startId
        routeProbeJob?.cancel()
    }

    private fun requestConnect() {
        activeRouteProbeId?.let {
            routeProbeJob?.cancel()
        }
        if (resourcesActive || connectJob?.isActive == true) return
        val settings = advancedSettingsStore.snapshot()
        activeConnectionMode = settings.connectionMode
        activeEngine = engineModeStore.snapshot()
        ConnectRescueStore.hide()
        AppLogRepository.info(
            LogSource.SERVICE,
            "Connection requested engine=${activeEngine.id} mode=$activeConnectionMode",
        )
        if (activeEngine.isXray) {
            profileStore.clearActive()
        }
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
                lifecycleMutex.withLock {
                    if (activeEngine.isTor) {
                        connectTorEngine(token, settings)
                    } else {
                        val profile = profileStore.selectedProfile()
                        connectRoutes(token, settings, profile)
                    }
                }
            } catch (_: CancellationException) {
                ConnectRescueStore.hide()
                if (
                    activeEngine.isTor &&
                    ConnectionStateStore.state.value != ConnectionState.CONNECTING
                ) {
                    TorStatusStore.reset()
                }
            } catch (error: Throwable) {
                ConnectRescueStore.hide()
                if (activeEngine.isTor) {
                    TorStatusStore.update(TorPhase.FAILED, 0, error.message.orEmpty().ifBlank { "Tor connect failed" })
                }
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

    private fun requestDisconnect(closeAppTasks: Boolean = false) {
        AppLogRepository.info(LogSource.SERVICE, "Disconnect requested")
        ConnectRescueStore.hide()
        activeRouteProbeId?.let {
            routeProbeJob?.cancel()
        }
        ConnectionStateStore.tryBeginDisconnect()
        generation.incrementAndGet()
        val pendingConnect = connectJob
        val pendingHealth = healthJob
        val pendingStats = statsJob
        val pendingLatency = latencyJob
        val pendingLearning = adaptiveLearningJob
        val pendingNetworkWatch = networkWatchJob
        val pendingRouteProbe = routeProbeJob
        serviceScope.launch {
            pendingRouteProbe?.cancelAndJoin()
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
            if (closeAppTasks) finishAppTasks()
            stopSelf()
        }
    }

    private fun finishAppTasks() {
        getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
            runCatching { task.finishAndRemoveTask() }
        }
    }

    private fun requestSwitchProfile() {
        if (engineModeStore.snapshot().isTor) {
            AppLogRepository.info(LogSource.TOR, "Profile switch ignored while Tor / WebTunnel engine is selected")
            return
        }
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

    private fun requestApplyTorExit() {
        if (!engineModeStore.snapshot().isTor) return
        val state = ConnectionStateStore.state.value
        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING) {
            AppLogRepository.info(LogSource.TOR, "Exit country saved for the next Tor connection")
            return
        }
        val raw = torEngineStore.snapshot()
        val torSettings = if (raw.validated().exitCountryCode.isNotEmpty() && !raw.exitStrict) {
            raw.copy(exitStrict = true).validated().also { torEngineStore.save(it) }
        } else {
            raw
        }
        val country = torSettings.validated().exitCountryCode.ifBlank { "auto" }
        AppLogRepository.info(LogSource.TOR, "Reconnecting Tor engine for ExitNodes={$country}")
        ConnectRescueStore.hide()
        activeEngine = EngineMode.TOR_WEBTUNNEL
        val token = generation.incrementAndGet()
        ConnectionStateStore.markConnecting()
        TorStatusStore.update(TorPhase.STARTING, 0, "Reconnecting for ExitNodes={$country}")
        runCatching { updateNotification(connected = false) }

        val pendingConnect = connectJob
        val pendingHealth = healthJob
        val pendingStats = statsJob
        val pendingLatency = latencyJob
        val pendingLearning = adaptiveLearningJob
        val pendingNetworkWatch = networkWatchJob
        val pendingRouteProbe = routeProbeJob
        val job = serviceScope.launch {
            try {
                pendingRouteProbe?.cancelAndJoin()
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
                routeProbeJob = null
                if (token != generation.get()) return@launch
                lifecycleMutex.withLock {
                    if (token != generation.get()) return@withLock
                    cleanupRoute()
                    resourcesActive = false
                    TorStatusStore.update(
                        TorPhase.STARTING,
                        0,
                        "Reconnecting for ExitNodes={$country}",
                    )
                    AppLogRepository.info(LogSource.TOR, "Starting Tor connect cycle for ExitNodes={$country}")
                    connectTorEngine(token, advancedSettingsStore.snapshot())
                }
            } catch (_: CancellationException) {
                ConnectRescueStore.hide()
            } catch (error: Throwable) {
                ConnectRescueStore.hide()
                TorStatusStore.update(
                    TorPhase.FAILED,
                    0,
                    error.message.orEmpty().ifBlank { "Tor reconnect failed" },
                )
                Log.e(TAG, "Tor exit country reconnect failed", error)
                AppLogRepository.error(LogSource.TOR, "Tor exit country reconnect failed", error)
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

    private fun requestLatencyRefresh() {
        if (
            ConnectionStateStore.state.value != ConnectionState.CONNECTED ||
            !resourcesActive
        ) {
            return
        }

        startLatencySampler(generation.get(), settleFirst = false)
    }
    private suspend fun connectRoutes(
        token: Long,
        settings: AdvancedSettingsData,
        profile: ProxyProfile,
    ) {
        activeConnectionMode = settings.connectionMode
        var lastFailure: Throwable? = null
        var bestReport: AdaptiveProbeReport? = null
        val networkContext = fingerprintResolver.captureAdaptiveContext()
        val fingerprint = networkContext.fingerprint
        val signature = adaptivePlanner.signature(settings, profile)
        val savedChampionId = adaptiveProfileStore.savedRoute(fingerprint, profile, signature)?.id
        val savedBackupId = adaptiveProfileStore.savedBackupRoute(fingerprint, profile, signature)?.id
        val learnedWinnerId = adaptiveProfileStore.winner(fingerprint, profile, signature)
        fun routeSource(candidate: AdaptiveCandidate): String = when (candidate.id) {
            savedChampionId -> "SAVED_CHAMPION"
            savedBackupId -> "SAVED_BACKUP"
            AdaptiveCandidatePlanner.CONNECT_LAST_GOOD_ID -> "CONNECT_LAST_GOOD"
            learnedWinnerId -> "LEARNED_WINNER"
            else -> "ADAPTIVE_FALLBACK"
        }
        fun persistWorkingPool(pool: ConnectPoolSelection, candidate: AdaptiveCandidate) {
            val workingEdge = candidate.edge.takeIf(::persistableConnectEdge)
            if (workingEdge != null) {
                connectEdgePoolStore.saveChampion(fingerprint, profile.id, workingEdge)
            }
            val usedPoolEdge = pool.edges.any { edge ->
                canonicalEndpointKey(edge.address, edge.port) ==
                    canonicalEndpointKey(candidate.edge.address, candidate.edge.port)
            }
            val poolToSave = poolWithChampionFirst(
                pool = if (usedPoolEdge) pool.edges else emptyList(),
                champion = workingEdge,
            )
            if (poolToSave.isEmpty()) return
            connectEdgePoolStore.save(fingerprint, profile.id, poolToSave)
            AppLogRepository.info(
                LogSource.ADAPTIVE,
                "Saved connect edge pool profile=${profile.id} operator=${fingerprint.learningKey()} " +
                    "source=${pool.source} champion=${workingEdge?.let { "${it.address}:${it.port}" } ?: "none"} " +
                    "edges=${poolToSave.joinToString { "${it.address}:${it.port}" }}",
            )
        }
        suspend fun tryCandidateBatch(
            candidates: List<AdaptiveCandidate>,
            pool: ConnectPoolSelection,
            rescueGeneration: Long = 0L,
            progressOffset: Int = 0,
        ): Boolean {
            val progressTotal = progressOffset + candidates.size
            for ((index, candidate) in candidates.withIndex()) {
                coroutineContext.ensureActive()
                if (token != generation.get()) throw CancellationException("stale connect generation")
                ConnectionStateStore.updateConnectRouteProgress(progressOffset + index + 1, progressTotal)
                try {
                    cleanupRoute()
                    val edge = candidate.edge
                    if (rescueGeneration != 0L) {
                        ConnectRescueStore.update(rescueGeneration) { current ->
                            current.copy(
                                phase = ConnectRescuePhase.RETRYING,
                                retryIndex = index + 1,
                                retryTotal = candidates.size,
                                currentTarget = "${edge.address}:${edge.port}",
                                foundCount = pool.edges.size,
                            )
                        }
                    }
                    val candidateSettings = candidate.settings
                    val source = routeSource(candidate)
                    val routeAttempt =
                        "ROUTE TRY source=$source position=${index + 1}/${candidates.size} " +
                            "id=${candidate.id} label=${candidate.label} edge=${edge.address}:${edge.port} " +
                            "resolver=${AdaptiveDnsResolvers.idFor(candidate.settings.dnsResolverUrl)}"
                    when (source) {
                        "SAVED_CHAMPION", "LEARNED_WINNER", "CONNECT_LAST_GOOD" ->
                            AppLogRepository.success(LogSource.ADAPTIVE, routeAttempt)
                        "SAVED_BACKUP" ->
                            AppLogRepository.warning(LogSource.ADAPTIVE, routeAttempt)
                        else -> AppLogRepository.info(LogSource.ADAPTIVE, routeAttempt)
                    }
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
                        return true
                    }
                    persistWorkingPool(pool, candidate)
                    if (rescueGeneration != 0L) {
                        ConnectRescueStore.update(rescueGeneration) { current ->
                            current.copy(
                                phase = ConnectRescuePhase.SUCCEEDED,
                                retryIndex = index + 1,
                                retryTotal = candidates.size,
                                currentTarget = "${edge.address}:${edge.port}",
                                foundCount = pool.edges.size.coerceAtLeast(1),
                            )
                        }
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
                    AppLogRepository.success(
                        LogSource.ADAPTIVE,
                        "ROUTE ACTIVE source=${routeSource(candidate)} id=${candidate.id} " +
                            "label=${candidate.label} score=${report.score} edge=${edge.address}:${edge.port}",
                    )
                    runCatching { updateNotification(connected = true) }
                        .onFailure { Log.w(TAG, "connected notification update failed", it) }
                    startHealthMonitor(token)
                    startStatsMonitor(token)
                    startLatencySampler(token)
                    startAdaptiveLearningMonitor(token, candidate, fingerprint, profile, signature, report.score)
                    startNetworkWatch(token, fingerprint)
                    return true
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
                        val next = candidates[index + 1]
                        val reason = error.message
                            ?.substringBefore('\n')
                            ?.take(220)
                            ?.ifBlank { error.javaClass.simpleName }
                            ?: error.javaClass.simpleName
                        AppLogRepository.warning(
                            LogSource.ADAPTIVE,
                            "ROUTE SWITCH from=${routeSource(candidate)}:${candidate.id} " +
                                "to=${routeSource(next)}:${next.id} reason=[$reason]",
                        )
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
            return false
        }

        AppLogRepository.info(LogSource.SERVICE, "Selected ${profile.protocol.name} profile: ${profile.name}")
        AppLogRepository.info(LogSource.ADAPTIVE, "Session=$token network fingerprint ${fingerprint.summary()}")
        AppLogRepository.debug(
            LogSource.ADAPTIVE,
            "ROUTE COLOR LEGEND green=SAVED/ACTIVE amber=BACKUP/SWITCH blue=ADAPTIVE red=FAILED",
        )
        if (savedChampionId != null) {
            AppLogRepository.success(
                LogSource.ADAPTIVE,
                "ROUTE SAVED_CHAMPION ready id=$savedChampionId fingerprint=${fingerprint.learningKey()}",
            )
        }
        if (savedBackupId != null) {
            AppLogRepository.warning(
                LogSource.ADAPTIVE,
                "ROUTE SAVED_BACKUP ready id=$savedBackupId fingerprint=${fingerprint.learningKey()}",
            )
        }
        val plan = adaptivePlanner.connectPlan(settings, fingerprint, profile)
        AppLogRepository.info(
            LogSource.ADAPTIVE,
            "Connect edge pool source=${plan.pool.source} profile=${profile.id} " +
                "operator=${fingerprint.learningKey()} " +
                "edges=${plan.pool.edges.joinToString { "${it.address}:${it.port}" }.ifEmpty { "hardcoded-default" }}",
        )
        AppLogRepository.info(
            LogSource.ADAPTIVE,
            "Planner signature=$signature candidates=${plan.candidates.joinToString(",") { it.id }}",
        )
        if (tryCandidateBatch(plan.candidates, plan.pool)) return

        AppLogRepository.info(
            LogSource.ADAPTIVE,
            "Connect edge pool exhausted; searching for clean Cloudflare IPs",
        )
        val rescueGeneration = ConnectRescueStore.begin()
        val rescuedEdges = latencyTester.discoverConnectEdgePool(
            settings = settings,
            profile = profile,
            networkContext = networkContext,
            profileSignature = signature,
            rescueGeneration = rescueGeneration,
        )
        val triedEndpoints = plan.candidates
            .filter { it.id != AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID }
            .map { canonicalEndpointKey(it.edge.address, it.edge.port) }
            .toSet()
        val freshEdges = rescuedEdges.filter { edge ->
            canonicalEndpointKey(edge.address, edge.port) !in triedEndpoints
        }
        if (freshEdges.isNotEmpty()) {
            val rescuePlan = adaptivePlanner.connectPlan(
                base = settings,
                network = fingerprint,
                profile = profile,
                poolOverride = freshEdges,
                includeSavedRoutes = false,
            )
            AppLogRepository.info(
                LogSource.ADAPTIVE,
                "Connect rescue retry edges=${freshEdges.joinToString { "${it.address}:${it.port}" }} " +
                    "candidates=${rescuePlan.candidates.joinToString(",") { it.id }}",
            )
            if (tryCandidateBatch(
                    rescuePlan.candidates,
                    rescuePlan.pool,
                    rescueGeneration,
                    progressOffset = plan.candidates.size,
                )
            ) return
        } else {
            AppLogRepository.warning(
                LogSource.ADAPTIVE,
                "Connect rescue found no new Cloudflare edges after the exhausted pool",
            )
        }
        ConnectRescueStore.update(rescueGeneration) { current ->
            current.copy(phase = ConnectRescuePhase.FAILED, foundCount = freshEdges.size)
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

    private suspend fun connectTorEngine(token: Long, settings: AdvancedSettingsData) {
        coroutineContext.ensureActive()
        if (token != generation.get()) throw CancellationException("stale connect generation")
        torCoordinator.connect(settings) { candidateSettings ->
            establishTun(candidateSettings)
        }
        coroutineContext.ensureActive()
        if (token != generation.get()) throw CancellationException("stale connect generation")
        resourcesActive = true
        if (!ConnectionStateStore.markConnected()) {
            cleanupRoute()
            resourcesActive = false
            return
        }
        runCatching { updateNotification(connected = true) }
            .onFailure { Log.w(TAG, "connected notification update failed", it) }
        if (!isProxyMode()) startStatsMonitor(token)
        startHealthMonitor(token)
        startLatencySampler(token)
        AppLogRepository.success(LogSource.TOR, "Tor / WebTunnel engine is active")
    }

    private fun establishTun(
        settings: AdvancedSettingsData,
    ): android.os.ParcelFileDescriptor? {
        val route = TunRouteParser.parse(settings.tunRoute)
        val routing = AppRoutingPreferences.snapshot(this)
        val torRelay = activeEngine.isTor
        val dns = if (torRelay) TorTunRelayConfig.MAP_DNS else settings.nativeDns
        AppLogRepository.debug(
            LogSource.TUN,
            "Establish request mtu=${settings.tunMtu} address=${settings.tunAddress} route=${settings.tunRoute} " +
                "dns=$dns ipv4Only=${settings.ipv4Only || torRelay} " +
                "routing=${routing.mode.name.lowercase()} selected=${routing.selectedPackages.size}",
        )
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setBlocking(false)
            .setMtu(settings.tunMtu)
            .addAddress(settings.tunAddress, 32)
            .addRoute(route.first, route.second)
            .addDnsServer(dns)
            .apply { if (settings.ipv4Only || torRelay) allowFamily(OsConstants.AF_INET) }
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
        runCatching { torCoordinator.stop() }
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

    private suspend fun measureRuntimeLatency(): ProbeResult {
        if (!activeEngine.isTor) return connectivityProbe.verifyRuntime()
        val tor = torEngineStore.snapshot()
        return connectivityProbe.verifyRuntime(
            socksAddress = MciConfig.LOCAL_SOCKS_ADDRESS,
            socksPort = tor.socksPort,
            totalTimeoutMs = TOR_LATENCY_TIMEOUT_MS,
            socketTimeoutMs = TOR_LATENCY_SOCKET_TIMEOUT_MS,
        )
    }

    private fun startLatencySampler(token: Long, settleFirst: Boolean = true) {
        latencyJob?.cancel()
        ConnectionMetricsStore.beginLatencyMeasurement()
        val job = serviceScope.launch {
            try {
                val tor = activeEngine.isTor
                if (tor && settleFirst) {
                    delay(TOR_LATENCY_SETTLE_MS)
                    if (token != generation.get() || !resourcesActive) return@launch
                    measureRuntimeLatency()
                    if (token != generation.get() || !resourcesActive) return@launch
                    delay(TOR_LATENCY_SAMPLE_DELAY_MS)
                }
                val samples = if (tor) TOR_LATENCY_SAMPLE_COUNT else LATENCY_SAMPLE_COUNT
                val gapMs = if (tor) TOR_LATENCY_SAMPLE_DELAY_MS else LATENCY_SAMPLE_DELAY_MS
                repeat(samples) { index ->
                    if (token != generation.get() || !resourcesActive) return@launch
                    val probe = measureRuntimeLatency()
                    if (probe.success) ConnectionMetricsStore.addLatencySample(probe.latencyMs)
                    if (index + 1 < samples) delay(gapMs)
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

    private fun activeStats(): TunStats = when {
        isProxyMode() -> TunStats.ZERO
        activeEngine.isTor -> torCoordinator.tunStats()
        else -> nativeTunEngine.stats()
    }

    private fun activeProbeStats(): TunStats = when {
        isProxyMode() -> TunStats.ZERO
        activeEngine.isTor -> TunStats.ZERO
        else -> nativeTunEngine.probeStats()
    }

    private fun activeCoreRunning(): Boolean = when {
        activeEngine.isTor && isProxyMode() -> torCoordinator.isDaemonRunning()
        activeEngine.isTor -> torCoordinator.isDaemonRunning() && torCoordinator.isRelayRunning()
        isProxyMode() -> proxyCore.isRunning()
        else -> nativeTunEngine.isRunning()
    }

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

                if (activeEngine.isTor) {
                    if (!activeCoreRunning()) {
                        AppLogRepository.warning(LogSource.TOR, "Tor engine process exited; recovering")
                        scheduleRuntimeRecovery(token, "Tor engine process exited", penalizeCandidate = false)
                        return@launch
                    }
                    nextDelayMs = MciConfig.HEALTH_CHECK_INTERVAL_MS
                    continue
                }

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
        if (activeEngine.isXray && penalizeCandidate && failedCandidate != null && failedFingerprint != null && failedSignature != null) {
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
                    val settings = advancedSettingsStore.snapshot()
                    if (activeEngine.isTor) {
                        connectTorEngine(recoveryToken, settings)
                    } else {
                        connectRoutes(recoveryToken, settings, profile)
                    }
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
        val disconnect = PendingIntent.getService(
            this,
            NOTIFICATION_DISCONNECT_REQUEST,
            Intent(this, UacVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this,
            NOTIFICATION_CLOSE_REQUEST,
            Intent(this, UacVpnService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = getString(textRes)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(
                R.drawable.ic_notification_disconnect,
                getString(R.string.notification_disconnect),
                disconnect,
            )
            .addAction(
                R.drawable.ic_notification_close,
                getString(R.string.notification_close),
                close,
            )
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.uacspoofer.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.uacspoofer.mobile.DISCONNECT"
        const val ACTION_CLOSE = "com.uacspoofer.mobile.CLOSE"
        const val ACTION_SWITCH_PROFILE = "com.uacspoofer.mobile.SWITCH_PROFILE"
        const val ACTION_APPLY_TOR_EXIT = "com.uacspoofer.mobile.APPLY_TOR_EXIT"
        const val ACTION_ROUTE_MTU_PROBE = "com.uacspoofer.mobile.ROUTE_MTU_PROBE"
        const val ACTION_CANCEL_ROUTE_MTU_PROBE = "com.uacspoofer.mobile.CANCEL_ROUTE_MTU_PROBE"
        const val ACTION_REFRESH_LATENCY = "com.uacspoofer.mobile.REFRESH_LATENCY"
        const val EXTRA_ROUTE_PROBE_ID = "route_probe_id"

        private const val TAG = "UAC-SNI"
        private const val NOTIFICATION_CHANNEL = "uac_mci_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_DISCONNECT_REQUEST = 1002
        private const val NOTIFICATION_CLOSE_REQUEST = 1003
        private const val STATS_INTERVAL_MS = 1_000L
        private const val LATENCY_SAMPLE_COUNT = 3
        private const val TOR_LATENCY_SAMPLE_COUNT = 3
        private const val LATENCY_SAMPLE_DELAY_MS = 350L
        private const val TOR_LATENCY_SAMPLE_DELAY_MS = 700L
        private const val TOR_LATENCY_SETTLE_MS = 5_000L
        private const val TOR_LATENCY_TIMEOUT_MS = 22_000L
        private const val TOR_LATENCY_SOCKET_TIMEOUT_MS = 12_000
        private const val ADAPTIVE_STABILITY_WINDOW_MS = 60_000L
        private const val ADAPTIVE_PROBE_WARMUP_MS = 800L
        private const val POST_CONNECT_HEALTH_DELAY_MS = 8_000L
        private const val NETWORK_WATCH_INTERVAL_MS = 3_000L
        private const val NETWORK_CHANGE_CONFIRMATIONS = 2
        private const val ROUTE_MTU_PROBE_WARMUP_MS = 300L
        private const val ROUTE_STATS_SETTLE_MS = 50L
        private const val ROUTE_VPN_NETWORK_TIMEOUT_MS = 5_000L
        private const val ROUTE_VPN_NETWORK_POLL_MS = 50L

    }
}

private data class RuntimeControlGate(
    val healthy: Boolean,
    val latencyMs: Long?,
    val detail: String,
)
