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

    private lateinit var nativeTunEngine: XrayNativeTunEngine
    private lateinit var connectivityProbe: VpnConnectivityProbe
    private lateinit var dnsProbe: SocksDnsProbe
    private lateinit var adaptiveProbe: AdaptiveConnectionProbe
    private lateinit var adaptiveProfileStore: AdaptiveProfileStore
    private lateinit var adaptivePlanner: AdaptiveCandidatePlanner
    private lateinit var fingerprintResolver: NetworkFingerprintResolver
    private lateinit var advancedSettingsStore: AdvancedSettingsStore
    private lateinit var profileStore: ProfileStore

    override fun onCreate() {
        super.onCreate()
        AppLogRepository.info(LogSource.SERVICE, "VPN service created")
        createNotificationChannel()
        nativeTunEngine = XrayNativeTunEngine(this)
        connectivityProbe = VpnConnectivityProbe(::activeProbeStats)
        dnsProbe = SocksDnsProbe()
        adaptiveProbe = AdaptiveConnectionProbe(connectivityProbe, dnsProbe)
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
        AppLogRepository.info(LogSource.SERVICE, "VPN service stopping")
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
        var lastFailure: Throwable? = null
        var bestReport: AdaptiveProbeReport? = null
        val fingerprint = fingerprintResolver.capture()
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
                nativeTunEngine.start(edge, candidateSettings, profile) { establishTun(candidateSettings) }
                delay(ADAPTIVE_PROBE_WARMUP_MS)
                val report = adaptiveProbe.verify(candidate)
                if (bestReport == null || report.score > bestReport!!.score) bestReport = report
                AppLogRepository.info(LogSource.ADAPTIVE, "Candidate ${candidate.id} result ${report.detail()}")
                check(report.accepted) { report.detail() }
                coroutineContext.ensureActive()
                if (token != generation.get()) throw CancellationException("stale connect generation")

                resourcesActive = true
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
            }
        }

        Log.e(TAG, "all adaptive candidates failed", lastFailure)
        AppLogRepository.error(
            LogSource.SERVICE,
            "All adaptive candidates failed; best=${bestRep÷Om¢G§²ÚîÆ­yÐ             if (token == generation.get() && resourcesActive) {
                    ConnectionMetricsStore.finishLatencyMeasurement()
                }
            }
        }
        latencyJob = job
        job.invokeOnCompletion { if (latencyJob === job) latencyJob = null }
    }

    private fun activeStats(): TunStats = nativeTunEngine.stats()

    private fun activeProbeStats(): TunStats = nativeTunEngine.probeStats()

    private fun activeCoreRunning(): Boolean = nativeTunEngine.isRunning()

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
            val currentNetwork = fingerprintResolver.capture()
            if (currentNetwork.key != fingerprint.key) {
                AppLogRepository.warning(
                    LogSource.ADAPTIVE,
                    "Learning skipped for ${candidate.id}; network changed ${fingerprint.key}->${currentNetwork.key}",
                )
                return@launch
            }
            val currentStats = activeStats()
            val trafficHealthy = currentStats.hasBidirectionalGrowthSince(initialStats)
            if (!activeCoreRunning()) {
                adaptiveProfileStore.recordFailure(fingerprint, profile, signature, candidate.id)
                AppLogRepository.warning(
                    LogSource.ADAPTIVE,
                    "Candidate ${candidate.id} was not learned because the native core stopped",
                )
                return@launch
            }
            if (!trafficHealthy) {
                AppLogRepository.info(
                    LogSource.ADAPTIVE,
                    "Learning deferred for ${candidate.id}; no bidirectional user TUN traffic was observed",
                )
                return@launch
            }
            adaptiveProfileStore.recordStable(fingerprint, profile, signature, candidate, initialScore)
            AppLogRepository.info(
                LogSource.ADAPTIVE,
                "Learned stable candidate ${candidate.id} network=${fingerprint.key} score=$initialScore " +
                    "tunTx=${currentStats.txBytes - initialStats.txBytes} tunRx=${currentStats.rxBytes - initialStats.rxBytes}",
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
            while (true) {
                delay(NETWORK_WATCH_INTERVAL_MS)
                if (token != generation.get() || !resourcesActive) return@launch
                val current = fingerprintResolver.capture()
                if (current.networkHandle < 0L || current.key == initial.key) {
                    mismatchKey = null
                    mismatchCount = 0
                    continue
                }
                if (mismatchKey == current.key) {
                    mismatchCount += 1
                } else {
                    mismatchKey = current.key
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
                    AppLogRepository.warning(LogSource.SERVICE, "Active tunnel core exited; recovering")
                    scheduleRuntimeRecovery(token, "active tunnel core exited")
                    return@launch
                }

                val currentStats = activeStats()
                val hasUplink = currentStats.hasUplinkGrowthSince(previousStats)
                val hasDownlink = currentStats.hasDownlinkGrowthSince(previousStats)
                if (hasDownlink) {
                    previousStats = currentStats
                    guard.recordHealthy()
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
                    RuntimeControlGate(
                        healthy = http.success && dns.success,
                        latencyMs = http.latencyMs,
                        detail = "http=[${http.detail}] dns=[${dns.detail}]",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    RuntimeControlGate(false, null, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                }

                if (control.healthy && !hasUplink) {
                    guard.recordHealthy()
                    ConnectionMetricsStore.updateLatency(control.latencyMs)
                    nextDelayMs = MciConfig.HEALTH_CHECK_INTERVAL_MS
                    AppLogRepository.debug(LogSource.SERVICE, "Idle runtime control gate passed ${control.detail}")
                    continue
                }

                val failureDetail = if (hasUplink && control.healthy) {
                    "user TUN uplink advanced without downlink; ${control.detail}"
                } else {
                    control.detail
                }

                when (guard.recordFailure(coreRunning = true)) {
                    RuntimeHealthAction.KEEP_CONNECTED -> {
                        nextDelayMs = MciConfig.RUNTIME_HEALTH_RETRY_DELAY_MS
                        AppLogRepository.warning(
                            LogSource.SERVICE,
                            "Transient health failure ${guard.consecutiveFailures}/${MciConfig.RUNTIME_HEALTH_MAX_FAILURES}; tunnel kept active: $failureDetail",
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
