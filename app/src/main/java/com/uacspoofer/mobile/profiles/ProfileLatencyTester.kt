package com.uacspoofer.mobile.profiles

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.LocalForwardProfile
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayBatchRoute
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.vpn.AdaptiveCandidate
import com.uacspoofer.mobile.vpn.AdaptiveCandidatePlanner
import com.uacspoofer.mobile.vpn.AdaptiveConnectionProbe
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import com.uacspoofer.mobile.vpn.AdaptiveProfileStore
import com.uacspoofer.mobile.vpn.AdaptiveRouteMetrics
import com.uacspoofer.mobile.vpn.AdaptiveSavedRoute
import com.uacspoofer.mobile.vpn.CloudflareEdgeCandidate
import com.uacspoofer.mobile.vpn.CloudflareEdgeDiscovery
import com.uacspoofer.mobile.vpn.CloudflareEdgeDiscoveryResult
import com.uacspoofer.mobile.vpn.CloudflareDiscoveryPhase
import com.uacspoofer.mobile.vpn.CloudflareDiscoveryProgress
import com.uacspoofer.mobile.vpn.CloudflareSuitability
import com.uacspoofer.mobile.vpn.NetworkFingerprint
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver
import com.uacspoofer.mobile.vpn.RouteMtuProbeCoordinator
import com.uacspoofer.mobile.vpn.RouteNativeMtuProbeRequest
import com.uacspoofer.mobile.vpn.RouteProbeBusyException
import com.uacspoofer.mobile.vpn.RouteProbePermissionRequiredException
import com.uacspoofer.mobile.vpn.SocksDnsProbe
import com.uacspoofer.mobile.vpn.TunStats
import com.uacspoofer.mobile.vpn.VpnConnectivityProbe
import com.uacspoofer.mobile.vpn.selectSubnetDiverseEdges
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class SniProfileProbeResult(
    val latencyMs: Long,
    val country: CountryMetadata,
    val exitIp: String,
    val countrySource: String,
    val candidateId: String = "",
    val candidateLabel: String = "",
    val probeDetail: String = "",
)

data class SniMakerTestSession(
    val settings: com.uacspoofer.mobile.settings.AdvancedSettingsData,
    val network: NetworkFingerprint,
    val initialPreferredCandidateId: String?,
)

data class RouteSpeedTestPlan(
    val profile: ProxyProfile,
    val session: SniMakerTestSession,
    val signature: String,
    val candidates: List<AdaptiveCandidate>,
    val savedChampionId: String?,
    val savedChampionLabel: String?,
    val savedChampion: AdaptiveSavedRoute?,
    val savedBackupId: String?,
    val savedBackupLabel: String?,
    val savedBackup: AdaptiveSavedRoute?,
    val discoveryId: String,
    val discoverySummary: String,
    val discoveredEdgeCount: Int,
    val underlyingNetwork: Network? = null,
)

enum class RoutePreparationStep(val number: Int) {
    PROFILE_SNAPSHOT(1),
    NETWORK_DETECTION(2),
    EDGE_POOL(3),
    TCP_TLS_PREFLIGHT(4),
    XRAY_SCREENING(5),
    CONNECTIVITY_VALIDATION(6),
    ROUTE_MATRIX(7),
    ;

    companion object {
        const val TOTAL = 7
    }
}

data class RoutePreparationProgress(
    val step: RoutePreparationStep,
    val completed: Int = 0,
    val total: Int = 0,
    val healthy: Int = 0,
    val currentTarget: String = "",
    val detail: String = "",
)

enum class RouteSpeedProbeStage { STARTING, PROBING }

data class RouteSpeedProbeResult(
    val candidate: AdaptiveCandidate,
    val accepted: Boolean,
    val score: Int,
    val latencyMs: Long?,
    val dnsLatencyMs: Long?,
    val payloadBytes: Int,
    val durationMs: Long,
    val throughputKbps: Long,
    val httpSucceeded: Int,
    val httpAttempted: Int,
    val dnsSucceeded: Boolean,
    val detail: String,
    val error: String? = null,
    val uploadBytes: Int = 0,
    val downloadBytes: Int = payloadBytes,
    val uploadKbps: Long = 0L,
    val downloadKbps: Long = throughputKbps,
    val jitterMs: Long? = null,
    val transferMode: RouteTransferMeasurementMode = RouteTransferMeasurementMode.SOCKS_PROXY,
    val transferValidated: Boolean = false,
    val endpointFailure: RouteEndpointFailure? = null,
    val txDelta: Long = 0L,
    val rxDelta: Long = 0L,
    val mtuValidated: Boolean = false,
)

sealed interface RouteSpeedQualifierEvent {
    data class Running(
        val candidateIds: List<String>,
        val stage: RouteSpeedProbeStage,
    ) : RouteSpeedQualifierEvent

    data class Completed(
        val results: List<RouteSpeedProbeResult>,
    ) : RouteSpeedQualifierEvent
}

enum class SniCandidateStage { STARTING, PROBING, REJECTED, FAILED, PASSED, EXHAUSTED }

data class SniCandidateProgress(
    val candidateId: String,
    val candidateLabel: String,
    val candidateIndex: Int,
    val candidateCount: Int,
    val stage: SniCandidateStage,
    val routeSummary: String,
    val detail: String,
)

class ProfileLatencyTester(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = AdvancedSettingsStore(appContext)
    private val profileStore = ProfileStore(appContext)
    private val adaptiveProfileStore = AdaptiveProfileStore(appContext)
    private val adaptivePlanner = AdaptiveCandidatePlanner(adaptiveProfileStore)
    private val fingerprintResolver = NetworkFingerprintResolver(appContext)
    private val routeConnectivityProbe = VpnConnectivityProbe { TunStats.ZERO }
    private val adaptiveProbe = AdaptiveConnectionProbe(
        connectivityProbe = routeConnectivityProbe,
        tunConnectivityProbe = VpnConnectivityProbe { TunStats.ZERO },
        dnsProbe = SocksDnsProbe(),
    )
    private val transferProbe = RouteTransferProbe()

    suspend fun measure(profile: ProxyProfile): Long =
        measureInternal(
            profile = profile,
            probeCount = PROBE_COUNT,
            minSuccessCount = MIN_SUCCESS_COUNT,
            resolveCountry = false,
            probeTimeoutMs = PROBE_TIMEOUT_MS,
            parallelProbes = false,
        ).latencyMs

    suspend fun prepareSniMakerSession(): SniMakerTestSession = withContext(Dispatchers.IO) {
        val settings = settingsStore.snapshot().validated()
        val network = fingerprintResolver.captureAdaptive()
        val selectedProfile = profileStore.selectedProfile()
        val preferred = adaptivePlanner.candidates(settings, network, selectedProfile)
            .firstOrNull(AdaptiveCandidate::learned)
            ?.id
        AppLogRepository.info(
            LogSource.APP,
            "SNI Maker adaptive session network=${network.summary()} preferred=${preferred ?: "none"}",
        )
        SniMakerTestSession(settings, network, preferred)
    }

    suspend fun prepareRouteSpeedTest(
        profileOverride: ProxyProfile? = null,
        onProgress: suspend (RoutePreparationProgress) -> Unit = {},
        onSavedRouteResolved: suspend (AdaptiveSavedRoute?, AdaptiveSavedRoute?) -> Unit = { _, _ -> },
    ): RouteSpeedTestPlan = withContext(Dispatchers.IO) {
        val settings = settingsStore.snapshot().validated()
        val profile = (profileOverride ?: profileStore.selectedProfile()).copy()
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.PROFILE_SNAPSHOT,
                completed = 1,
                total = 1,
                currentTarget = profile.name,
                detail = "Profile settings frozen",
            ),
        )
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.NETWORK_DETECTION,
                total = 1,
                detail = "Reading the active network fingerprint",
            ),
        )
        val networkContext = fingerprintResolver.captureAdaptiveContext()
        val network = networkContext.fingerprint
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.NETWORK_DETECTION,
                completed = 1,
                total = 1,
                currentTarget = network.transport,
                detail = "Network fingerprint ready",
            ),
        )
        val signature = adaptivePlanner.signature(settings, profile)
        val savedChampion = adaptiveProfileStore.savedRoute(network, profile, signature)
        val savedBackup = adaptiveProfileStore.savedBackupRoute(network, profile, signature)
        onSavedRouteResolved(savedChampion, savedBackup)
        val savedEdges = listOfNotNull(savedChampion, savedBackup).map { saved ->
            MciEdge(saved.address, saved.port, saved.role, saved.maxSplit)
        }
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.EDGE_POOL,
                detail = "Collecting current, original, saved, DNS and official edges",
            ),
        )
        val discovery = CloudflareEdgeDiscovery.create(appContext).discover(
            settings = settings,
            profile = profile,
            networkContext = networkContext,
            profileSignature = signature,
            savedEdges = savedEdges,
            onProgress = { progress ->
                onProgress(progress.toRoutePreparationProgress())
            },
        )
        val selectedDiscovery = validateDiscoveredEdges(
            settings = settings,
            profile = profile,
            network = network,
            signature = signature,
            discovery = discovery,
            onProgress = onProgress,
        )
        val selectedEdges = selectedDiscovery.selected.mapIndexed { index, edge ->
            edge.toMciEdge(
                role = if (index < selectedDiscovery.primary.size) {
                    "discovered-${index + 1}"
                } else {
                    "discovered-backup-${index - selectedDiscovery.primary.size + 1}"
                },
                maxSplit = settings.primaryMaxSplit,
            )
        }
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.ROUTE_MATRIX,
                total = 1,
                healthy = selectedEdges.size,
                detail = "Combining Edge, DNS, tuning and MTU routes",
            ),
        )
        val cloudflareEligible = profile.usesAdvancedSettingsIdentity() ||
            LocalForwardProfile.isLocalForward(profile) ||
            discovery.suitability.status == CloudflareSuitability.ELIGIBLE
        val candidates = adaptivePlanner.routeSpeedCandidates(
            base = settings,
            network = network,
            profile = profile,
            discoveredEdges = selectedEdges,
            cloudflareEligible = cloudflareEligible,
        )
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.ROUTE_MATRIX,
                completed = candidates.size,
                total = candidates.size,
                healthy = selectedEdges.size,
                detail = "${candidates.size} route candidates ready",
            ),
        )
        AppLogRepository.info(
            LogSource.APP,
            "Route Speed Test plan profile=${profile.name} network=${network.summary()} " +
                "discovery=${selectedDiscovery.discoveryId} suitability=${discovery.suitability.status} " +
                "edges=${selectedEdges.joinToString { "${it.address}:${it.port}" }} " +
                "logicalCandidates=${candidates.size}",
        )
        RouteSpeedTestPlan(
            profile = profile,
            session = SniMakerTestSession(
                settings = settings,
                network = network,
                initialPreferredCandidateId = candidates.firstOrNull(AdaptiveCandidate::learned)?.id,
            ),
            signature = signature,
            candidates = candidates,
            savedChampionId = savedChampion?.id,
            savedChampionLabel = savedChampion?.label,
            savedChampion = savedChampion,
            savedBackupId = savedBackup?.id,
            savedBackupLabel = savedBackup?.label,
            savedBackup = savedBackup,
            discoveryId = selectedDiscovery.discoveryId,
            discoverySummary = buildString {
                append(discovery.suitability.status.name.lowercase())
                append(" • ").append(discovery.candidates.size).append(" preflight")
                append(" • ").append(selectedEdges.size).append(" Xray-selected edges")
            },
            discoveredEdgeCount = selectedEdges.size,
            underlyingNetwork = networkContext.network,
        )
    }

    suspend fun discoverConnectEdgePool(
        settings: com.uacspoofer.mobile.settings.AdvancedSettingsData,
        profile: ProxyProfile,
        networkContext: com.uacspoofer.mobile.vpn.UnderlyingNetworkSnapshot,
        profileSignature: String,
        rescueGeneration: Long = 0L,
    ): List<MciEdge> = withContext(Dispatchers.IO) {
        val validated = settings.validated()
        val network = networkContext.fingerprint
        AppLogRepository.info(
            LogSource.ADAPTIVE,
            "Connect rescue discovery start profile=${profile.name} network=${network.summary()}",
        )
        fun publish(
            phase: com.uacspoofer.mobile.vpn.ConnectRescuePhase,
            completed: Int = 0,
            total: Int = 0,
            healthy: Int = 0,
            currentTarget: String = "",
            foundCount: Int = 0,
        ) {
            if (rescueGeneration == 0L) return
            com.uacspoofer.mobile.vpn.ConnectRescueStore.update(rescueGeneration) { current ->
                current.copy(
                    phase = phase,
                    completed = if (phase == current.phase) maxOf(current.completed, completed) else completed,
                    total = total,
                    healthy = if (phase == current.phase) maxOf(current.healthy, healthy) else healthy,
                    currentTarget = currentTarget.ifBlank { current.currentTarget },
                    foundCount = if (foundCount > 0) foundCount else current.foundCount,
                )
            }
        }
        publish(com.uacspoofer.mobile.vpn.ConnectRescuePhase.COLLECTING)
        val discovery = CloudflareEdgeDiscovery.create(appContext).discover(
            settings = validated,
            profile = profile,
            networkContext = networkContext,
            profileSignature = profileSignature,
            savedEdges = emptyList(),
            onProgress = { progress ->
                publish(
                    phase = when (progress.phase) {
                        CloudflareDiscoveryPhase.COLLECTING -> com.uacspoofer.mobile.vpn.ConnectRescuePhase.COLLECTING
                        CloudflareDiscoveryPhase.PREFLIGHT -> com.uacspoofer.mobile.vpn.ConnectRescuePhase.PREFLIGHT
                    },
                    completed = progress.completed,
                    total = progress.total,
                    healthy = progress.healthy,
                    currentTarget = progress.currentTarget,
                )
            },
        )
        if (discovery.suitability.status == CloudflareSuitability.INELIGIBLE) {
            AppLogRepository.info(
                LogSource.ADAPTIVE,
                "Connect rescue skipped; Cloudflare suitability=${discovery.suitability.status} " +
                    "reason=${discovery.suitability.reason}",
            )
            return@withContext emptyList()
        }
        if (discovery.suitability.status == CloudflareSuitability.UNKNOWN) {
            AppLogRepository.info(
                LogSource.ADAPTIVE,
                "Connect rescue continuing without DNS Cloudflare evidence; " +
                    "sampling official CIDRs reason=${discovery.suitability.reason}",
            )
        }
        val eligible = discovery.candidates
            .filter { edge -> edge.reserved || edge.preflight?.tcpSucceeded == true }
            .sortedWith(
                compareByDescending<CloudflareEdgeCandidate> { it.score }
                    .thenBy { it.preflight?.tcpLatencyMs ?: Long.MAX_VALUE },
            )
            .take(CONNECT_RESCUE_SCREEN_LIMIT)
        if (eligible.isEmpty()) {
            AppLogRepository.warning(LogSource.ADAPTIVE, "Connect rescue had no TCP-reachable Cloudflare edge")
            return@withContext emptyList()
        }
        val customRuntime = if (profile.usesAdvancedSettingsIdentity()) {
            com.uacspoofer.mobile.mci.MciXrayRuntimeOptions.DEFAULT
        } else {
            com.uacspoofer.mobile.mci.MciXrayRuntimeOptions(
                identityOverride = profile.runtimeIdentity(validated),
                preserveEmptyAlpn = true,
                preserveTransportFields = true,
            )
        }
        val provisionalPlan = RouteSpeedTestPlan(
            profile = profile,
            session = SniMakerTestSession(validated, network, null),
            signature = profileSignature,
            candidates = emptyList(),
            savedChampionId = null,
            savedChampionLabel = null,
            savedChampion = null,
            savedBackupId = null,
            savedBackupLabel = null,
            savedBackup = null,
            discoveryId = discovery.discoveryId,
            discoverySummary = "connect rescue",
            discoveredEdgeCount = eligible.size,
            underlyingNetwork = networkContext.network,
        )
        publish(
            phase = com.uacspoofer.mobile.vpn.ConnectRescuePhase.SCREENING,
            completed = 0,
            total = eligible.size,
            currentTarget = eligible.firstOrNull()?.let { "${it.address}:${it.port}" }.orEmpty(),
        )
        val screenedCompleted = AtomicInteger(0)
        val screenedHealthy = AtomicInteger(0)
        val isolatedByEndpoint = coroutineScope {
            val slots = Semaphore(EDGE_XRAY_VALIDATION_WORKERS)
            eligible.mapIndexed { index, edge ->
                async {
                    slots.withPermit {
                        val endpoint = "${edge.address}:${edge.port}"
                        val candidate = AdaptiveCandidate(
                            id = "connect-rescue-$index",
                            label = "Connect rescue ${edge.address}",
                            edge = edge.toMciEdge("connect-rescue-${index + 1}", validated.primaryMaxSplit),
                            settings = validated.copy(finalmaskDelayMs = 20).validated(),
                            runtimeOptions = customRuntime,
                        )
                        val result = measureRouteSpeedCandidate(
                            plan = provisionalPlan,
                            candidate = candidate,
                            transferConfig = null,
                        )
                        val finished = screenedCompleted.incrementAndGet()
                        if (result.accepted) screenedHealthy.incrementAndGet()
                        publish(
                            phase = com.uacspoofer.mobile.vpn.ConnectRescuePhase.SCREENING,
                            completed = finished,
                            total = eligible.size,
                            healthy = screenedHealthy.get(),
                            currentTarget = endpoint,
                        )
                        endpoint to result
                    }
                }
            }.awaitAll().toMap()
        }
        val xrayAccepted = eligible.mapNotNull { edge ->
            val result = isolatedByEndpoint["${edge.address}:${edge.port}"]
                ?.takeIf(RouteSpeedProbeResult::accepted)
                ?: return@mapNotNull null
            edge.copy(score = edge.score + result.score * 10)
        }
        publish(
            phase = com.uacspoofer.mobile.vpn.ConnectRescuePhase.SELECTING,
            completed = eligible.size,
            total = eligible.size,
            healthy = xrayAccepted.size,
        )
        val source = xrayAccepted.ifEmpty { eligible }
        val (primary, backups) = selectSubnetDiverseEdges(source)
        val selected = (primary + backups).mapIndexed { index, edge ->
            edge.toMciEdge("connect-pool-${index + 1}", validated.primaryMaxSplit)
        }
        publish(
            phase = com.uacspoofer.mobile.vpn.ConnectRescuePhase.SELECTING,
            completed = selected.size,
            total = selected.size.coerceAtLeast(1),
            healthy = selected.size,
            foundCount = selected.size,
        )
        AppLogRepository.info(
            LogSource.ADAPTIVE,
            "Connect rescue discovery done screened=${eligible.size} xrayAccepted=${xrayAccepted.size} " +
                "selected=${selected.joinToString { "${it.address}:${it.port}" }}",
        )
        selected
    }

    private suspend fun validateDiscoveredEdges(
        settings: com.uacspoofer.mobile.settings.AdvancedSettingsData,
        profile: ProxyProfile,
        network: NetworkFingerprint,
        signature: String,
        discovery: CloudflareEdgeDiscoveryResult,
        onProgress: suspend (RoutePreparationProgress) -> Unit,
    ): CloudflareEdgeDiscoveryResult {
        if (discovery.suitability.status != CloudflareSuitability.ELIGIBLE) {
            onProgress(
                RoutePreparationProgress(
                    step = RoutePreparationStep.XRAY_SCREENING,
                    completed = 1,
                    total = 1,
                    detail = "Cloudflare edge screening is not required for this profile",
                ),
            )
            onProgress(
                RoutePreparationProgress(
                    step = RoutePreparationStep.CONNECTIVITY_VALIDATION,
                    completed = 1,
                    total = 1,
                    detail = "Using the original direct route",
                ),
            )
            return discovery
        }
        val eligible = discovery.candidates.filter { edge ->
            edge.reserved || edge.preflight?.tcpSucceeded == true
        }
        if (eligible.isEmpty()) {
            onProgress(
                RoutePreparationProgress(
                    step = RoutePreparationStep.XRAY_SCREENING,
                    completed = 1,
                    total = 1,
                    detail = "No preflight edge was available for Xray screening",
                ),
            )
            return discovery
        }
        val validationCandidates = buildList {
            val customRuntime = if (profile.usesAdvancedSettingsIdentity()) {
                com.uacspoofer.mobile.mci.MciXrayRuntimeOptions.DEFAULT
            } else {
                com.uacspoofer.mobile.mci.MciXrayRuntimeOptions(
                    identityOverride = profile.runtimeIdentity(settings),
                    preserveEmptyAlpn = true,
                    preserveTransportFields = true,
                )
            }
            eligible.forEachIndexed { index, edge ->
                val endpoint = edge.toMciEdge("edge-check-${index + 1}", settings.primaryMaxSplit)
                add(
                    AdaptiveCandidate(
                        id = "edge-check-$index-stable",
                        label = "Edge ${edge.address} stable",
                        edge = endpoint,
                        settings = settings.copy(finalmaskDelayMs = 20).validated(),
                        runtimeOptions = customRuntime,
                    ),
                )
                add(
                    AdaptiveCandidate(
                        id = "edge-check-$index-deep",
                        label = "Edge ${edge.address} deep",
                        edge = endpoint.copy(finalmaskMaxSplit = 100),
                        settings = settings.copy(finalmaskDelayMs = 5).validated(),
                        runtimeOptions = customRuntime,
                    ),
                )
            }
        }
        val provisionalPlan = RouteSpeedTestPlan(
            profile = profile,
            session = SniMakerTestSession(settings, network, null),
            signature = signature,
            candidates = validationCandidates,
            savedChampionId = null,
            savedChampionLabel = null,
            savedChampion = null,
            savedBackupId = null,
            savedBackupLabel = null,
            savedBackup = null,
            discoveryId = discovery.discoveryId,
            discoverySummary = "edge validation",
            discoveredEdgeCount = eligible.size,
            underlyingNetwork = null,
        )
        var completed = 0
        var screenedHealthy = 0
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.XRAY_SCREENING,
                total = validationCandidates.size,
                detail = "Starting Xray edge screening",
            ),
        )
        val results = measureRouteSpeedQualifier(provisionalPlan, validationCandidates) { event ->
            if (event is RouteSpeedQualifierEvent.Completed) {
                completed += event.results.size
                screenedHealthy += event.results.count(RouteSpeedProbeResult::accepted)
                onProgress(
                    RoutePreparationProgress(
                        step = RoutePreparationStep.XRAY_SCREENING,
                        completed = completed.coerceAtMost(validationCandidates.size),
                        total = validationCandidates.size,
                        healthy = screenedHealthy,
                        currentTarget = event.results.lastOrNull()?.candidate?.edge?.let { "${it.address}:${it.port}" }.orEmpty(),
                        detail = "Fast Xray route screening",
                    ),
                )
            }
        }
        val screenedByEndpoint = results.values
            .filter(RouteSpeedProbeResult::accepted)
            .groupBy { result -> "${result.candidate.edge.address}:${result.candidate.edge.port}" }
            .mapValues { (_, values) ->
                values.sortedWith(
                    compareByDescending<RouteSpeedProbeResult> { it.score }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE },
                ).firstOrNull()
            }
        val candidatesForIsolatedGate = eligible.mapNotNull { edge ->
            screenedByEndpoint["${edge.address}:${edge.port}"]
        }
        val isolatedCompleted = AtomicInteger(0)
        val isolatedHealthy = AtomicInteger(0)
        onProgress(
            RoutePreparationProgress(
                step = RoutePreparationStep.CONNECTIVITY_VALIDATION,
                total = candidatesForIsolatedGate.size,
                detail = "Starting isolated HTTP and DNS validation",
            ),
        )
        val isolatedByEndpoint = coroutineScope {
            val slots = Semaphore(EDGE_XRAY_VALIDATION_WORKERS)
            candidatesForIsolatedGate.map { screened ->
                async {
                    slots.withPermit {
                        val result = measureRouteSpeedCandidate(
                            plan = provisionalPlan,
                            candidate = screened.candidate,
                            transferConfig = null,
                        )
                        val finished = isolatedCompleted.incrementAndGet()
                        if (result.accepted) isolatedHealthy.incrementAndGet()
                        onProgress(
                            RoutePreparationProgress(
                                step = RoutePreparationStep.CONNECTIVITY_VALIDATION,
                                completed = finished,
                                total = candidatesForIsolatedGate.size,
                                healthy = isolatedHealthy.get(),
                                currentTarget = "${result.candidate.edge.address}:${result.candidate.edge.port}",
                                detail = "Isolated HTTP + DNS validation",
                            ),
                        )
                        "${result.candidate.edge.address}:${result.candidate.edge.port}" to result
                    }
                }
            }.awaitAll().toMap()
        }
        val xrayAccepted = eligible.mapNotNull { edge ->
            val result = isolatedByEndpoint["${edge.address}:${edge.port}"]
                ?.takeIf(RouteSpeedProbeResult::accepted)
                ?: return@mapNotNull null
            edge.copy(score = edge.score + result.score * 10)
        }
        if (xrayAccepted.isEmpty()) {
            AppLogRepository.warning(
                LogSource.APP,
                "Edge Discovery Xray validation had no accepted edge; keeping reserved fail-open shortlist",
            )
            val (primary, backups) = selectSubnetDiverseEdges(eligible)
            return discovery.copy(primary = primary, backups = backups)
        }
        val (primary, backups) = selectSubnetDiverseEdges(xrayAccepted)
        return discovery.copy(primary = primary, backups = backups)
    }

    private fun CloudflareDiscoveryProgress.toRoutePreparationProgress(): RoutePreparationProgress =
        RoutePreparationProgress(
            step = when (phase) {
                CloudflareDiscoveryPhase.COLLECTING -> RoutePreparationStep.EDGE_POOL
                CloudflareDiscoveryPhase.PREFLIGHT -> RoutePreparationStep.TCP_TLS_PREFLIGHT
            },
            completed = completed,
            total = total,
            healthy = healthy,
            currentTarget = currentTarget,
            detail = detail,
        )

    suspend fun measureRouteSpeedCandidate(
        plan: RouteSpeedTestPlan,
        candidate: AdaptiveCandidate,
        transferConfig: RouteTransferProbeConfig? = RouteTransferProbeConfig(),
        onStage: suspend (RouteSpeedProbeStage) -> Unit = {},
    ): RouteSpeedProbeResult = withContext(Dispatchers.IO) {
        val reservedPort = reservePort()
        val probeSettings = candidate.settings.copy(
            connectionMode = CONNECTION_MODE_PROXY,
            socksAddress = "127.0.0.1",
            socksPort = reservedPort,
            socksUdp = false,
        ).validated()
        val probeCandidate = candidate.copy(settings = probeSettings)
        val core = MciXrayCore(appContext)
        try {
            onStage(RouteSpeedProbeStage.STARTING)
            val report = withTimeoutOrNull(ROUTE_SPEED_CANDIDATE_TIMEOUT_MS) {
                core.start(
                    candidate.edge,
                    probeSettings,
                    plan.profile,
                    candidate.runtimeOptions.copy(quietLogging = true),
                )
                onStage(RouteSpeedProbeStage.PROBING)
                adaptiveProbe.verifyForRouteSpeed(probeCandidate)
            } ?: throw SocketTimeoutException(
                "Route ${candidate.id} timed out after ${ROUTE_SPEED_CANDIDATE_TIMEOUT_MS}ms",
            )
            val transfer = if (report.accepted && transferConfig != null) {
                transferProbe.measure(
                    measurementMode = RouteTransferMeasurementMode.SOCKS_PROXY,
                    socksProxy = RouteSocksProxy(probeSettings.socksAddress, probeSettings.socksPort),
                    config = transferConfig,
                )
            } else {
                null
            }
            val transferMs = report.http.durationMs.coerceAtLeast(1L)
            val legacyThroughput = (report.http.totalBytes.toLong() * 8L / transferMs).coerceAtLeast(0L)
            val throughputKbps = transfer?.takeIf(RouteTransferProbeResult::success)?.let {
                minOf(it.uploadKbps, it.downloadKbps)
            }?.takeIf { it > 0L } ?: transfer?.downloadKbps?.takeIf { it > 0L } ?: legacyThroughput
            val transferAccepted = when {
                transfer == null -> report.accepted
                transfer.success -> true
                transfer.endpointUnavailable -> true
                else -> false
            }
            val accepted = report.accepted && transferAccepted
            val adjustedScore = when {
                !accepted -> 0
                transfer?.success == true -> (report.score + transferQualityBonus(transfer)).coerceAtMost(100)
                transfer?.endpointUnavailable == true -> report.score.coerceAtMost(75)
                else -> report.score
            }
            val transferDetail = transfer?.let(::transferDetail).orEmpty()
            AppLogRepository.info(
                LogSource.APP,
                "Route Speed Test candidate=${candidate.id} profile=${plan.profile.name} " +
                    "downloadKbps=$throughputKbps uploadKbps=${transfer?.uploadKbps ?: 0L} " +
                    "${report.detail()}$transferDetail",
            )
            RouteSpeedProbeResult(
                candidate = candidate,
                accepted = accepted,
                score = adjustedScore,
                latencyMs = transfer?.latencyMedianMs ?: report.http.latencyMs,
                dnsLatencyMs = report.dns.latencyMs,
                payloadBytes = report.http.totalBytes + (transfer?.uploadBytes ?: 0) + (transfer?.downloadBytes ?: 0),
                durationMs = report.durationMs,
                throughputKbps = throughputKbps,
                httpSucceeded = report.http.succeededTargets,
                httpAttempted = report.http.attemptedTargets,
                dnsSucceeded = report.dns.success,
                detail = report.detail() + transferDetail,
                error = transfer?.candidateFailure,
                uploadBytes = transfer?.uploadBytes ?: 0,
                downloadBytes = transfer?.downloadBytes ?: report.http.totalBytes,
                uploadKbps = transfer?.uploadKbps ?: 0L,
                downloadKbps = transfer?.downloadKbps ?: legacyThroughput,
                jitterMs = transfer?.jitterMs,
                transferMode = transfer?.measurementMode ?: RouteTransferMeasurementMode.SOCKS_PROXY,
                transferValidated = transfer?.byteValidationPassed == true,
                endpointFailure = transfer?.endpointFailure,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.uiMessage()
            AppLogRepository.warning(
                LogSource.APP,
                "Route Speed Test candidate=${candidate.id} profile=${plan.profile.name} failed",
                error,
            )
            RouteSpeedProbeResult(
                candidate = candidate,
                accepted = false,
                score = 0,
                latencyMs = null,
                dnsLatencyMs = null,
                payloadBytes = 0,
                durationMs = 0L,
                throughputKbps = 0L,
                httpSucceeded = 0,
                httpAttempted = 0,
                dnsSucceeded = false,
                detail = detail,
                error = detail,
            )
        } finally {
            runCatching { core.stop() }
                .onFailure { AppLogRepository.warning(LogSource.XRAY, "Route Speed Test core cleanup failed", it) }
            releasePort(reservedPort)
        }
    }

    suspend fun measureRouteSpeedNativeCandidate(
        plan: RouteSpeedTestPlan,
        candidate: AdaptiveCandidate,
        transferConfig: RouteTransferProbeConfig,
        onStage: suspend (RouteSpeedProbeStage) -> Unit = {},
    ): RouteSpeedProbeResult = withContext(Dispatchers.IO) {
        try {
            onStage(RouteSpeedProbeStage.STARTING)
            onStage(RouteSpeedProbeStage.PROBING)
            val native = RouteMtuProbeCoordinator.measure(
                context = appContext,
                request = RouteNativeMtuProbeRequest(
                    candidate = candidate,
                    profile = plan.profile,
                    transferConfig = transferConfig,
                    expectedNetworkKey = plan.session.network.exactStorageKey(),
                    underlyingNetwork = plan.underlyingNetwork,
                ),
            )
            val transfer = native.transfer
            val bidirectionalKbps = if (transfer.success) {
                minOf(transfer.uploadKbps, transfer.downloadKbps)
            } else {
                transfer.downloadKbps
            }
            val score = nativeMtuScore(native)
            RouteSpeedProbeResult(
                candidate = candidate,
                accepted = native.accepted,
                score = score,
                latencyMs = transfer.latencyMedianMs,
                dnsLatencyMs = native.dnsLatencyMs,
                payloadBytes = transfer.uploadBytes + transfer.downloadBytes,
                durationMs = transfer.uploadDurationMs + transfer.downloadDurationMs,
                throughputKbps = bidirectionalKbps,
                httpSucceeded = native.httpSucceeded,
                httpAttempted = native.httpAttempted,
                dnsSucceeded = native.dnsSucceeded,
                detail = native.detail,
                error = if (native.accepted) null else transfer.candidateFailure ?: native.detail,
                uploadBytes = transfer.uploadBytes,
                downloadBytes = transfer.downloadBytes,
                uploadKbps = transfer.uploadKbps,
                downloadKbps = transfer.downloadKbps,
                jitterMs = transfer.jitterMs,
                transferMode = RouteTransferMeasurementMode.NATIVE_TUN,
                transferValidated = transfer.byteValidationPassed,
                endpointFailure = transfer.endpointFailure,
                txDelta = native.txDelta,
                rxDelta = native.rxDelta,
                mtuValidated = native.accepted,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (permission: RouteProbePermissionRequiredException) {
            throw permission
        } catch (busy: RouteProbeBusyException) {
            throw busy
        } catch (error: Throwable) {
            val detail = error.uiMessage()
            AppLogRepository.warning(LogSource.TUN, "Native MTU candidate=${candidate.id} failed", error)
            RouteSpeedProbeResult(
                candidate = candidate,
                accepted = false,
                score = 0,
                latencyMs = null,
                dnsLatencyMs = null,
                payloadBytes = 0,
                durationMs = 0L,
                throughputKbps = 0L,
                httpSucceeded = 0,
                httpAttempted = 0,
                dnsSucceeded = false,
                detail = detail,
                error = detail,
                transferMode = RouteTransferMeasurementMode.NATIVE_TUN,
            )
        }
    }

    private fun nativeMtuScore(result: com.uacspoofer.mobile.vpn.RouteNativeMtuProbeResult): Int {
        if (!result.accepted) return 0
        var score = 35
        score += (result.httpSucceeded.coerceAtMost(3) * 8)
        if (result.dnsSucceeded) score += 15
        if (result.transfer.success) score += 15 else if (result.transfer.endpointUnavailable) score += 5
        if (result.txDelta > 0L && result.rxDelta > 0L) score += 10
        score += when (result.transfer.latencyMedianMs) {
            null -> 0
            in 0L..500L -> 8
            in 501L..1_200L -> 6
            in 1_201L..2_500L -> 3
            else -> 1
        }
        return score.coerceAtMost(100)
    }

    private fun transferQualityBonus(result: RouteTransferProbeResult): Int {
        if (!result.success) return 0
        val bidirectional = minOf(result.uploadKbps, result.downloadKbps)
        return when {
            bidirectional >= 8_000L -> 15
            bidirectional >= 2_000L -> 12
            bidirectional >= 512L -> 9
            bidirectional >= 128L -> 6
            else -> 3
        }
    }

    private fun transferDetail(result: RouteTransferProbeResult): String = buildString {
        append(" | transferMode=${result.measurementMode.name.lowercase()}")
        append(" upload=${result.uploadBytes}/${result.requestedUploadBytes}@${result.uploadKbps}Kbps")
        append(" download=${result.downloadBytes}/${result.requestedDownloadBytes}@${result.downloadKbps}Kbps")
        append(" ping=${result.latencyMedianMs ?: -1}ms jitter=${result.jitterMs ?: -1}ms")
        append(" bytesValid=${result.byteValidationPassed}")
        result.endpointFailure?.let { append(" endpoint=${it.kind}:${it.statusCode}") }
        result.candidateFailure?.let { append(" candidateFailure=[$it]") }
    }

    suspend fun measureRouteSpeedQualifier(
        plan: RouteSpeedTestPlan,
        candidates: List<AdaptiveCandidate>,
        onEvent: suspend (RouteSpeedQualifierEvent) -> Unit = {},
    ): Map<String, RouteSpeedProbeResult> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyMap()
        val groups = candidates.groupBy(::routeScreeningKey)
        val representatives = groups.values.map(List<AdaptiveCandidate>::first)
        val representativesById = representatives.associateBy(AdaptiveCandidate::id)
        val finalResults = LinkedHashMap<String, RouteSpeedProbeResult>()
        val completedGroupKeys = HashSet<String>()
        AppLogRepository.info(
            LogSource.APP,
            "Route Speed fast qualifier logical=${candidates.size} runtime=${representatives.size} " +
                "batches=${(representatives.size + ROUTE_SCREEN_BATCH_SIZE - 1) / ROUTE_SCREEN_BATCH_SIZE}",
        )

        suspend fun runBatch(batch: List<AdaptiveCandidate>): List<RouteSpeedProbeResult> =
            measureScreeningBatchResilient(plan, batch) { representativeId, stage ->
                val representative = checkNotNull(representativesById[representativeId]) {
                    "Unknown qualifier representative $representativeId"
                }
                val logicalIds = groups[routeScreeningKey(representative)]
                    .orEmpty()
                    .map(AdaptiveCandidate::id)
                if (logicalIds.isNotEmpty()) {
                    onEvent(RouteSpeedQualifierEvent.Running(logicalIds, stage))
                }
            }

        suspend fun emitSharedGroup(representative: RouteSpeedProbeResult) {
            val key = routeScreeningKey(representative.candidate)
            if (!completedGroupKeys.add(key)) return
            val results = groups[key].orEmpty().map { candidate ->
                representative.copy(
                    candidate = candidate,
                    detail = representative.detail +
                        " | shared HTTP preflight; isolated HTTP/DNS verification follows",
                )
            }
            results.forEach { finalResults[it.candidate.id] = it }
            if (results.isNotEmpty()) onEvent(RouteSpeedQualifierEvent.Completed(results))
        }

        suspend fun emitIsolatedFallbackGroup(representative: RouteSpeedProbeResult) {
            val key = routeScreeningKey(representative.candidate)
            if (key in completedGroupKeys) return
            val results = groups[key].orEmpty().map { candidate ->
                if (candidate.id == representative.candidate.id) {
                    representative
                } else {
                    measureRouteSpeedCandidate(plan, candidate, transferConfig = null) { stage ->
                        onEvent(RouteSpeedQualifierEvent.Running(listOf(candidate.id), stage))
                    }
                }
            }
            completedGroupKeys += key
            results.forEach { finalResults[it.candidate.id] = it }
            if (results.isNotEmpty()) onEvent(RouteSpeedQualifierEvent.Completed(results))
        }

        val firstPass = LinkedHashMap<String, RouteSpeedProbeResult>()
        for (batch in representatives.chunked(ROUTE_SCREEN_BATCH_SIZE)) {
            val batchKeys = batch.mapTo(LinkedHashSet()) { routeScreeningKey(it) }
            runBatch(batch).forEach { result ->
                val key = routeScreeningKey(result.candidate)
                firstPass[key] = result
                if (result.accepted && !result.detail.startsWith(ISOLATED_QUALIFIER_FALLBACK)) {
                    emitSharedGroup(result)
                }
            }

            val failed = batch.filter { candidate ->
                val result = firstPass[routeScreeningKey(candidate)]
                result?.accepted != true && result?.detail?.startsWith(ISOLATED_QUALIFIER_FALLBACK) != true
            }
            if (failed.isNotEmpty()) {
                AppLogRepository.info(
                    LogSource.APP,
                    "Route Speed fast qualifier retrying ${failed.size} runtime failures in the current batch",
                )
                runBatch(failed).forEach { result ->
                    val key = routeScreeningKey(result.candidate)
                    val previous = firstPass[key]
                    if (result.accepted || previous == null || result.score > previous.score) {
                        firstPass[key] = result
                    }
                }
            }

            batchKeys.forEach { key ->
                val representative = checkNotNull(firstPass[key]) {
                    "Missing qualifier result for runtime group $key"
                }
                if (representative.detail.startsWith(ISOLATED_QUALIFIER_FALLBACK)) {
                    emitIsolatedFallbackGroup(representative)
                } else {
                    emitSharedGroup(representative)
                }
            }
        }
        candidates.associate { candidate ->
            candidate.id to checkNotNull(finalResults[candidate.id]) {
                "Missing qualifier result for ${candidate.id}"
            }
        }
    }

    private suspend fun measureScreeningBatchResilient(
        plan: RouteSpeedTestPlan,
        candidates: List<AdaptiveCandidate>,
        onStage: suspend (String, RouteSpeedProbeStage) -> Unit,
    ): List<RouteSpeedProbeResult> {
        return try {
            measureScreeningBatch(plan, candidates, onStage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogRepository.warning(
                LogSource.APP,
                "Route Speed batch preflight failed size=${candidates.size}; isolating the batch",
                error,
            )
            if (candidates.size == 1) {
                val candidate = candidates.single()
                listOf(
                    measureRouteSpeedCandidate(plan, candidate, transferConfig = null) { stage ->
                        onStage(candidate.id, stage)
                    }.let { fallback ->
                        fallback.copy(detail = "$ISOLATED_QUALIFIER_FALLBACK ${fallback.detail}")
                    },
                )
            } else {
                val split = (candidates.size + 1) / 2
                measureScreeningBatchResilient(plan, candidates.take(split), onStage) +
                    measureScreeningBatchResilient(plan, candidates.drop(split), onStage)
            }
        }
    }

    private suspend fun measureScreeningBatch(
        plan: RouteSpeedTestPlan,
        candidates: List<AdaptiveCandidate>,
        onStage: suspend (String, RouteSpeedProbeStage) -> Unit,
    ): List<RouteSpeedProbeResult> {
        require(candidates.isNotEmpty()) { "Screening batch is empty" }
        val ports = ArrayList<Int>(candidates.size)
        try {
            repeat(candidates.size) { ports += reservePort() }
        } catch (error: Throwable) {
            ports.forEach(::releasePort)
            throw error
        }
        val probeCandidates = candidates.mapIndexed { index, candidate ->
            candidate.copy(
                settings = candidate.settings.copy(
                    connectionMode = CONNECTION_MODE_PROXY,
                    socksAddress = "127.0.0.1",
                    socksPort = ports[index],
                    socksUdp = false,
                ).validated(),
            )
        }
        val routes = probeCandidates.mapIndexed { index, candidate ->
            MciXrayBatchRoute(
                tag = "r$index",
                edge = candidate.edge,
                settings = candidate.settings,
                runtimeOptions = candidate.runtimeOptions.copy(quietLogging = true),
            )
        }
        val core = MciXrayCore(appContext)
        try {
            probeCandidates.forEach { onStage(it.id, RouteSpeedProbeStage.STARTING) }
            core.startBatch(routes, plan.profile)
            probeCandidates.forEach { onStage(it.id, RouteSpeedProbeStage.PROBING) }
            return coroutineScope {
                val probeSlots = Semaphore(ROUTE_SCREEN_PARALLEL_PROBES)
                probeCandidates.mapIndexed { index, candidate ->
                    async {
                        probeSlots.withPermit {
                            val http = routeConnectivityProbe.verifyScreening(candidate.settings)
                            val duration = http.durationMs.coerceAtLeast(1L)
                            val latencyBonus = when {
                                http.latencyMs == null -> 0
                                http.latencyMs <= 500L -> 20
                                http.latencyMs <= 1_200L -> 15
                                http.latencyMs <= 2_500L -> 10
                                else -> 5
                            }
                            val score = if (http.success) 60 + latencyBonus else 0
                            RouteSpeedProbeResult(
                                candidate = candidates[index],
                                accepted = http.success,
                                score = score,
                                latencyMs = http.latencyMs,
                                dnsLatencyMs = null,
                                payloadBytes = http.totalBytes,
                                durationMs = duration,
                                throughputKbps = 0L,
                                httpSucceeded = http.succeededTargets,
                                httpAttempted = http.attemptedTargets,
                                dnsSucceeded = false,
                                detail = "fast HTTP preflight ${if (http.success) "passed" else "failed"}: ${http.detail}",
                                error = if (http.success) null else http.detail,
                            )
                        }
                    }
                }.awaitAll()
            }
        } finally {
            runCatching { core.stop() }
                .onFailure { AppLogRepository.warning(LogSource.XRAY, "Route batch cleanup failed", it) }
            ports.forEach(::releasePort)
        }
    }

    private fun routeScreeningKey(candidate: AdaptiveCandidate): String = listOf(
        candidate.edge.address,
        candidate.edge.port,
        candidate.edge.finalmaskMaxSplit,
        candidate.settings.finalmaskPacket,
        candidate.settings.finalmaskLength,
        candidate.settings.finalmaskDelayMs,
        candidate.settings.domainStrategy,
        candidate.settings.routingDomainStrategy,
        candidate.settings.ipv4Only,
        candidate.settings.keepAliveIdleSeconds,
        candidate.settings.keepAliveIntervalSeconds,
        candidate.runtimeOptions.identityOverride,
        candidate.runtimeOptions.finalmaskEnabled,
        candidate.runtimeOptions.preserveEmptyAlpn,
        candidate.runtimeOptions.preserveTransportFields,
        candidate.runtimeOptions.muxEnabledOverride,
    ).joinToString("|")

    fun selectRouteWinner(
        plan: RouteSpeedTestPlan,
        candidateId: String,
        score: Int,
        metrics: AdaptiveRouteMetrics = AdaptiveRouteMetrics(score),
        backupCandidateId: String? = null,
        backupScore: Int = 0,
        backupMetrics: AdaptiveRouteMetrics = AdaptiveRouteMetrics(backupScore),
    ): Boolean {
        val candidate = plan.candidates.firstOrNull { it.id == candidateId } ?: return false
        adaptiveProfileStore.recordSavedRoute(
            network = plan.session.network,
            profile = plan.profile,
            signature = plan.signature,
            candidate = candidate,
            score = score,
            metrics = metrics,
        )
        val backup = backupCandidateId
            ?.takeIf { it != candidateId }
            ?.let { id -> plan.candidates.firstOrNull { it.id == id } }
        if (backup != null) {
            adaptiveProfileStore.recordSavedBackupRoute(
                network = plan.session.network,
                profile = plan.profile,
                signature = plan.signature,
                candidate = backup,
                score = backupScore,
                metrics = backupMetrics,
            )
        } else {
            adaptiveProfileStore.clearSavedBackupRoute(
                network = plan.session.network,
                profile = plan.profile,
                signature = plan.signature,
            )
        }
        AppLogRepository.info(
            LogSource.APP,
            "Route Speed Test selected candidate=${candidate.id} profile=${plan.profile.name} " +
                "network=${plan.session.network.exactStorageKey()} score=$score " +
                "backup=${backup?.id ?: "none"} backupScore=$backupScore",
        )
        return true
    }

    fun persistRouteSelection(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        champion: AdaptiveCandidate,
        score: Int,
        metrics: AdaptiveRouteMetrics = AdaptiveRouteMetrics(score),
        backupCandidate: AdaptiveCandidate? = null,
        backupScore: Int = 0,
        backupMetrics: AdaptiveRouteMetrics = AdaptiveRouteMetrics(backupScore),
    ) {
        adaptiveProfileStore.recordSavedRoute(
            network = network,
            profile = profile,
            signature = signature,
            candidate = champion,
            score = score,
            metrics = metrics,
        )
        if (backupCandidate != null && backupCandidate.id != champion.id) {
            adaptiveProfileStore.recordSavedBackupRoute(
                network = network,
                profile = profile,
                signature = signature,
                candidate = backupCandidate,
                score = backupScore,
                metrics = backupMetrics,
            )
        } else {
            adaptiveProfileStore.clearSavedBackupRoute(
                network = network,
                profile = profile,
                signature = signature,
            )
        }
        AppLogRepository.info(
            LogSource.APP,
            "Route Speed Test selected candidate=${champion.id} profile=${profile.name} " +
                "network=${network.exactStorageKey()} score=$score " +
                "backup=${backupCandidate?.id ?: "none"} backupScore=$backupScore",
        )
    }

    suspend fun measureForSniMaker(
        profile: ProxyProfile,
        session: SniMakerTestSession,
        preferredCandidateId: String?,
        totalTimeoutMs: Int = MAKER_DEFAULT_TIMEOUT_MS,
        onCandidateProgress: suspend (SniCandidateProgress) -> Unit = {},
    ): SniProfileProbeResult = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + totalTimeoutMs.coerceIn(MIN_MAKER_TIMEOUT_MS, MAX_MAKER_TIMEOUT_MS)
        val signature = adaptivePlanner.signature(session.settings, profile)
        val planned = adaptivePlanner.candidates(session.settings, session.network, profile)
        val candidates = planned.sortedBy { candidate ->
            when (candidate.id) {
                AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID -> 0
                preferredCandidateId -> 1
                else -> 2
            }
        }
        val reservedPort = reservePort()
        var bestReport: com.uacspoofer.mobile.vpn.AdaptiveProbeReport? = null
        var bestCandidate: AdaptiveCandidate? = null
        var lastCandidate: AdaptiveCandidate? = null
        var lastCandidateIndex = 0
        var lastError: Throwable? = null
        try {
            for ((index, candidate) in candidates.withIndex()) {
                currentCoroutineContext().ensureActive()
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs < MIN_ROUTE_BUDGET_MS) break
                lastCandidate = candidate
                lastCandidateIndex = index + 1
                val routeBudgetMs = remainingMs.coerceAtMost(MAX_ROUTE_BUDGET_MS)
                val probeSettings = candidate.settings.copy(
                    connectionMode = CONNECTION_MODE_PROXY,
                    socksAddress = "127.0.0.1",
                    socksPort = reservedPort,
                    socksUdp = false,
                ).validated()
                val probeCandidate = candidate.copy(settings = probeSettings)
                val core = MciXrayCore(appContext)
                try {
                    onCandidateProgress(
                        candidate.progress(
                            index = index,
                            count = candidates.size,
                            stage = SniCandidateStage.STARTING,
                            detail = "Starting Xray route",
                        ),
                    )
                    AppLogRepository.debug(
                        LogSource.APP,
                        "SNI Maker candidate ${index + 1}/${candidates.size} profile=${profile.name} " +
                            probeCandidate.summary(),
                    )
                    val report = withTimeoutOrNull(routeBudgetMs) {
                        core.start(candidate.edge, probeSettings, profile, candidate.runtimeOptions)
                        delay(SNI_MAKER_WARMUP_MS)
                        onCandidateProgress(
                            candidate.progress(
                                index = index,
                                count = candidates.size,
                                stage = SniCandidateStage.PROBING,
                                detail = "Testing HTTP and DNS",
                            ),
                        )
                        val probeBudget = (deadline - SystemClock.elapsedRealtime())
                            .coerceIn(MIN_ROUTE_BUDGET_MS, routeBudgetMs)
                        adaptiveProbe.verifyForSniMaker(probeCandidate, probeBudget)
                    } ?: throw SocketTimeoutException("Candidate ${candidate.id} timed out after ${routeBudgetMs}ms")
                    if (bestReport == null || report.score > bestReport!!.score) {
                        bestReport = report
                        bestCandidate = candidate
                    }
                    AppLogRepository.info(
                        LogSource.APP,
                        "SNI Maker candidate=${candidate.id} profile=${profile.name} ${report.detail()}",
                    )
                    onCandidateProgress(
                        candidate.progress(
                            index = index,
                            count = candidates.size,
                            stage = if (report.accepted) SniCandidateStage.PASSED else SniCandidateStage.REJECTED,
                            detail = report.uiDetail(),
                        ),
                    )
                    if (!report.accepted) continue
                    adaptiveProfileStore.recordWinner(
                        network = session.network,
                        profile = profile,
                        signature = signature,
                        candidate = candidate,
                        score = report.score,
                    )
                    val latency = listOfNotNull(report.http.latencyMs, report.dns.latencyMs)
                        .minOrNull()
                        ?: report.durationMs
                    val exit = lookupExitCountryFast(
                        probeSettings.socksAddress,
                        probeSettings.socksPort,
                    )
                    AppLogRepository.info(
                        LogSource.APP,
                        "SNI Maker country candidate=${candidate.id} profile=${profile.name} " +
                            "source=${exit.source} ip=${exit.ip.ifBlank { "-" }} " +
                            "country=${exit.country.countryCode ?: "XX"}",
                    )
                    return@withContext SniProfileProbeResult(
                        latencyMs = latency,
                        country = exit.country.takeIf(CountryMetadata::isKnown) ?: profile.country,
                        exitIp = exit.ip,
                        countrySource = exit.source,
                        candidateId = candidate.id,
                        candidateLabel = candidate.label,
                        probeDetail = report.uiDetail(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastError = error
                    onCandidateProgress(
                        candidate.progress(
                            index = index,
                            count = candidates.size,
                            stage = SniCandidateStage.FAILED,
                            detail = error.uiMessage(),
                        ),
                    )
                    AppLogRepository.warning(
                        LogSource.APP,
                        "SNI Maker candidate=${candidate.id} profile=${profile.name} failed",
                        error,
                    )
                } finally {
                    runCatching { core.stop() }
                        .onFailure { AppLogRepository.warning(LogSource.XRAY, "SNI Maker core cleanup failed", it) }
                }
            }
        } finally {
            releasePort(reservedPort)
        }
        val best = bestReport?.detail() ?: lastError?.message ?: "no candidate completed"
        val finalCandidate = bestCandidate ?: lastCandidate
        if (finalCandidate != null) {
            onCandidateProgress(
                finalCandidate.progress(
                    index = if (bestCandidate != null) candidates.indexOf(finalCandidate) else lastCandidateIndex - 1,
                    count = candidates.size,
                    stage = SniCandidateStage.EXHAUSTED,
                    detail = bestReport?.uiDetail() ?: lastError.uiMessage(),
                ),
            )
        }
        throw IllegalStateException("No adaptive candidate passed; best=$best")
    }

    private fun AdaptiveCandidate.progress(
        index: Int,
        count: Int,
        stage: SniCandidateStage,
        detail: String,
    ) = SniCandidateProgress(
        candidateId = id,
        candidateLabel = label,
        candidateIndex = index + 1,
        candidateCount = count,
        stage = stage,
        routeSummary = buildString {
            append("edge=").append(edge.address).append(':').append(edge.port)
            append(" | DNS=").append(AdaptiveDnsResolvers.idFor(settings.dnsResolverUrl))
            append(" | fragment=")
            if (runtimeOptions.finalmaskEnabled) {
                append(settings.finalmaskPacket).append('/')
                    .append(settings.finalmaskLength).append('/')
                    .append(settings.finalmaskDelayMs).append("ms")
            } else {
                append("off")
            }
        },
        detail = detail,
    )

    private fun com.uacspoofer.mobile.vpn.AdaptiveProbeReport.uiDetail(): String =
        "HTTP ${http.succeededTargets}/${http.attemptedTargets} | " +
            "DNS ${if (dns.success) "OK" else "failed"} | score=$score | $acceptanceMode"

    private fun Throwable?.uiMessage(): String {
        if (this == null) return "No candidate completed within the time budget"
        val reason = message?.substringBefore('\n')?.take(100).orEmpty()
        return if (reason.isBlank()) javaClass.simpleName else "${javaClass.simpleName}: $reason"
    }

    private suspend fun measureInternal(
        profile: ProxyProfile,
        probeCount: Int,
        minSuccessCount: Int,
        resolveCountry: Boolean,
        probeTimeoutMs: Int,
        parallelProbes: Boolean,
    ): SniProfileProbeResult = withContext(Dispatchers.IO) {
        val totalStarted = SystemClock.elapsedRealtime()
        val reservedPort = reservePort()
        try {
        val prepareStarted = SystemClock.elapsedRealtime()
        val base = settingsStore.snapshot().validated()
        val probeSettings = base.copy(
            socksAddress = "127.0.0.1",
            socksPort = reservedPort,
            socksUdp = false,
        )
        val outerConfigPrepareMs = SystemClock.elapsedRealtime() - prepareStarted
        var lastError: Throwable? = null

        for (edge in base.edges()) {
            currentCoroutineContext().ensureActive()
            val core = MciXrayCore(appContext)
            try {
                val startup = core.start(edge, probeSettings, profile)
                val samples = mutableListOf<ProbeSample>()
                var timeoutCount = 0
                var failureCount = 0

                if (parallelProbes) {
                    val attempts = coroutineScope {
                        List(probeCount) {
                            async(Dispatchers.IO) {
                                try {
                                    val sample = ProbeSession(
                                        probeSettings.socksAddress,
                                        probeSettings.socksPort,
                                        probeTimeoutMs,
                                    ).use(ProbeSession::probe)
                                    ProbeAttempt(sample = sample)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    ProbeAttempt(error = error)
                                }
                            }
                        }.awaitAll()
                    }
                    attempts.forEach { attempt ->
                        attempt.sample?.let(samples::add)
                        when (attempt.error) {
                            is SocketTimeoutException -> timeoutCount++
                            null -> Unit
                            else -> failureCount++
                        }
                    }
                } else {
                    ProbeSession(probeSettings.socksAddress, probeSettings.socksPort, probeTimeoutMs).use { session ->
                        repeat(probeCount) {
                            currentCoroutineContext().ensureActive()
                            try {
                                samples += session.probe()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: SocketTimeoutException) {
                                timeoutCount++
                                session.reset()
                            } catch (_: Throwable) {
                                failureCount++
                                session.reset()
                            }
                        }
                    }
                }

                check(samples.size >= minSuccessCount) {
                    "Delay test had ${samples.size}/$probeCount successful probes"
                }
                val rawProbeMs = samples.map(ProbeSample::httpProbeMs)
                val reportedLatencyMs = medianSuccessful(rawProbeMs)
                val totalTestMs = SystemClock.elapsedRealtime() - totalStarted
                val exit = if (resolveCountry) {
                    lookupExitCountryFast(
                        probeSettings.socksAddress,
                        probeSettings.socksPort,
                    )
                } else {
                    ExitLocation.UNKNOWN
                }
                val logLine = buildString {
                    append("Real delay config=${profile.name} edge=${edge.role}")
                    append(" configPrepareMs=${outerConfigPrepareMs + startup.configPrepareMs}")
                    append(" coreStartupMs=${startup.coreStartupMs}")
                    append(" proxyReadyMs=${startup.proxyReadyMs}")
                    append(" dnsMs=proxy")
                    append(" connectMs=${samples.map(ProbeSample::connectMs)}")
                    append(" tlsHandshakeMs=${samples.map(ProbeSample::tlsHandshakeMs)}")
                    append(" httpProbeMs=$rawProbeMs")
                    append(" headerWaitMs=${samples.map(ProbeSample::headerWaitMs)}")
                    append(" totalTestMs=$totalTestMs")
                    append(" reportedLatencyMs=$reportedLatencyMs")
                    append(" successCount=${samples.size}")
                    append(" timeoutCount=$timeoutCount")
                    append(" failureCount=$failureCount")
                    if (resolveCountry) {
                        append(" exitIp=${exit.ip.ifBlank { "-" }}")
                        append(" countryCode=${exit.country.countryCode ?: "XX"}")
                        append(" countrySource=${exit.source}")
                    }
                }
                AppLogRepository.info(LogSource.APP, logLine)
                Log.i(TAG, logLine)
                return@withContext SniProfileProbeResult(
                    latencyMs = reportedLatencyMs,
                    country = exit.country,
                    exitIp = exit.ip,
                    countrySource = exit.source,
                    candidateId = if (resolveCountry) "configs-ping" else "",
                    candidateLabel = if (resolveCountry) "Compatibility Scan" else "",
                    probeDetail = if (resolveCountry) {
                        "HTTPS ${samples.size}/$probeCount | edge=${edge.role} | country=${exit.country.countryCode ?: "unknown"}"
                    } else {
                        ""
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                AppLogRepository.warning(
                    LogSource.APP,
                    "Real delay edge=${edge.role} failed totalTestMs=${SystemClock.elapsedRealtime() - totalStarted}",
                    error,
                )
                Log.w(TAG, "Real delay edge=${edge.role} failed", error)
            } finally {
                try {
                    core.stop()
                } catch (_: Throwable) {
                    
                }
            }
        }
        throw lastError ?: IllegalStateException("Delay test failed")
        } finally {
            releasePort(reservedPort)
        }
    }

    private class ProbeSession(
        socksHost: String,
        socksPort: Int,
        private val timeoutMs: Int,
    ) : Closeable {
        private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        private var socket: SSLSocket? = null
        private var input: BufferedInputStream? = null
        private var output: BufferedOutputStream? = null

        fun probe(): ProbeSample {
            val probeStarted = SystemClock.elapsedRealtime()
            val connectionTiming = ensureConnected()
            val request = buildString {
                append("GET /generate_204?uac=${SystemClock.elapsedRealtimeNanos()} HTTP/1.1\r\n")
                append("Host: $PROBE_HOST\r\n")
                append("User-Agent: UAC-SNI-Spoofer-Android/0.1\r\n")
                append("Accept: */*\r\n")
                append("Connection: keep-alive\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)

            val headerStarted = SystemClock.elapsedRealtime()
            output!!.write(request)
            output!!.flush()
            val headers = readHeaders(input!!)
            val headersReceived = SystemClock.elapsedRealtime()
            val code = parseStatusCode(headers)
            check(code == 204) { "HTTP $code" }

            
            if (hasConnectionClose(headers)) reset()
            return ProbeSample(
                httpProbeMs = (headersReceived - probeStarted).coerceAtLeast(1L),
                connectMs = connectionTiming.connectMs,
                tlsHandshakeMs = connectionTiming.tlsHandshakeMs,
                headerWaitMs = (headersReceived - headerStarted).coerceAtLeast(1L),
            )
        }

        private fun ensureConnected(): ConnectionTiming {
            val current = socket
            if (current != null && current.isConnected && !current.isClosed) return ConnectionTiming.ZERO

            reset()
            val raw = Socket(proxy).apply {
                soTimeout = timeoutMs
                tcpNoDelay = true
                keepAlive = true
            }
            val connectStarted = SystemClock.elapsedRealtime()
            raw.connect(InetSocketAddress.createUnresolved(PROBE_HOST, PROBE_PORT), timeoutMs)
            val connectMs = SystemClock.elapsedRealtime() - connectStarted

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = (sslFactory.createSocket(raw, PROBE_HOST, PROBE_PORT, true) as SSLSocket).apply {
                useClientMode = true
                soTimeout = timeoutMs
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            val tlsStarted = SystemClock.elapsedRealtime()
            try {
                tls.startHandshake()
            } catch (error: Throwable) {
                runCatching { tls.close() }
                throw error
            }
            val tlsHandshakeMs = SystemClock.elapsedRealtime() - tlsStarted
            socket = tls
            input = BufferedInputStream(tls.inputStream)
            output = BufferedOutputStream(tls.outputStream)
            return ConnectionTiming(connectMs, tlsHandshakeMs)
        }

        fun reset() {
            runCatching { input?.close() }
            runCatching { output?.close() }
            runCatching { socket?.close() }
            input = null
            output = null
            socket = null
        }

        override fun close() = reset()
    }

    private fun reservePort(): Int {
        repeat(PORT_RESERVATION_ATTEMPTS) {
            val candidate = ServerSocket(0).use { it.localPort }
            val accepted = synchronized(reservedPortsLock) { reservedPorts.add(candidate) }
            if (accepted) return candidate
        }
        error("No local delay-test port available")
    }

    private fun releasePort(port: Int) {
        synchronized(reservedPortsLock) { reservedPorts.remove(port) }
    }

    
    private fun lookupExitCountry(socksHost: String, socksPort: Int, timeoutMs: Int): ExitLocation {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        runCatching { lookupIpWho(proxy, timeoutMs) }
            .getOrNull()?.takeIf { it.country.isKnown }?.let { return it }
        runCatching { lookupCountryIs(proxy, timeoutMs) }
            .getOrNull()?.takeIf { it.country.isKnown }?.let { return it }
        runCatching { lookupCloudflareTrace(proxy, timeoutMs) }
            .getOrNull()?.takeIf { it.country.isKnown }?.let { return it }
        return ExitLocation.UNKNOWN
    }

    private suspend fun lookupExitCountryFast(socksHost: String, socksPort: Int): ExitLocation = coroutineScope {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        val results = Channel<ExitLocation>(capacity = COUNTRY_LOOKUP_PROVIDERS)
        val lookups = listOf<() -> ExitLocation>(
            { lookupIpWho(proxy, COUNTRY_PROVIDER_TIMEOUT_MS) },
            { lookupCountryIs(proxy, COUNTRY_PROVIDER_TIMEOUT_MS) },
            { lookupCloudflareTrace(proxy, COUNTRY_PROVIDER_TIMEOUT_MS) },
        )
        val jobs = lookups.map { lookup ->
            launch(Dispatchers.IO) {
                results.send(runCatching(lookup).getOrDefault(ExitLocation.UNKNOWN))
            }
        }
        try {
            withTimeoutOrNull(COUNTRY_TOTAL_TIMEOUT_MS) {
                repeat(lookups.size) {
                    val result = results.receive()
                    if (result.country.isKnown) return@withTimeoutOrNull result
                }
                ExitLocation.UNKNOWN
            } ?: ExitLocation.UNKNOWN
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    private fun lookupIpWho(proxy: Proxy, timeoutMs: Int): ExitLocation {
            val json = JSONObject(fetchSmall("https://ipwho.is/?fields=success,ip,country_code,country", proxy, timeoutMs))
            if (json.optBoolean("success", true)) {
                val country = CountryMetadata.resolve(json.optString("country_code"), json.optString("country"))
                if (country.isKnown) return ExitLocation(json.optString("ip"), country, "ipwho.is")
            }
        return ExitLocation.UNKNOWN
    }

    private fun lookupCountryIs(proxy: Proxy, timeoutMs: Int): ExitLocation {
            val json = JSONObject(fetchSmall("https://api.country.is/", proxy, timeoutMs))
            val country = CountryMetadata.resolve(json.optString("country"), null)
            if (country.isKnown) return ExitLocation(json.optString("ip"), country, "api.country.is")
        return ExitLocation.UNKNOWN
    }

    private fun lookupCloudflareTrace(proxy: Proxy, timeoutMs: Int): ExitLocation {
            val values = fetchSmall("https://www.cloudflare.com/cdn-cgi/trace", proxy, timeoutMs)
                .lineSequence()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0].trim() to it[1].trim() }
            val country = CountryMetadata.resolve(values["loc"], null)
            if (country.isKnown) return ExitLocation(values["ip"].orEmpty(), country, "cloudflare-trace")
        return ExitLocation.UNKNOWN
    }

    private fun fetchSmall(url: String, proxy: Proxy, timeoutMs: Int): String {
        val connection = URL(url).openConnection(proxy) as HttpsURLConnection
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json,text/plain")
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/SNI-Maker")
            val code = connection.responseCode
            check(code in 200..299) { "Country HTTP $code" }
            connection.inputStream.buffered().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (output.size() < MAX_COUNTRY_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_COUNTRY_BYTES - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class ProbeSample(
        val httpProbeMs: Long,
        val connectMs: Long,
        val tlsHandshakeMs: Long,
        val headerWaitMs: Long,
    )

    private data class ProbeAttempt(
        val sample: ProbeSample? = null,
        val error: Throwable? = null,
    )

    private data class ConnectionTiming(
        val connectMs: Long,
        val tlsHandshakeMs: Long,
    ) {
        companion object {
            val ZERO = ConnectionTiming(0L, 0L)
        }
    }

    private data class ExitLocation(
        val ip: String,
        val country: CountryMetadata,
        val source: String,
    ) {
        companion object {
            val UNKNOWN = ExitLocation("", CountryMetadata.UNKNOWN, "unknown")
        }
    }

    companion object {
        private const val PROBE_HOST = "connectivitycheck.gstatic.com"
        private const val TAG = "UAC-RealDelay"
        private const val PROBE_PORT = 443
        private const val PROBE_TIMEOUT_MS = 2_000
        private const val PROBE_COUNT = 5
        private const val MIN_SUCCESS_COUNT = 2
        private const val MAKER_DEFAULT_TIMEOUT_MS = 20_000
        private const val MIN_MAKER_TIMEOUT_MS = 3_000
        private const val MAX_MAKER_TIMEOUT_MS = 30_000
        private const val MIN_ROUTE_BUDGET_MS = 1_000L
        private const val MAX_ROUTE_BUDGET_MS = 7_500L
        private const val SNI_MAKER_WARMUP_MS = 100L
        private const val ROUTE_SPEED_CANDIDATE_TIMEOUT_MS = 12_000L
        private const val ROUTE_SCREEN_BATCH_SIZE = 16
        private const val ROUTE_SCREEN_PARALLEL_PROBES = 4
        private const val EDGE_XRAY_VALIDATION_WORKERS = 2
        private const val CONNECT_RESCUE_SCREEN_LIMIT = 12
        private const val ISOLATED_QUALIFIER_FALLBACK = "isolated qualifier fallback:"
        private const val COUNTRY_TIMEOUT_MS = 3_500
        private const val COUNTRY_PROVIDER_TIMEOUT_MS = 2_200
        private const val COUNTRY_TOTAL_TIMEOUT_MS = 2_750L
        private const val COUNTRY_LOOKUP_PROVIDERS = 3
        private const val MAX_COUNTRY_BYTES = 64 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val PORT_RESERVATION_ATTEMPTS = 32
        private val reservedPortsLock = Any()
        private val reservedPorts = HashSet<Int>()

        internal fun medianSuccessful(values: List<Long>): Long {
            require(values.isNotEmpty()) { "No successful delay samples" }
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2L
            }
        }

        private fun readHeaders(input: BufferedInputStream): ByteArray {
            val output = ByteArrayOutputStream(512)
            var matched = 0
            while (output.size() < MAX_HEADER_BYTES) {
                val next = input.read()
                check(next >= 0) { "HTTP response ended before headers" }
                output.write(next)
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    next == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) return output.toByteArray()
            }
            error("HTTP response headers exceeded $MAX_HEADER_BYTES bytes")
        }

        private fun parseStatusCode(headers: ByteArray): Int {
            val firstLine = headers.toString(Charsets.US_ASCII).lineSequence().firstOrNull().orEmpty()
            val parts = firstLine.split(' ', limit = 3)
            check(parts.size >= 2 && parts[0].startsWith("HTTP/")) { "Invalid HTTP response" }
            return parts[1].toIntOrNull() ?: error("Invalid HTTP status")
        }

        internal fun hasConnectionClose(headers: ByteArray): Boolean =
            headers.toString(Charsets.US_ASCII)
                .lineSequence()
                .drop(1)
                .any { line ->
                    val split = line.split(':', limit = 2)
                    split.size == 2 &&
                        split[0].trim().equals("Connection", ignoreCase = true) &&
                        split[1].split(',').any { it.trim().equals("close", ignoreCase = true) }
                }
    }
}
