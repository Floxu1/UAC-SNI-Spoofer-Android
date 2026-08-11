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
import com.uacspoofer.mobile.mci.MciRouteSelector
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
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
    private val routeSelector = MciRouteSelector()
    @Volatile private var connectJob: Job? = null
    @Volatile private var healthJob: Job? = null
    @Volatile private var statsJob: Job? = null
    @Volatile private var latencyJob: Job? = null
    @Volatile private var resourcesActive = false
    @Volatile private var activeEdge: MciEdge? = null

    private lateinit var nativeTunEngine: XrayNativeTunEngine
    private lateinit var connectivityProbe: VpnConnectivityProbe
    private lateinit var advancedSettingsStore: AdvancedSettingsStore
    private lateinit var profileStore: ProfileStore

    override fun onCreate() {
        super.onCreate()
        AppLogRepository.info(LogSource.SERVICE, "VPN service created")
        createNotificationChannel()
        nativeTunEngine = XrayNativeTunEngine(this)
        connectivityProbe = VpnConnectivityProbe(::activeStats)
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
        AppLogRepository.info(LogSource.SERVICE, "VPN service stopping")
        generation.incrementAndGet()
        connectJob?.cancel()
        healthJob?.cancel()
        statsJob?.cancel()
        latencyJob?.cancel()
        serviceScope.cancel()
        runBlocking(Dispatchers.IO) { runCatching { cleanupRoute() } }
        if (ConnectionStateStore.state.value != ConnectionState.ERROR) {
            ConnectionStateStore.markDisconnected()
        }
        super.onDestroy()
    }

    private fun requestConnect() {
        if (resourcesActive || connectJob?.isActive == true) return
        AppLogRepository.info(LogSource.SERVICE, "Connection requested")
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
                val settings = advancedSettingsStore.snapshot()
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
        serviceScope.launch {
            pendingConnect?.cancelAndJoin()
            pendingHealth?.cancelAndJoin()
            pendingStats?.cancelAndJoin()
            pendingLatency?.cancelAndJoin()
            healthJob = null
            statsJob = null
            latencyJob = null
            lifecycleMutex.withLock {
                cleanupRoute()
                resourcesActive = false
            }
            ConnectionStateStore.markDisconnected()
            AppLogRepository.info(LogSource.SERVICE, "Disconnected; route resources released")
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
        val job = serviceScope.launch {
            try {
                pendingHealth?.cancelAndJoin()
                pendingStats?.cancelAndJoin()
                pendingLatency?.cancelAndJoin()
                healthJob = null
                statsJob = null
                latencyJob = null
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
        var lastFailure: Throwable? = null
        AppLogRepository.info(LogSource.SERVICE, "Selected ${profile.protocol.name} profile: ${profile.name}")
        for (edge in routeSelector.orderedEdges(settings.edges())) {
            coroutineContext.ensureActive()
            if (token != generation.get()) throw CancellationException("stale connect generation")
            try {
                cleanupRoute()
                Log.i(TAG, "starting XRAY_NATIVE route ${edge.role}=${edge.address}:${edge.port}")
                AppLogRepository.info(LogSource.SERVICE, "Trying XRAY_NATIVE / ${edge.role} ${edge.address}:${edge.port}")
                nativeTunEngine.start(edge, settings, profile) { establishTun(settings) }
                delay(300L)
                val probe = verifyInitialConnectivity()
                check(probe.success) { probe.detail }
                coroutineContext.ensureActive()
                if (token != generation.get()) throw CancellationException("stale connect generation")

                routeSelector.recordSuccess(edge)
                resourcesActive = true
                activeEdge = edge
                profileStore.markActive(
                    profile.id,
                    com.uacspoofer.mobile.profiles.ProfileEndpoint(edge.address, edge.port),
                )
                if (!ConnectionStateStore.markConnected()) {
                    cleanupRoute()
                    resourcesActive = false
                    return
                }
                Log.i(TAG, "real connectivity gate passed on XRAY_NATIVE/${edge.role}: ${probe.detail}")
                AppLogRepository.info(LogSource.SERVICE, "Connected with XRAY_NATIVE on ${edge.role}: ${probe.detail}")
                runCatching { updateNotification(connected = true) }
                    .onFailure { Log.w(TAG, "connected notification update failed", it) }
                startHealthMonitor(token)
                startStatsMonitor(token)
                startLatencySampler(token)
                return
            } catch (cancelled: CancellationException) {
                cleanupRoute()
                resourcesActive = false
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = error
                routeSelector.recordFailure(edge)
                Log.w(TAG, "XRAY_NATIVE route ${edge.role} failed", error)
                AppLogRepository.warning(LogSource.SERVICE, "XRAY_NATIVE / ${edge.role} failed", error)
                cleanupRoute()
                resourcesActive = false
            }
        }

        Log.e(TAG, "all MCI routes failed", lastFailure)
        AppLogRepository.error(LogSource.SERVICE, "All routes failed", lastFailure)
        if (token == generation.get()) {
            ConnectionStateStore.markError()
            runCatching { updateFailureNotification() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun establishTun(settings: AdvancedSettingsData): android.os.ParcelFileDescriptor? {
        val route = TunRouteParser.parse(settings.tunRoute)
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
        statsJob?.cancel()
        statsJob = null
        latencyJob?.cancel()
        latencyJob = null
        nativeTunEngine.stop()
        activeEdge = null
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

    private suspend fun verifyInitialConnectivity(): ProbeResult {
        var last = ProbeResult(false, 0, "probe not started")
        repeat(MciConfig.CONNECT_PROBE_ATTEMPTS) { index ->
            last = connectivityProbe.verify()
            if (last.success) return last
            if (index + 1 < MciConfig.CONNECT_PROBE_ATTEMPTS) {
                AppLogRepository.warning(
                    LogSource.SERVICE,
                    "Connect probe ${index + 1}/${MciConfig.CONNECT_PROBE_ATTEMPTS} failed; retrying: ${last.detail}",
                )
                delay(MciConfig.CONNECT_PROBE_RETRY_DELAY_MS)
            }
        }
        return last
    }

    private fun activeStats(): TunStats = nativeTunEngine.stats()

    private fun activeCoreRunning(): Boolean = nativeTunEngine.isRunning()

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
            var nextDelayMs = MciConfig.HEALTH_CHECK_INTERVAL_MS
            var previousStats = activeStats()
            while (true) {
                delay(nextDelayMs)
                if (token != generation.get() || !resourcesActive) return@launch

                if (!activeCoreRunning()) {
                    AppLogRepository.warning(LogSource.SERVICE, "Active tunnel core exited; recovering")
                    scheduleRuntimeRecovery(token, "active tunnel core exited")
                    return@launch
                }

                val currentStats = activeStats()
                if (currentStats.hasBidirectionalGrowthSince(previousStats)) {
                    previousStats = currentStats
                    guard.recordHealthy()
                    nextDelayMs = MciConfig.HEALTH_CHECK_INTERVAL_MS
                    Log.d(TAG, "runtime health confirmed by active bidirectional traffic")
                    continue
                }
                previousStats = currentStats

                val probe = try {
                    connectivityProbe.verifyRuntime()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ProbeResult(false, 0, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                }

                if (probe.success) {
                    guard.recordHealthy()
                    ConnectionMetricsStore.updateLatency(probe.latencyMs)
                    nextDelayMs = MciConfig.HEALTH_CHECK_INTERVAL_MS
                    Log.d(TAG, "periodic connectivity gate passed: ${probe.detail}")
                    continue
                }

                when (guard.recordFailure(coreRunning = true)) {
                    RuntimeHealthAction.KEEP_CONNECTED -> {
                        nextDelayMs = MciConfig.RUNTIME_HEALTH_RETRY_DELAY_MS
                        AppLogRepository.warning(
                            LogSource.SERVICE,
                            "Transient health probe failure ${guard.consecutiveFailures}/${MciConfig.RUNTIME_HEALTH_MAX_FAILURES}; tunnel kept active: ${probe.detail}",
                        )
                    }
                    RuntimeHealthAction.RECOVER -> {
                        AppLogRepository.warning(
                            LogSource.SERVICE,
                            "Runtime health failed ${guard.consecutiveFailures} times; reconnecting: ${probe.detail}",
                        )
                        scheduleRuntimeRecovery(token, probe.detail)
                        return@launch
                    }
                }
            }
        }
        healthJob = job
        job.invokeOnCompletion { if (healthJob === job) healthJob = null }
    }

    
    private fun scheduleRuntimeRecovery(failedToken: Long, reason: String) {
        val recoveryToken = failedToken + 1L
        if (!generation.compareAndSet(failedToken, recoveryToken)) return

        val failedEdge = activeEdge
        val profile = profileStore.activeProfile() ?: profileStore.selectedProfile()
        failedEdge?.let(routeSelector::recordFailure)
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
            if (connected) R.string.vpn_connected_notification else R.string.vpn_connecting_notification,
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
                if (connected) R.string.vpn_connected_notification else R.string.vpn_connecting_notification,
            ),
        )
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

    }
}
