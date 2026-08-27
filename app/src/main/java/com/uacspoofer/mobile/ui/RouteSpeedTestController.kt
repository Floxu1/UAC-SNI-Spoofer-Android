package com.uacspoofer.mobile.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.LocalForwardProfile
import com.uacspoofer.mobile.profiles.ProfileLatencyTester
import com.uacspoofer.mobile.profiles.ProfileLibrary
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.RoutePreparationProgress
import com.uacspoofer.mobile.profiles.RoutePreparationStep
import com.uacspoofer.mobile.profiles.RouteSpeedProbeResult
import com.uacspoofer.mobile.profiles.RouteSpeedProbeStage
import com.uacspoofer.mobile.profiles.RouteSpeedQualifierEvent
import com.uacspoofer.mobile.profiles.RouteSpeedTestPlan
import com.uacspoofer.mobile.profiles.RouteTransferProbeConfig
import com.uacspoofer.mobile.vpn.AdaptiveCandidate
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import com.uacspoofer.mobile.vpn.AdaptiveSavedRoute
import com.uacspoofer.mobile.vpn.AdaptiveRouteMetrics
import com.uacspoofer.mobile.vpn.IpAddress
import com.uacspoofer.mobile.vpn.RouteProbeBusyException
import com.uacspoofer.mobile.vpn.RouteProbePermissionRequiredException

import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.vpn.AdaptiveCandidatePlanner
import com.uacspoofer.mobile.vpn.AdaptiveProfileStore
import com.uacspoofer.mobile.vpn.NetworkFingerprint
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.random.Random

internal enum class RouteSpeedStatus { QUEUED, STARTING, TESTING, PASSED, FAILED, STOPPED }

internal class RouteQualifierCompletionTracker(initialCandidateIds: Iterable<String> = emptyList()) {
    private val completedCandidateIds = LinkedHashSet<String>().apply { addAll(initialCandidateIds) }

    val count: Int
        get() = completedCandidateIds.size

    fun claim(candidateIds: Iterable<String>): Set<String> = buildSet {
        candidateIds.forEach { candidateId ->
            if (completedCandidateIds.add(candidateId)) add(candidateId)
        }
    }
}

internal enum class RouteTournamentStage(
    val title: String,
    val subtitle: String,
    val shortlistSize: Int,
    val samplesPerCandidate: Int,
    val workers: Int,
) {
    QUALIFIER("Qualifying", "Fast batched HTTP preflight; isolated verification follows", Int.MAX_VALUE, 1, 3),
    VERIFICATION("Resolver verification", "96 Edge, tuning and DNS families are checked in isolation", 96, 1, 1),
    MTU_VALIDATION("MTU validation", "Four real MTUs are opened for the best 24 route families", 96, 1, 1),
    STABILITY("Stability", "24 diverse routes get repeated stability samples", 24, 2, 1),
    STRESS("Stress test", "6 finalists face repeated cold-start tests", 6, 3, 1),
    CHAMPIONSHIP("ABBA final", "Champion and backup are compared A-B-B-A", 2, 2, 1),
    COMPLETE("Complete", "Champion and backup are ready", 0, 0, 0),
}

internal data class RouteObservation(
    val stage: RouteTournamentStage,
    val accepted: Boolean,
    val score: Int,
    val latencyMs: Long?,
    val dnsLatencyMs: Long?,
    val payloadBytes: Int,
    val throughputKbps: Long,
    val httpSucceeded: Int,
    val httpAttempted: Int,
    val dnsSucceeded: Boolean,
    val detail: String,
    val failureFingerprint: String,
    val uploadBytes: Int = 0,
    val downloadBytes: Int = payloadBytes,
    val uploadKbps: Long = 0L,
    val downloadKbps: Long = throughputKbps,
    val jitterMs: Long? = null,
    val transferValidated: Boolean = false,
    val endpointFailure: Boolean = false,
    val txDelta: Long = 0L,
    val rxDelta: Long = 0L,
    val mtuValidated: Boolean = false,
)

internal data class RouteSpeedRow(
    val candidateId: String,
    val label: String,
    val route: String,
    val edgeKey: String,
    val resolverKey: String,
    val fragmentKey: String,
    val mtu: Int,
    val status: RouteSpeedStatus = RouteSpeedStatus.QUEUED,
    val stageReached: RouteTournamentStage = RouteTournamentStage.QUALIFIER,
    val score: Int = 0,
    val tournamentScore: Int = 0,
    val confidence: Int = 0,
    val latencyMs: Long? = null,
    val p95LatencyMs: Long? = null,
    val jitterMs: Long? = null,
    val dnsLatencyMs: Long? = null,
    val payloadBytes: Int = 0,
    val throughputKbps: Long = 0L,
    val httpSucceeded: Int = 0,
    val httpAttempted: Int = 0,
    val dnsSucceeded: Boolean = false,
    val dnsSuccessCount: Int = 0,
    val successfulSamples: Int = 0,
    val observations: List<RouteObservation> = emptyList(),
    val failureFingerprint: String = "Not tested",
    val detail: String = "Waiting to test",
    val uploadBytes: Int = 0,
    val downloadBytes: Int = 0,
    val uploadKbps: Long = 0L,
    val downloadKbps: Long = 0L,
    val transferSuccessCount: Int = 0,
    val endpointFailureCount: Int = 0,
    val txDelta: Long = 0L,
    val rxDelta: Long = 0L,
    val nativeSampleCount: Int = 0,
    val mtuValidated: Boolean = false,
) {
    val sampleCount: Int get() = observations.size
    val usable: Boolean get() = successfulSamples > 0
}

internal fun recommendationRowsForStage(
    rows: List<RouteSpeedRow>,
    currentStage: RouteTournamentStage,
    stageCandidateIds: (RouteTournamentStage) -> Collection<String>?,
): List<RouteSpeedRow> {
    val stages = if (currentStage == RouteTournamentStage.COMPLETE) {
        listOf(RouteTournamentStage.CHAMPIONSHIP)
    } else {
        RouteTournamentStage.entries
            .asSequence()
            .filter { it != RouteTournamentStage.COMPLETE && it.ordinal <= currentStage.ordinal }
            .sortedByDescending { it.ordinal }
            .toList()
    }
    stages.forEach { stage ->
        val ids = stageCandidateIds(stage)?.toHashSet()
        val accepted = rows.filter { row ->
            (ids == null || row.candidateId in ids) &&
                row.observations.any { observation -> observation.stage == stage && observation.accepted }
        }
        if (accepted.isNotEmpty()) return accepted
    }
    return emptyList()
}

internal fun preferredBackupRow(
    champion: RouteSpeedRow,
    rankedRows: List<RouteSpeedRow>,
): RouteSpeedRow? {
    val championSubnet = routeEndpointSubnet(champion.edgeKey)
    fun diversityTier(row: RouteSpeedRow): Int {
        val subnet = routeEndpointSubnet(row.edgeKey)
        return when {
            row.edgeKey != champion.edgeKey && championSubnet != null && subnet != null && subnet != championSubnet -> 0
            row.edgeKey != champion.edgeKey -> 1
            else -> 2
        }
    }
    val candidates = rankedRows.filter { it.candidateId != champion.candidateId }
    val preferredTier = candidates.minOfOrNull { diversityTier(it) } ?: return null
    return candidates.firstOrNull { diversityTier(it) == preferredTier }
}

internal fun filterRestorableFinalRows(
    rows: List<RouteSpeedRow>,
    validCandidateIds: Collection<String>,
): List<RouteSpeedRow> {
    if (rows.isEmpty() || validCandidateIds.isEmpty()) return emptyList()
    val valid = validCandidateIds.toHashSet()
    return rows.filter { it.candidateId in valid }
}

private fun routeEndpointSubnet(endpoint: String): String? {
    val address = if (endpoint.startsWith('[')) {
        endpoint.substringAfter('[').substringBefore(']')
    } else {
        endpoint.substringBeforeLast(':', endpoint)
    }
    return IpAddress.parse(address)?.subnetKey()
}

internal data class SavedRouteDetails(
    val id: String,
    val label: String,
    val edge: String,
    val role: String,
    val resolver: String,
    val fragment: String,
    val mtu: Int,
    val directCompat: Boolean,
)

internal data class SavedRouteProfileDetails(
    val profileName: String,
    val profileId: String,
    val profileType: String,
    val protocol: String,
    val server: String,
    val transport: String,
    val security: String,
    val sni: String,
    val host: String,
    val path: String,
    val alpn: String,
    val fingerprint: String,
    val networkTransport: String,
    val carrier: String,
    val carrierClass: String,
    val provider: String,
    val asn: String,
    val networkFingerprint: String,
    val networkMtu: Int,
    val metered: Boolean,
    val validated: Boolean,
    val ipSupport: String,
    val champion: SavedRouteDetails?,
    val backup: SavedRouteDetails?,
)

internal class RouteSpeedTestController private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val tester = ProfileLatencyTester(appContext)
    private val profileStore = ProfileStore(appContext)

    private val settingsStore = AdvancedSettingsStore(appContext)
    private val adaptiveProfileStore = AdaptiveProfileStore(appContext)
    private val adaptivePlanner = AdaptiveCandidatePlanner(adaptiveProfileStore)
    private val fingerprintResolver = NetworkFingerprintResolver(appContext)

    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var plan: RouteSpeedTestPlan? = null
    private var activeRouteNetwork: NetworkFingerprint? = null
    private var loadJob: Job? = null
    private var loadLastRouteJob: Job? = null
    private var savedRouteLoadJob: Job? = null
    private var testJob: Job? = null
    private var stageJob: Job? = null

    private var pauseRequested = false
    private var manualAdvanceRequested = false
    private var manualAdvanceCandidateIds: List<String>? = null
    private var forcedPauseReason: String? = null

    val rows = mutableStateListOf<RouteSpeedRow>()
    private val finalStageRows = mutableStateListOf<RouteSpeedRow>()
    var profileLibrary by mutableStateOf(selectedTestProfileLibrary(profileStore.snapshot()))
        private set
    var loading by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var profileName by mutableStateOf("-")
        private set
    var networkLabel by mutableStateOf("Detecting network…")
        private set
    var notice by mutableStateOf("Preparing the Route Tournament…")
        private set
    var preparationProgress by mutableStateOf(
        RoutePreparationProgress(
            step = RoutePreparationStep.PROFILE_SNAPSHOT,
            detail = "Waiting to prepare the route list",
        ),
    )
        private set
    var activeCandidateId by mutableStateOf<String?>(null)
        private set
    var activeCount by mutableStateOf(0)
        private set
    var currentStage by mutableStateOf(RouteTournamentStage.QUALIFIER)
        private set
    var phaseCompletedCount by mutableStateOf(0)
        private set
    var phaseTotalCount by mutableStateOf(0)
        private set
    var recommendedCandidateId by mutableStateOf<String?>(null)
        private set
    var backupCandidateId by mutableStateOf<String?>(null)
        private set
    var championConfidence by mutableStateOf(0)
        private set
    var selectedCandidateId by mutableStateOf<String?>(null)
        private set
    var savedRouteProfileName by mutableStateOf<String?>(null)
        private set
    var savedRouteProfileId by mutableStateOf<String?>(null)
        private set
    var savedChampionLabel by mutableStateOf<String?>(null)
        private set
    var savedBackupLabel by mutableStateOf<String?>(null)
        private set
    var savedRouteDetails by mutableStateOf<SavedRouteProfileDetails?>(null)
        private set
    var finalStageHistoryAvailable by mutableStateOf(false)
        private set
    var viewingFinalStageHistory by mutableStateOf(false)
        private set
    var finalStageHistoryTitle by mutableStateOf("Last Championship")
        private set

    val completedCount: Int
        get() = rows.count { it.sampleCount > 0 }

    val healthyCount: Int
        get() = rows.count(RouteSpeedRow::usable)

    val advanceReadyCount: Int
        get() = promotionRowsForNextStage().size

    val canAdvanceNow: Boolean
        get() = !loading &&
            currentStage != RouteTournamentStage.COMPLETE &&
            advanceReadyCount > 0 &&
            (testing || paused)

    val hasPreparedPlan: Boolean
        get() = plan?.candidates?.isNotEmpty() == true

    val canStartTest: Boolean
        get() = !loading && !testing && profileLibrary.allProfiles.isNotEmpty()

    val canLoadLastRouteList: Boolean
        get() {
            if (finalStageHistoryAvailable) return true
            val prepared = plan
            if (prepared != null) {
                if (hasFinalStageSnapshot(prepared)) return true
                if (prepared.savedChampion != null) return true
                if (hasSavedRouteInStore(prepared)) return true
            }
            return savedRouteDetails != null && savedRouteProfileId == profileLibrary.selectedId
        }

    private fun hasSavedRouteInStore(prepared: RouteSpeedTestPlan): Boolean =
        adaptiveProfileStore.savedRoute(
            prepared.session.network,
            prepared.profile,
            prepared.signature,
        ) != null
    private fun loadSavedRouteBeforeStart(profile: ProxyProfile) {
        savedRouteLoadJob?.cancel()

        val requestedProfileId = profile.id

        savedRouteLoadJob = scope.launch {
            val settings = settingsStore.snapshot().validated()

            val network = try {
                fingerprintResolver.captureAdaptive()
            } catch (_: Throwable) {
                return@launch
            }

            if (profileLibrary.selectedId != requestedProfileId) {
                return@launch
            }

            val signature = adaptivePlanner.signature(
                settings = settings,
                profile = profile,
            )

            val champion = adaptiveProfileStore.savedRoute(
                network = network,
                profile = profile,
                signature = signature,
            )

            val backup = adaptiveProfileStore.savedBackupRoute(
                network = network,
                profile = profile,
                signature = signature,
            )

            if (profileLibrary.selectedId != requestedProfileId) {
                return@launch
            }

            if (champion == null) {
                savedRouteProfileId = null
                savedRouteProfileName = null
                savedChampionLabel = null
                savedBackupLabel = null
                savedRouteDetails = null
                return@launch
            }

            savedRouteProfileId = profile.id
            savedRouteProfileName = profile.name
            savedChampionLabel = champion.label
            savedBackupLabel = backup?.label

            savedRouteDetails = buildSavedRouteDetails(
                profile = profile,
                settings = settings,
                network = network,
                champion = champion.toSavedRouteDetails(),
                backup = backup?.toSavedRouteDetails(),
            )
            withContext(Dispatchers.Main.immediate) {
                finalStageHistoryAvailable = true
            }
        }
    }
    fun loadProfileLibrary() {
        if (testing || loading) return

        val snapshot = profileStore.snapshot()
        val currentId = resolveRouteSpeedTestProfileId(
            selectedId = snapshot.selectedId,
            profileIds = snapshot.allProfiles.map(ProxyProfile::id),
            connected = ConnectionStateStore.state.value == ConnectionState.CONNECTED,
            activeProfileId = profileStore.activeProfile()?.id,
        )
        preferences.edit().putString(KEY_TEST_PROFILE_ID, currentId).apply()
        val latest = snapshot.copy(selectedId = currentId)
        profileLibrary = latest

        val prepared = plan

        if (prepared == null || prepared.profile.id != latest.selectedId) {
            resetForProfileSelection(
                latest.selectedProfile,
                clearRunRequest = false,
            )
        }

        loadSavedRouteBeforeStart(latest.selectedProfile)
    }

    fun selectTestProfile(profileId: String) {
        if (testing || loading || profileId == profileLibrary.selectedId) return
        val profile = profileLibrary.allProfiles.firstOrNull { it.id == profileId } ?: return
        preferences.edit().putString(KEY_TEST_PROFILE_ID, profileId).apply()
        profileLibrary = profileLibrary.copy(selectedId = profileId)
        resetForProfileSelection(profile, clearRunRequest = true)
        loadSavedRouteBeforeStart(profile)
        notice = "${profile.name} selected • tap START when you are ready"
    }

    fun load(force: Boolean = false) {
        if (force || plan == null) loadProfileLibrary()
    }

    fun refresh() {
        if (testing) {
            notice = "Pause the Tournament before reloading the profile and network"
            return
        }
        if (plan == null) {
            loadProfileLibrary()
            notice = "Profiles refreshed • tap START when you are ready"
            return
        }
        viewingFinalStageHistory = false
        finalStageRows.clear()
        notice = "Reloading the current configuration and network…"
        preparePlan(resumeIfRequested = false)
    }

    fun startTest() {
        val prepared = plan ?: return preparePlan(
            resumeIfRequested = false,
            startAfterPreparation = true,
        )
        if (testing || loading || prepared.candidates.isEmpty()) return
        beginRun(prepared, reset = true)
    }

    fun resumeTest() {
        val prepared = plan ?: return preparePlan(resumeIfRequested = true)
        if (testing || loading || prepared.candidates.isEmpty()) return
        beginRun(prepared, reset = false)
    }

    fun pauseTest() {
        pauseRequested = true
        preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
        if (testing) {
            testJob?.cancel()
        } else {
            paused = currentStage != RouteTournamentStage.COMPLETE
            if (paused) notice = "Route Tournament paused • use the current best route or resume"
            stopKeepAliveService()
        }
    }

    fun advanceStageNow() {
        val prepared = plan ?: return
        if (!canAdvanceNow || manualAdvanceRequested) return
        val promoted = manualAdvanceShortlist(currentStage)
        if (promoted.isEmpty()) {
            notice = "No fully healthy result is ready in ${currentStage.title} yet"
            return
        }
        manualAdvanceRequested = true
        manualAdvanceCandidateIds = promoted
        notice = "Advancing ${promoted.size} healthy routes from ${currentStage.title} now…"
        if (testing) {
            stageJob?.cancel(CancellationException("Manual stage advance"))
        } else {
            applyPausedManualAdvance(prepared, promoted)
        }
    }

    fun resumePersistedIfRequested() {
        if (testing || loading || !preferences.getBoolean(KEY_RUN_REQUESTED, false)) return
        preparePlan(resumeIfRequested = true)
    }

    fun selectRoute(candidateId: String) {
        val row = visibleRows().firstOrNull { it.candidateId == candidateId } ?: return
        if (!row.usable) return
        val visibleUsable = visibleRows().filter(RouteSpeedRow::usable)
        val backupRow = preferredBackupRow(
            champion = row,
            rankedRows = visibleUsable,
        )
        val context = routeSelectionContext() ?: return
        val settings = settingsStore.snapshot().validated()
        val championCandidate = resolveCandidateForSelection(context, row, settings) ?: return
        val backupCandidate = backupRow?.let { resolveCandidateForSelection(context, it, settings) }
        tester.persistRouteSelection(
            network = context.network,
            profile = context.profile,
            signature = context.signature,
            champion = championCandidate,
            score = row.score,
            metrics = row.toAdaptiveRouteMetrics(),
            backupCandidate = backupCandidate,
            backupScore = backupRow?.score ?: 0,
            backupMetrics = backupRow?.toAdaptiveRouteMetrics() ?: AdaptiveRouteMetrics(0),
        )
        val prepared = plan
        if (prepared != null) {
            preferences.edit().putString(KEY_TEST_PROFILE_ID, prepared.profile.id).apply()
            profileLibrary = runCatching { selectedTestProfileLibrary(profileStore.select(prepared.profile.id)) }
                .getOrDefault(profileLibrary.copy(selectedId = prepared.profile.id))
        }
        selectedCandidateId = candidateId
        backupCandidateId = backupRow?.candidateId
        recommendedCandidateId = candidateId
        savedRouteProfileId = context.profile.id
        savedRouteProfileName = context.profile.name
        savedChampionLabel = row.label
        savedBackupLabel = backupRow?.label
        savedRouteDetails = buildSavedRouteDetails(
            context.profile,
            settings,
            context.network,
            row.toSavedRouteDetails(),
            backupRow?.toSavedRouteDetails(),
        )
        if (prepared != null) {
            persistSavedRouteListSnapshot()
        }
        notice = if (backupRow == null) {
            "${row.label} saved for this network and configuration"
        } else {
            "Champion and backup saved for automatic recovery on this network"
        }
    }

    fun loadLastFinalStageList() {
        if (testing) {
            notice = "Pause the Tournament before loading the last saved route list"
            return
        }
        loadLastRouteJob?.cancel()
        loadLastRouteJob = scope.launch {
            try {
                val profile = profileLibrary.selectedProfile
                val settings = settingsStore.snapshot().validated()
                val network = withContext(Dispatchers.IO) { fingerprintResolver.captureAdaptive() }
                val signature = adaptivePlanner.signature(settings, profile)
                val ranked = when (val prepared = plan) {
                    null -> buildLoadableRouteListLight(profile, network, signature)
                    else -> buildLoadableRouteList(prepared)
                }.sortedWith(routeRankingComparator())
                if (ranked.isEmpty()) {
                    notice = "No saved route list is available yet"
                    return@launch
                }
                finalStageRows.clear()
                finalStageRows.addAll(ranked)
                activeRouteNetwork = network
                applyLoadedSavedRouteMarkers(profile, network, signature, ranked)
                viewingFinalStageHistory = true
                networkLabel = buildNetworkLabelFrom(network)
                notice = "Loaded ${ranked.size} routes ranked by ping, speed and overall score"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notice = "Could not load saved routes: ${error.shortMessage()}"
            }
        }
    }

    fun showLiveRanking() {
        viewingFinalStageHistory = false
        notice = if (testing) {
            "Live ${currentStage.title} ranking restored"
        } else {
            "Live Tournament ranking restored"
        }
    }

    fun visibleRows(): List<RouteSpeedRow> {
        val source = if (viewingFinalStageHistory) finalStageRows else rows
        if (viewingFinalStageHistory) {
            return source.sortedWith(routeRankingComparator())
        }
        return source.sortedWith(
        compareBy<RouteSpeedRow> {
            when {
                it.candidateId == recommendedCandidateId -> 0
                it.candidateId == backupCandidateId -> 1
                it.usable -> 2
                it.status == RouteSpeedStatus.TESTING || it.status == RouteSpeedStatus.STARTING -> 3
                it.status == RouteSpeedStatus.QUEUED -> 4
                else -> 5
            }
        }.thenByDescending { it.tournamentScore }
            .thenByDescending { it.confidence }
            .thenByDescending { it.throughputKbps }
            .thenBy { it.p95LatencyMs ?: Long.MAX_VALUE },
    )
    }

    private fun preparePlan(
        resumeIfRequested: Boolean,
        startAfterPreparation: Boolean = false,
    ) {
        val selectedProfile = profileLibrary.selectedProfile.copy()
        loadJob?.cancel()
        viewingFinalStageHistory = false
        finalStageRows.clear()
        finalStageHistoryAvailable = false
        loadJob = scope.launch {
            loading = true
            notice = "Detecting network and restoring the Route Tournament…"
            preparationProgress = RoutePreparationProgress(
                step = RoutePreparationStep.PROFILE_SNAPSHOT,
                detail = "Starting route preparation",
            )
            try {
                val prepared = tester.prepareRouteSpeedTest(
                    profileOverride = selectedProfile,
                    onProgress = { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            applyPreparationProgress(progress)
                        }
                    },
                    onSavedRouteResolved = { champion, backup ->
                        withContext(Dispatchers.Main.immediate) {
                            savedRouteProfileId = champion?.let { selectedProfile.id }
                            savedRouteProfileName = champion?.let { selectedProfile.name }
                            savedChampionLabel = champion?.label
                            savedBackupLabel = backup?.label
                        }
                    },
                )
                plan = prepared
                applyPreparedPlanState(prepared, clearHistory = true)
                notice = when {
                    currentStage == RouteTournamentStage.COMPLETE ->
                        "Tournament complete • Champion and backup are ready"
                    paused ->
                        "${currentStage.title} restored • tap RESUME to continue"
                    rows.isNotEmpty() ->
                        "${prepared.discoverySummary} • ${rows.size} route genomes ready"
                    else -> "No routes available"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                plan = null
                rows.clear()
                paused = false
                selectedCandidateId = null
                savedRouteProfileName = null
                savedChampionLabel = null
                savedBackupLabel = null
                savedRouteDetails = null
                savedRouteProfileId = null
                finalStageHistoryAvailable = false
                viewingFinalStageHistory = false
                finalStageRows.clear()
                notice = "Could not prepare routes: ${error.shortMessage()}"
                networkLabel = "Network unavailable"
            } finally {
                loading = false
            }
            when {
                startAfterPreparation -> plan?.let { beginRun(it, reset = true) }
                resumeIfRequested && preferences.getBoolean(KEY_RUN_REQUESTED, false) ->
                    plan?.let { beginRun(it, reset = false) }
            }
        }
    }

    private fun resetForProfileSelection(
        profile: ProxyProfile,
        clearRunRequest: Boolean,
    ) {
        loadJob?.cancel()

        val keepSavedRoute =
            savedRouteProfileId == profile.id ||
                savedRouteDetails?.profileId == profile.id

        plan = null
        rows.clear()
        finalStageRows.clear()
        viewingFinalStageHistory = false
        finalStageHistoryAvailable = false
        paused = false
        currentStage = RouteTournamentStage.QUALIFIER
        phaseCompletedCount = 0
        phaseTotalCount = 0
        activeCandidateId = null
        activeCount = 0
        recommendedCandidateId = null
        backupCandidateId = null
        selectedCandidateId = null

        if (!keepSavedRoute) {
            savedRouteProfileName = null
            savedChampionLabel = null
            savedBackupLabel = null
            savedRouteDetails = null
            savedRouteProfileId = null
        }

        profileName = profile.name
        networkLabel = "Tap Start to detect the network"

        preparationProgress = RoutePreparationProgress(
            step = RoutePreparationStep.PROFILE_SNAPSHOT,
            detail = "Ready when you are",
        )

        notice = "Choose a profile, then tap START"

        if (clearRunRequest) {
            preferences.edit()
                .putBoolean(KEY_RUN_REQUESTED, false)
                .apply()
        }
    }

    private fun selectedTestProfileLibrary(library: ProfileLibrary): ProfileLibrary {
        val storedId = preferences.getString(KEY_TEST_PROFILE_ID, null)
        val selectedId = storedId
            ?.takeIf { id -> library.allProfiles.any { it.id == id } }
            ?: library.selectedId
        return library.copy(selectedId = selectedId)
    }

    private fun applyPreparationProgress(progress: RoutePreparationProgress) {
        val current = preparationProgress
        if (progress.step.number < current.step.number) return
        if (progress.step == current.step && progress.completed < current.completed) return
        preparationProgress = progress
        if (progress.step == RoutePreparationStep.PROFILE_SNAPSHOT && progress.currentTarget.isNotBlank()) {
            profileName = progress.currentTarget
        }
        if (
            progress.step == RoutePreparationStep.NETWORK_DETECTION &&
            progress.completed > 0 &&
            progress.currentTarget.isNotBlank()
        ) {
            networkLabel = progress.currentTarget.replaceFirstChar(Char::uppercase)
        }
        notice = progress.detail
    }

    private fun beginRun(prepared: RouteSpeedTestPlan, reset: Boolean) {
        if (testJob?.isActive == true) return
        viewingFinalStageHistory = false
        finalStageRows.clear()
        if (reset) {
            clearPersistedRows()
            rows.clear()
            rows.addAll(prepared.candidates.map(::initialRow))
            createSession(prepared)
            currentStage = RouteTournamentStage.QUALIFIER
            recommendedCandidateId = null
            backupCandidateId = null
            championConfidence = 0
        } else {
            currentStage = restoreCurrentStage()
            rows.indices.forEach { index ->
                if (rows[index].status in setOf(
                        RouteSpeedStatus.STARTING,
                        RouteSpeedStatus.TESTING,
                        RouteSpeedStatus.STOPPED,
                    )
                ) {
                    val acceptedInCurrentStage = rows[index].observations.any { observation ->
                        observation.stage == currentStage && observation.accepted
                    }
                    rows[index] = rows[index].copy(
                        status = if (acceptedInCurrentStage) RouteSpeedStatus.PASSED else RouteSpeedStatus.QUEUED,
                        detail = if (acceptedInCurrentStage) rows[index].detail else "Waiting to resume",
                    )
                }
            }
        }
        if (currentStage == RouteTournamentStage.COMPLETE) {
            paused = false
            preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
            notice = "Route Tournament is already complete"
            stopKeepAliveService()
            return
        }
        pauseRequested = false
        forcedPauseReason = null
        manualAdvanceRequested = false
        manualAdvanceCandidateIds = null
        paused = false
        testing = true
        preferences.edit().putBoolean(KEY_RUN_REQUESTED, true).apply()
        startKeepAliveService()
        testJob = scope.launch {
            try {
                runTournament(prepared)
                preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
                paused = false
                finishTournament()
            } catch (cancelled: CancellationException) {
                rows.indices.forEach { index ->
                    if (rows[index].status == RouteSpeedStatus.STARTING || rows[index].status == RouteSpeedStatus.TESTING) {
                        rows[index] = rows[index].copy(
                            status = RouteSpeedStatus.STOPPED,
                            detail = "Paused before this sample completed",
                        )
                    }
                }
                persistAllRows()
                updateRecommendedCandidates()
                paused = currentStage != RouteTournamentStage.COMPLETE
                notice = forcedPauseReason ?: if (recommendedCandidateId == null) {
                    "Route Tournament paused • tap RESUME to continue"
                } else {
                    "Tournament paused • current Champion can be used now"
                }
                if (!pauseRequested) preferences.edit().putBoolean(KEY_RUN_REQUESTED, true).apply()
            } catch (error: Throwable) {
                rows.indices.forEach { index ->
                    if (rows[index].status == RouteSpeedStatus.STARTING || rows[index].status == RouteSpeedStatus.TESTING) {
                        rows[index] = rows[index].copy(
                            status = RouteSpeedStatus.STOPPED,
                            detail = "Interrupted by an unexpected test error",
                        )
                    }
                }
                persistAllRows()
                updateRecommendedCandidates()
                paused = currentStage != RouteTournamentStage.COMPLETE
                preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
                notice = "Route Tournament paused after an unexpected error • tap RESUME to retry"
                AppLogRepository.error(
                    LogSource.APP,
                    "Route Tournament failed stage=${currentStage.name}",
                    error,
                )
            } finally {
                activeCandidateId = null
                activeCount = 0
                testing = false
                testJob = null
                if (!preferences.getBoolean(KEY_RUN_REQUESTED, false)) stopKeepAliveService()
            }
        }
    }

    private suspend fun runTournament(prepared: RouteSpeedTestPlan) {
        var stage = currentStage
        while (stage != RouteTournamentStage.COMPLETE) {
            currentCoroutineContext().ensureActive()
            currentStage = stage
            val candidateIds = stageCandidateIds(stage)
            if (candidateIds.isEmpty()) break
            persistStage(stage, candidateIds)
            runStage(prepared, stage, candidateIds)
            val nextStage = stage.next()
            val manuallyPromoted = manualAdvanceCandidateIds
            if (manualAdvanceRequested && manuallyPromoted != null) {
                if (nextStage != RouteTournamentStage.COMPLETE) {
                    persistStage(nextStage, manuallyPromoted)
                }
                manualAdvanceRequested = false
                manualAdvanceCandidateIds = null
            }
            stage = nextStage
            currentStage = stage
            preferences.edit().putString(KEY_STAGE, stage.name).apply()
        }
    }

    private suspend fun runStage(
        prepared: RouteSpeedTestPlan,
        stage: RouteTournamentStage,
        candidateIds: List<String>,
    ) {
        val schedule = buildSchedule(prepared, stage, candidateIds)
        val remaining = remainingSchedule(stage, schedule)
        phaseTotalCount = schedule.size
        phaseCompletedCount = schedule.size - remaining.size
        notice = "${stage.title} • ${remaining.size} samples left • ${stage.workers} workers"
        if (remaining.isEmpty()) return

        try {
            if (stage == RouteTournamentStage.QUALIFIER) {
                val phaseCandidateIds = candidateIds.toHashSet()
                val completionTracker = RouteQualifierCompletionTracker(
                    rows.asSequence()
                        .filter { it.candidateId in phaseCandidateIds }
                        .filter { row -> row.observations.any { it.stage == stage } }
                        .map(RouteSpeedRow::candidateId)
                        .asIterable(),
                )
                phaseCompletedCount = completionTracker.count.coerceAtMost(phaseTotalCount)
                coroutineScope {
                    stageJob = currentCoroutineContext()[Job]
                    val candidates = remaining.mapNotNull { candidateId ->
                        prepared.candidates.firstOrNull { it.id == candidateId }
                    }
                    tester.measureRouteSpeedQualifier(prepared, candidates) { event ->
                        withContext(Dispatchers.Main.immediate) {
                            when (event) {
                                is RouteSpeedQualifierEvent.Running -> {
                                    val logicalIds = event.candidateIds.filter { it in phaseCandidateIds }
                                    activeCandidateId = logicalIds.firstOrNull()
                                    logicalIds.forEach { candidateId ->
                                        updateRow(candidateId) { current ->
                                            current.copy(
                                                status = when (event.stage) {
                                                    RouteSpeedProbeStage.STARTING -> RouteSpeedStatus.STARTING
                                                    RouteSpeedProbeStage.PROBING -> RouteSpeedStatus.TESTING
                                                },
                                                detail = when (event.stage) {
                                                    RouteSpeedProbeStage.STARTING ->
                                                        "${stage.title}: starting fast route batch"
                                                    RouteSpeedProbeStage.PROBING ->
                                                        "${stage.title}: fast HTTP preflight"
                                                },
                                            )
                                        }
                                    }
                                }

                                is RouteSpeedQualifierEvent.Completed -> {
                                    val newlyCompleted = completionTracker.claim(
                                        event.results.asSequence()
                                            .map { it.candidate.id }
                                            .filter { it in phaseCandidateIds }
                                            .asIterable(),
                                    )
                                    if (newlyCompleted.isNotEmpty()) {
                                        event.results
                                            .filter { it.candidate.id in newlyCompleted }
                                            .forEach { result ->
                                                updateRow(result.candidate.id) {
                                                    aggregateResult(it, result, stage)
                                                }
                                            }
                                        persistRows(newlyCompleted)
                                        phaseCompletedCount = completionTracker.count.coerceAtMost(phaseTotalCount)
                                        updateRecommendedCandidates()
                                        notice =
                                            "${stage.title} • $phaseCompletedCount/$phaseTotalCount • $healthyCount healthy"
                                    }
                                    if (activeCandidateId in event.results.map { it.candidate.id }.toSet()) {
                                        activeCandidateId = null
                                    }
                                }
                            }
                        }
                    }
                }
                activeCandidateId = null
                return
            }
            coroutineScope {
                stageJob = currentCoroutineContext()[Job]
                val queue = Channel<String>(Channel.UNLIMITED)
                remaining.forEach(queue::trySend)
                queue.close()
                List(stage.workers) {
                    launch {
                        for (candidateId in queue) {
                        currentCoroutineContext().ensureActive()
                        val candidate = prepared.candidates.firstOrNull { it.id == candidateId } ?: continue
                        activeCandidateId = candidate.id
                        activeCount++
                        updateRow(candidate.id) {
                            it.copy(
                                status = RouteSpeedStatus.STARTING,
                                stageReached = stage,
                                detail = "${stage.title}: starting a fresh Xray route",
                            )
                        }
                        try {
                            val stageTransferConfig = checkNotNull(transferConfigFor(stage))
                            val result = try {
                                if (stage == RouteTournamentStage.MTU_VALIDATION) {
                                    tester.measureRouteSpeedNativeCandidate(
                                        plan = prepared,
                                        candidate = candidate,
                                        transferConfig = stageTransferConfig,
                                    ) { probeStage -> updateProbeStage(candidate.id, stage, probeStage) }
                                } else {
                                    tester.measureRouteSpeedCandidate(
                                        plan = prepared,
                                        candidate = candidate,
                                        transferConfig = stageTransferConfig,
                                    ) { probeStage -> updateProbeStage(candidate.id, stage, probeStage) }
                                }
                            } catch (permission: RouteProbePermissionRequiredException) {
                                forcedPauseReason = "Native MTU validation paused • connect once to grant VPN permission, then resume"
                                pauseRequested = true
                                preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
                                throw CancellationException(forcedPauseReason).also { it.initCause(permission) }
                            } catch (busy: RouteProbeBusyException) {
                                forcedPauseReason = "Native MTU validation paused • disconnect the active VPN, then resume"
                                pauseRequested = true
                                preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
                                throw CancellationException(forcedPauseReason).also { it.initCause(busy) }
                            }
                            updateRow(candidate.id) { aggregateResult(it, result, stage) }
                            persistRow(rows.first { it.candidateId == candidate.id })
                            phaseCompletedCount++
                            updateRecommendedCandidates()
                            notice = "${stage.title} • $phaseCompletedCount/$phaseTotalCount • $healthyCount healthy"
                        } finally {
                            activeCount--
                        }
                        }
                    }
                }.joinAll()
            }
        } catch (cancelled: CancellationException) {
            if (!manualAdvanceRequested) throw cancelled
            rows.indices.forEach { index ->
                if (rows[index].status == RouteSpeedStatus.STARTING || rows[index].status == RouteSpeedStatus.TESTING) {
                    rows[index] = rows[index].copy(
                        status = if (rows[index].usable) RouteSpeedStatus.PASSED else RouteSpeedStatus.STOPPED,
                        detail = "${stage.title}: skipped after manual advance",
                    )
                }
            }
            persistAllRows()
            notice = "${manualAdvanceCandidateIds.orEmpty().size} healthy routes promoted from ${stage.title}"
        } finally {
            stageJob = null
            persistSavedRouteListSnapshot()
        }
    }

    private suspend fun updateProbeStage(
        candidateId: String,
        stage: RouteTournamentStage,
        probeStage: RouteSpeedProbeStage,
    ) = withContext(Dispatchers.Main.immediate) {
        updateRow(candidateId) { current ->
            current.copy(
                status = when (probeStage) {
                    RouteSpeedProbeStage.STARTING -> RouteSpeedStatus.STARTING
                    RouteSpeedProbeStage.PROBING -> RouteSpeedStatus.TESTING
                },
                detail = when (probeStage) {
                    RouteSpeedProbeStage.STARTING -> "${stage.title}: starting a fresh Xray route"
                    RouteSpeedProbeStage.PROBING -> if (stage == RouteTournamentStage.MTU_VALIDATION) {
                        "${stage.title}: validating native TUN, HTTP, DNS, upload and download"
                    } else {
                        "${stage.title}: testing HTTP, DNS, upload and download"
                    }
                },
            )
        }
    }

    private fun rowsAcceptedInStage(stage: RouteTournamentStage): List<RouteSpeedRow> =
        rows.filter { row ->
            row.observations.any { observation ->
                observation.stage == stage && observation.accepted
            }
        }

    private fun rowsAcceptedInStage(
        stage: RouteTournamentStage,
        candidateIds: Collection<String>,
    ): List<RouteSpeedRow> {
        val idSet = candidateIds.toHashSet()
        return rows.filter { row ->
            row.candidateId in idSet && row.observations.any { observation ->
                observation.stage == stage && observation.accepted
            }
        }
    }

    private fun promotionRowsForNextStage(): List<RouteSpeedRow> {
        val next = currentStage.next()
        if (next == RouteTournamentStage.COMPLETE) return rowsAcceptedInStage(currentStage)
        if (
            currentStage == RouteTournamentStage.MTU_VALIDATION &&
            next == RouteTournamentStage.STABILITY
        ) {
            val mtuShortlist = restoreStageIds(RouteTournamentStage.MTU_VALIDATION).orEmpty()
            val mtuPassed = rowsAcceptedInStage(RouteTournamentStage.MTU_VALIDATION, mtuShortlist)
            if (mtuPassed.isNotEmpty()) return mtuPassed
            return rowsAcceptedInStage(RouteTournamentStage.VERIFICATION, mtuShortlist)
        }
        return rowsAcceptedInStage(currentStage)
    }

    private fun promotionSourceRows(
        targetStage: RouteTournamentStage,
        candidateIds: Collection<String>,
    ): List<RouteSpeedRow> {
        val previous = targetStage.previous() ?: return emptyList()
        val idSet = candidateIds.toHashSet()
        val primary = rowsAcceptedInStage(previous, idSet)
        if (primary.isNotEmpty()) return primary
        if (
            targetStage == RouteTournamentStage.STABILITY &&
            previous == RouteTournamentStage.MTU_VALIDATION
        ) {
            return rowsAcceptedInStage(RouteTournamentStage.VERIFICATION, idSet)
        }
        return emptyList()
    }

    private fun manualAdvanceShortlist(stage: RouteTournamentStage): List<String> {
        val next = stage.next()
        val passedThisStage = if (stage == currentStage) {
            promotionRowsForNextStage()
        } else {
            rowsAcceptedInStage(stage)
        }
        if (passedThisStage.isEmpty()) return emptyList()
        if (next == RouteTournamentStage.COMPLETE) {
            return rankedRows(passedThisStage).map(RouteSpeedRow::candidateId)
        }
        val limit = minOf(next.shortlistSize, passedThisStage.size)
        return when (next) {
            RouteTournamentStage.VERIFICATION -> resolverRepresentatives(passedThisStage, limit)
            RouteTournamentStage.MTU_VALIDATION -> expandMtuFamilies(passedThisStage, limit)
            RouteTournamentStage.STABILITY -> diverseShortlist(passedThisStage, limit)
            RouteTournamentStage.STRESS,
            RouteTournamentStage.CHAMPIONSHIP -> rankedRows(passedThisStage).take(limit).map(RouteSpeedRow::candidateId)
            else -> emptyList()
        }
    }

    private fun applyPausedManualAdvance(prepared: RouteSpeedTestPlan, promoted: List<String>) {
        val next = currentStage.next()
        manualAdvanceRequested = false
        manualAdvanceCandidateIds = null
        if (next == RouteTournamentStage.COMPLETE) {
            preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
            paused = false
            finishTournament()
            stopKeepAliveService()
            return
        }
        persistStage(next, promoted)
        currentStage = next
        preferences.edit().putString(KEY_STAGE, next.name).apply()
        updatePhaseProgress(next)
        notice = "${promoted.size} healthy routes promoted • starting ${next.title}"
        beginRun(prepared, reset = false)
    }

    private fun stageCandidateIds(stage: RouteTournamentStage): List<String> {
        val validIds = rows.mapTo(HashSet()) { it.candidateId }
        restoreStageIds(stage)?.filter { it in validIds }?.distinct()?.let { restored ->
            if (restored.isNotEmpty()) return restored
        }
        if (stage == RouteTournamentStage.QUALIFIER) return rows.map(RouteSpeedRow::candidateId)
        val previous = stage.previous() ?: return emptyList()
        val sourceIds = restoreStageIds(previous).orEmpty().ifEmpty { rows.map(RouteSpeedRow::candidateId) }
        val sourceRows = promotionSourceRows(stage, sourceIds)
        val limit = minOf(stage.shortlistSize, sourceRows.size)
        if (limit <= 0) return emptyList()
        return when (stage) {
            RouteTournamentStage.VERIFICATION -> resolverRepresentatives(sourceRows, limit)
            RouteTournamentStage.MTU_VALIDATION -> expandMtuFamilies(sourceRows, limit)
            RouteTournamentStage.STABILITY -> diverseShortlist(sourceRows, limit)
            RouteTournamentStage.STRESS,
            RouteTournamentStage.CHAMPIONSHIP -> rankedRows(sourceRows).take(limit).map(RouteSpeedRow::candidateId)
            else -> emptyList()
        }
    }

    private fun diverseShortlist(source: List<RouteSpeedRow>, limit: Int): List<String> {
        val ranked = rankedRows(source)
        val selected = LinkedHashSet<String>()
        ranked.take(maxOf(1, limit / 4)).forEach { selected += it.candidateId }
        fun addCategoryBest(selector: (RouteSpeedRow) -> String) {
            ranked.groupBy(selector).values
                .mapNotNull { group -> group.firstOrNull() }
                .sortedWith(routeRankingComparator())
                .forEach { if (selected.size < limit) selected += it.candidateId }
        }
        addCategoryBest(RouteSpeedRow::edgeKey)
        addCategoryBest(RouteSpeedRow::resolverKey)
        addCategoryBest(RouteSpeedRow::fragmentKey)
        addCategoryBest { it.mtu.toString() }
        ranked.forEach { if (selected.size < limit) selected += it.candidateId }
        return selected.take(limit)
    }

    private fun resolverRepresentatives(source: List<RouteSpeedRow>, limit: Int): List<String> {
        val preferredMtu = plan?.session?.settings?.tunMtu ?: 1_280
        val representatives = source
            .groupBy { it.resolverFamilyKey() }
            .values
            .mapNotNull { family ->
                family.sortedWith(
                    compareByDescending<RouteSpeedRow> { it.tournamentScore }
                        .thenByDescending { it.confidence }
                        .thenBy { kotlin.math.abs(it.mtu - preferredMtu) }
                        .thenBy { it.candidateId },
                ).firstOrNull()
            }
        return diverseShortlist(representatives, minOf(limit, representatives.size))
    }

    private fun expandMtuFamilies(source: List<RouteSpeedRow>, limit: Int): List<String> {
        if (source.isEmpty() || limit <= 0) return emptyList()
        val familyLimit = minOf(24, source.map { it.resolverFamilyKey() }.distinct().size)
        val familyWinners = diverseShortlist(source, familyLimit)
            .mapNotNull { id -> rows.firstOrNull { it.candidateId == id } }
            .map { it.resolverFamilyKey() }
            .toSet()
        val rankedFamilies = rankedRows(source)
            .map { it.resolverFamilyKey() }
            .distinct()
        val selectedFamilies = (familyWinners + rankedFamilies).distinct().take(familyLimit).toSet()
        return rows
            .filter { it.resolverFamilyKey() in selectedFamilies }
            .sortedWith(
                compareBy<RouteSpeedRow> { rankedFamilies.indexOf(it.resolverFamilyKey()).let { rank ->
                    if (rank < 0) Int.MAX_VALUE else rank
                } }.thenBy { it.mtu },
            )
            .map(RouteSpeedRow::candidateId)
            .distinct()
            .take(limit)
    }

    private fun RouteSpeedRow.resolverFamilyKey(): String =
        "$edgeKey|$resolverKey|$fragmentKey"

    private fun buildSchedule(
        prepared: RouteSpeedTestPlan,
        stage: RouteTournamentStage,
        candidateIds: List<String>,
    ): List<String> {
        if (stage == RouteTournamentStage.CHAMPIONSHIP && candidateIds.size >= 2) {
            val a = candidateIds[0]
            val b = candidateIds[1]
            return listOf(a, b, b, a)
        }
        val seedBase = "${prepared.signature}|${prepared.session.network.exactStorageKey()}|${stage.name}".hashCode()
        return buildList {
            repeat(stage.samplesPerCandidate) { round ->
                addAll(candidateIds.shuffled(Random(seedBase + round * 7_919)))
            }
        }
    }

    private fun transferConfigFor(stage: RouteTournamentStage): RouteTransferProbeConfig? = when (stage) {
        RouteTournamentStage.QUALIFIER -> null
        RouteTournamentStage.VERIFICATION,
        RouteTournamentStage.MTU_VALIDATION -> RouteTransferProbeConfig(
            uploadBytes = 64 * 1_024,
            downloadBytes = 64 * 1_024,
        )
        RouteTournamentStage.STABILITY -> RouteTransferProbeConfig(
            uploadBytes = 128 * 1_024,
            downloadBytes = 128 * 1_024,
        )
        RouteTournamentStage.STRESS -> RouteTransferProbeConfig(
            uploadBytes = 512 * 1_024,
            downloadBytes = 512 * 1_024,
            readTimeoutMs = 20_000,
        )
        RouteTournamentStage.CHAMPIONSHIP -> RouteTransferProbeConfig(
            uploadBytes = 1 * 1_024 * 1_024,
            downloadBytes = 1 * 1_024 * 1_024,
            readTimeoutMs = 30_000,
        )
        RouteTournamentStage.COMPLETE -> null
    }

    private fun remainingSchedule(stage: RouteTournamentStage, schedule: List<String>): List<String> {
        val alreadyCompleted = rows.associate { row ->
            row.candidateId to row.observations.count { it.stage == stage }
        }
        val desiredOccurrence = HashMap<String, Int>()
        return schedule.filter { candidateId ->
            val occurrence = (desiredOccurrence[candidateId] ?: 0) + 1
            desiredOccurrence[candidateId] = occurrence
            (alreadyCompleted[candidateId] ?: 0) < occurrence
        }
    }

    private fun aggregateResult(
        previous: RouteSpeedRow,
        result: RouteSpeedProbeResult,
        stage: RouteTournamentStage,
    ): RouteSpeedRow {
        val failure = classifyFailure(result)
        val observations = previous.observations + result.toObservation(stage, failure)
        val successful = observations.count(RouteObservation::accepted)
        val latencies = observations.mapNotNull(RouteObservation::latencyMs).sorted()
        val dnsLatencies = observations.filter(RouteObservation::dnsSucceeded)
            .mapNotNull(RouteObservation::dnsLatencyMs)
            .sorted()
        val throughputs = observations.map(RouteObservation::throughputKbps).filter { it > 0L }.sorted()
        val uploads = observations.map(RouteObservation::uploadKbps).filter { it > 0L }.sorted()
        val downloads = observations.map(RouteObservation::downloadKbps).filter { it > 0L }.sorted()
        val measuredJitter = observations.mapNotNull(RouteObservation::jitterMs).sorted()
        val dnsSuccesses = observations.count(RouteObservation::dnsSucceeded)
        val currentStageSucceeded = observations.any { observation ->
            observation.stage == stage && observation.accepted
        }
        val base = previous.copy(
            status = if (currentStageSucceeded) RouteSpeedStatus.PASSED else RouteSpeedStatus.FAILED,
            stageReached = stage,
            score = observations.map(RouteObservation::score).average().roundToInt(),
            latencyMs = median(latencies),
            p95LatencyMs = percentile95(latencies),
            jitterMs = median(measuredJitter)
                ?: if (latencies.size >= 2) (percentile95(latencies) ?: 0L) - latencies.first() else null,
            dnsLatencyMs = median(dnsLatencies),
            payloadBytes = observations.sumOf(RouteObservation::payloadBytes),
            throughputKbps = median(throughputs) ?: 0L,
            httpSucceeded = observations.sumOf(RouteObservation::httpSucceeded),
            httpAttempted = observations.sumOf(RouteObservation::httpAttempted),
            dnsSucceeded = dnsSuccesses > 0,
            dnsSuccessCount = dnsSuccesses,
            successfulSamples = successful,
            observations = observations,
            failureFingerprint = when {
                successful == observations.size -> "Healthy across every sample"
                successful > 0 -> "Intermittent • $failure"
                else -> failure
            },
            detail = result.error ?: result.detail,
            uploadBytes = observations.sumOf(RouteObservation::uploadBytes),
            downloadBytes = observations.sumOf(RouteObservation::downloadBytes),
            uploadKbps = median(uploads) ?: 0L,
            downloadKbps = median(downloads) ?: 0L,
            transferSuccessCount = observations.count(RouteObservation::transferValidated),
            endpointFailureCount = observations.count(RouteObservation::endpointFailure),
            txDelta = observations.sumOf(RouteObservation::txDelta),
            rxDelta = observations.sumOf(RouteObservation::rxDelta),
            nativeSampleCount = observations.count(RouteObservation::mtuValidated),
            mtuValidated = observations.any(RouteObservation::mtuValidated),
        )
        return base.copy(
            tournamentScore = calculateTournamentScore(base),
            confidence = calculateRouteConfidence(base),
        )
    }

    private fun calculateTournamentScore(row: RouteSpeedRow): Int {
        if (row.sampleCount == 0) return 0
        val passRate = row.successfulSamples.toDouble() / row.sampleCount
        val httpRate = if (row.httpAttempted > 0) row.httpSucceeded.toDouble() / row.httpAttempted else 0.0
        val dnsRate = row.dnsSuccessCount.toDouble() / row.sampleCount
        val speedReward = if (row.throughputKbps > 0) log2(row.throughputKbps.toDouble() + 1.0) * 18.0 else 0.0
        val latencyPenalty = ((row.p95LatencyMs ?: 8_000L) / 24.0).coerceAtMost(170.0)
        val jitterPenalty = ((row.jitterMs ?: 0L) / 15.0).coerceAtMost(90.0)
        return (
            passRate * 330.0 +
                row.score * 3.8 +
                httpRate * 110.0 +
                dnsRate * 75.0 +
                speedReward -
                latencyPenalty -
                jitterPenalty
            ).roundToInt().coerceIn(0, 1_000)
    }

    private fun calculateRouteConfidence(row: RouteSpeedRow): Int {
        if (row.sampleCount == 0) return 0
        val sampleFactor = (row.sampleCount / 9.0).coerceIn(0.0, 1.0)
        val passRate = row.successfulSamples.toDouble() / row.sampleCount
        val dnsRate = row.dnsSuccessCount.toDouble() / row.sampleCount
        val httpRate = if (row.httpAttempted > 0) row.httpSucceeded.toDouble() / row.httpAttempted else 0.0
        val jitterQuality = 1.0 - ((row.jitterMs ?: 0L) / 2_500.0).coerceIn(0.0, 1.0)
        val measurableTransfers = (row.sampleCount - row.endpointFailureCount).coerceAtLeast(0)
        val transferRate = if (measurableTransfers == 0) 0.5 else {
            (row.transferSuccessCount.toDouble() / measurableTransfers).coerceIn(0.0, 1.0)
        }
        val nativeQuality = if (row.stageReached >= RouteTournamentStage.MTU_VALIDATION) {
            if (row.mtuValidated) 1.0 else 0.0
        } else {
            0.5
        }
        return (
            sampleFactor * 20.0 +
                passRate * 30.0 +
                dnsRate * 12.0 +
                httpRate * 12.0 +
                transferRate * 14.0 +
                nativeQuality * 7.0 +
                jitterQuality * 5.0
            ).roundToInt().coerceIn(0, 99)
    }

    private fun classifyFailure(result: RouteSpeedProbeResult): String {
        if (result.accepted) return "Healthy"
        val text = listOf(result.error, result.detail).joinToString(" ").lowercase()
        return when {
            "ssl" in text || "tls" in text || "handshake" in text || "certificate" in text ->
                "TLS/SNI handshake blocked"
            "reset" in text || "broken pipe" in text || "econnreset" in text ->
                "TCP path reset"
            result.httpSucceeded > 0 && !result.dnsSucceeded ->
                "DNS path failed after HTTP succeeded"
            result.httpAttempted > 0 && result.httpSucceeded in 1 until result.httpAttempted ->
                "Partial egress • only some targets worked"
            result.dnsSucceeded && result.httpSucceeded == 0 ->
                "HTTP egress blocked while DNS worked"
            result.throughputKbps in 1 until MIN_STABLE_THROUGHPUT_KBPS ->
                "Unstable or stalled throughput"
            "timeout" in text || "timed out" in text ->
                "Route timeout before complete connectivity"
            "network is unreachable" in text || "no route" in text ->
                "Edge unreachable on this network"
            else -> "Connectivity probe rejected the route"
        }
    }

    private fun finishTournament() {
        persistSavedRouteListSnapshot()
        currentStage = RouteTournamentStage.COMPLETE
        preferences.edit().putString(KEY_STAGE, RouteTournamentStage.COMPLETE.name).apply()
        phaseCompletedCount = phaseTotalCount
        updateRecommendedCandidates()
        val prepared = plan
        val champion = recommendedCandidateId?.let { id -> rows.firstOrNull { it.candidateId == id && it.usable } }
        val backup = backupCandidateId?.let { id -> rows.firstOrNull { it.candidateId == id && it.usable } }
        if (prepared != null && champion != null) {
            tester.selectRouteWinner(
                plan = prepared,
                candidateId = champion.candidateId,
                score = champion.score,
                metrics = champion.toAdaptiveRouteMetrics(),
                backupCandidateId = backup?.candidateId,
                backupScore = backup?.score ?: 0,
                backupMetrics = backup?.toAdaptiveRouteMetrics() ?: AdaptiveRouteMetrics(0),
            )
            selectedCandidateId = champion.candidateId
            savedRouteProfileId = prepared.profile.id
            savedRouteProfileName = prepared.profile.name
            savedChampionLabel = champion.label
            savedBackupLabel = backup?.label
            savedRouteDetails = buildSavedRouteDetails(
                prepared,
                prepared.candidates.firstOrNull { it.id == champion.candidateId }?.toSavedRouteDetails(),
                backup?.candidateId?.let { id ->
                    prepared.candidates.firstOrNull { it.id == id }?.toSavedRouteDetails()
                },
            )
        }
        notice = if (recommendedCandidateId == null) {
            "Tournament complete • no route passed the complete connectivity check"
        } else {
            "Tournament complete • Champion confidence $championConfidence% • backup ready"
        }
    }

    private fun updateRecommendedCandidates() {
        val ranked = rankedRows(
            recommendationRowsForStage(
                rows = rows,
                currentStage = currentStage,
                stageCandidateIds = ::restoreStageIds,
            ),
        )
        val champion = ranked.firstOrNull()
        val backup = champion?.let { preferredBackupRow(it, ranked) }
        recommendedCandidateId = champion?.candidateId
        backupCandidateId = backup?.candidateId
        championConfidence = if (champion == null) {
            0
        } else {
            val margin = if (backup == null) {
                8
            } else {
                ((champion.tournamentScore - backup.tournamentScore).coerceAtLeast(0) / 8).coerceAtMost(12)
            }
            (champion.confidence + margin).coerceAtMost(99)
        }
    }

    private fun rankedRows(source: List<RouteSpeedRow> = rows): List<RouteSpeedRow> =
        source.sortedWith(routeRankingComparator())

    private fun routeRankingComparator(): Comparator<RouteSpeedRow> =
        compareByDescending<RouteSpeedRow> { it.tournamentScore }
            .thenByDescending { it.successfulSamples }
            .thenByDescending { it.confidence }
            .thenByDescending { it.throughputKbps }
            .thenBy { it.p95LatencyMs ?: Long.MAX_VALUE }

    private fun initialRow(candidate: AdaptiveCandidate): RouteSpeedRow {
        val resolver = AdaptiveDnsResolvers.idFor(candidate.settings.dnsResolverUrl)
        val fragment = if (candidate.runtimeOptions.finalmaskEnabled) {
            "${candidate.settings.finalmaskPacket}/${candidate.edge.finalmaskMaxSplit}/${candidate.settings.finalmaskDelayMs}ms"
        } else {
            "Fragment off"
        }
        return RouteSpeedRow(
            candidateId = candidate.id,
            label = candidate.label,
            route = "${candidate.edge.address}:${candidate.edge.port}  •  $resolver  •  $fragment  •  MTU ${candidate.settings.tunMtu}",
            edgeKey = "${candidate.edge.address}:${candidate.edge.port}",
            resolverKey = resolver,
            fragmentKey = fragment,
            mtu = candidate.settings.tunMtu,
        )
    }

    private fun restoreRows(prepared: RouteSpeedTestPlan) {
        val matching = hasMatchingSession(prepared)
        rows.clear()
        rows.addAll(
            prepared.candidates.map { candidate ->
                if (matching) restoreRow(candidate) ?: initialRow(candidate) else initialRow(candidate)
            },
        )
        if (!matching) {
            clearPersistedRows()
            preferences.edit().putBoolean(KEY_RUN_REQUESTED, false).apply()
        }
    }

    private fun createSession(prepared: RouteSpeedTestPlan) {
        preferences.edit()
            .putString(KEY_PROFILE_ID, prepared.profile.id)
            .putString(KEY_SIGNATURE, prepared.signature)
            .putString(KEY_NETWORK_KEY, prepared.session.network.exactStorageKey())
            .putString(KEY_DISCOVERY_ID, prepared.discoveryId)
            .putString(KEY_CANDIDATE_IDS, prepared.candidates.joinToString("\n") { it.id })
            .putString(KEY_STAGE, RouteTournamentStage.QUALIFIER.name)
            .putString(stageIdsKey(RouteTournamentStage.QUALIFIER), prepared.candidates.joinToString("\n") { it.id })
            .putBoolean(KEY_SESSION_EXISTS, true)
            .apply()
    }

    private fun hasMatchingSession(prepared: RouteSpeedTestPlan): Boolean =
        preferences.getBoolean(KEY_SESSION_EXISTS, false) &&
            preferences.getString(KEY_PROFILE_ID, null) == prepared.profile.id &&
            preferences.getString(KEY_SIGNATURE, null) == prepared.signature &&
            preferences.getString(KEY_NETWORK_KEY, null) == prepared.session.network.exactStorageKey() &&
            preferences.getString(KEY_DISCOVERY_ID, null) == prepared.discoveryId &&
            preferences.getString(KEY_CANDIDATE_IDS, null) ==
            prepared.candidates.joinToString("\n") { it.id }

    private fun persistStage(stage: RouteTournamentStage, candidateIds: List<String>) {
        preferences.edit()
            .putString(KEY_STAGE, stage.name)
            .putString(stageIdsKey(stage), candidateIds.joinToString("\n"))
            .apply()
    }

    private fun restoreCurrentStage(): RouteTournamentStage = runCatching {
        RouteTournamentStage.valueOf(
            preferences.getString(KEY_STAGE, RouteTournamentStage.QUALIFIER.name)
                ?: RouteTournamentStage.QUALIFIER.name,
        )
    }.getOrDefault(RouteTournamentStage.QUALIFIER)

    private fun restoreStageIds(stage: RouteTournamentStage): List<String>? =
        preferences.getString(stageIdsKey(stage), null)
            ?.lineSequence()
            ?.filter(String::isNotBlank)
            ?.toList()

    private fun updatePhaseProgress(stage: RouteTournamentStage) {
        if (stage == RouteTournamentStage.COMPLETE) {
            val prepared = plan
            val finalists = restoreStageIds(RouteTournamentStage.CHAMPIONSHIP).orEmpty()
            phaseTotalCount = if (prepared != null && finalists.isNotEmpty()) {
                buildSchedule(prepared, RouteTournamentStage.CHAMPIONSHIP, finalists).size
            } else {
                4
            }
            phaseCompletedCount = phaseTotalCount
            return
        }
        val prepared = plan ?: return
        val ids = stageCandidateIds(stage)
        val schedule = buildSchedule(prepared, stage, ids)
        phaseTotalCount = schedule.size
        phaseCompletedCount = schedule.size - remainingSchedule(stage, schedule).size
    }

    private fun persistRow(row: RouteSpeedRow) {
        preferences.edit().putString(rowKey(row.candidateId), row.toJson().toString()).apply()
    }

    private fun persistRows(candidateIds: Collection<String>) {
        if (candidateIds.isEmpty()) return
        val ids = candidateIds.toHashSet()
        val editor = preferences.edit()
        rows.asSequence()
            .filter { it.candidateId in ids }
            .forEach { row -> editor.putString(rowKey(row.candidateId), row.toJson().toString()) }
        editor.apply()
    }

    private fun persistAllRows() {
        val editor = preferences.edit()
        rows.forEach { editor.putString(rowKey(it.candidateId), it.toJson().toString()) }
        editor.apply()
    }

    private fun restoreRow(candidate: AdaptiveCandidate): RouteSpeedRow? {
        val raw = preferences.getString(rowKey(candidate.id), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val observations = json.optJSONArray("observations")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.toObservation()?.let(::add)
                    }
                }
            }.orEmpty()
            val restoredStatus = runCatching {
                RouteSpeedStatus.valueOf(json.optString("status", RouteSpeedStatus.QUEUED.name))
            }.getOrDefault(RouteSpeedStatus.QUEUED)
            val base = initialRow(candidate).copy(
                status = if (restoredStatus in setOf(RouteSpeedStatus.STARTING, RouteSpeedStatus.TESTING)) {
                    RouteSpeedStatus.STOPPED
                } else {
                    restoredStatus
                },
                stageReached = runCatching {
                    RouteTournamentStage.valueOf(json.optString("stageReached", RouteTournamentStage.QUALIFIER.name))
                }.getOrDefault(RouteTournamentStage.QUALIFIER),
                score = json.optInt("score", 0),
                tournamentScore = json.optInt("tournamentScore", 0),
                confidence = json.optInt("confidence", 0),
                latencyMs = json.optLongOrNull("latency"),
                p95LatencyMs = json.optLongOrNull("p95Latency"),
                jitterMs = json.optLongOrNull("jitter"),
                dnsLatencyMs = json.optLongOrNull("dnsLatency"),
                payloadBytes = json.optInt("payload", 0),
                throughputKbps = json.optLong("throughput", 0L),
                httpSucceeded = json.optInt("httpSucceeded", 0),
                httpAttempted = json.optInt("httpAttempted", 0),
                dnsSucceeded = json.optBoolean("dnsSucceeded", false),
                dnsSuccessCount = json.optInt("dnsSuccessCount", 0),
                successfulSamples = json.optInt("successfulSamples", 0),
                observations = observations,
                failureFingerprint = json.optString("failureFingerprint", "Not tested"),
                detail = json.optString("detail", "Waiting to resume").take(MAX_PERSISTED_DETAIL),
                uploadBytes = json.optInt("uploadBytes", 0),
                downloadBytes = json.optInt("downloadBytes", 0),
                uploadKbps = json.optLong("uploadKbps", 0L),
                downloadKbps = json.optLong("downloadKbps", 0L),
                transferSuccessCount = json.optInt("transferSuccessCount", 0),
                endpointFailureCount = json.optInt("endpointFailureCount", 0),
                txDelta = json.optLong("txDelta", 0L),
                rxDelta = json.optLong("rxDelta", 0L),
                nativeSampleCount = json.optInt("nativeSampleCount", 0),
                mtuValidated = json.optBoolean("mtuValidated", false),
            )
            if (observations.isEmpty()) base else base.copy(
                successfulSamples = observations.count(RouteObservation::accepted),
            )
        }.getOrNull()
    }

    private fun RouteSpeedRow.toJson() = JSONObject()
        .put("candidateId", candidateId)
        .put("label", label)
        .put("route", route)
        .put("edgeKey", edgeKey)
        .put("resolverKey", resolverKey)
        .put("fragmentKey", fragmentKey)
        .put("mtu", mtu)
        .put("status", status.name)
        .put("stageReached", stageReached.name)
        .put("score", score)
        .put("tournamentScore", tournamentScore)
        .put("confidence", confidence)
        .put("latency", latencyMs ?: JSONObject.NULL)
        .put("p95Latency", p95LatencyMs ?: JSONObject.NULL)
        .put("jitter", jitterMs ?: JSONObject.NULL)
        .put("dnsLatency", dnsLatencyMs ?: JSONObject.NULL)
        .put("payload", payloadBytes)
        .put("throughput", throughputKbps)
        .put("httpSucceeded", httpSucceeded)
        .put("httpAttempted", httpAttempted)
        .put("dnsSucceeded", dnsSucceeded)
        .put("dnsSuccessCount", dnsSuccessCount)
        .put("successfulSamples", successfulSamples)
        .put("failureFingerprint", failureFingerprint)
        .put("detail", detail.take(MAX_PERSISTED_DETAIL))
        .put("uploadBytes", uploadBytes)
        .put("downloadBytes", downloadBytes)
        .put("uploadKbps", uploadKbps)
        .put("downloadKbps", downloadKbps)
        .put("transferSuccessCount", transferSuccessCount)
        .put("endpointFailureCount", endpointFailureCount)
        .put("txDelta", txDelta)
        .put("rxDelta", rxDelta)
        .put("nativeSampleCount", nativeSampleCount)
        .put("mtuValidated", mtuValidated)
        .put("observations", JSONArray().apply {
            observations.forEach { put(it.toJson()) }
        })

    private fun applyLoadedSavedRouteMarkers(
        profile: ProxyProfile,
        network: NetworkFingerprint,
        signature: String,
        loadedRows: List<RouteSpeedRow>,
    ) {
        val loadedIds = loadedRows.map(RouteSpeedRow::candidateId).toSet()
        val champion = adaptiveProfileStore.savedRoute(network, profile, signature)
        val backup = adaptiveProfileStore.savedBackupRoute(network, profile, signature)
        savedRouteProfileId = champion?.let { profile.id }
        savedRouteProfileName = champion?.let { profile.name }
        savedChampionLabel = champion?.label
        savedBackupLabel = backup?.label
        val championId = champion?.id?.takeIf { it in loadedIds }
        val backupId = backup?.id?.takeIf { it in loadedIds && it != championId }
        if (championId != null) {
            selectedCandidateId = championId
            backupCandidateId = backupId
            recommendedCandidateId = championId
        } else {
            loadedRows.firstOrNull()?.candidateId?.let { recommendedCandidateId = it }
            backupCandidateId = backupId
        }
    }

    private fun buildLoadableRouteListLight(
        profile: ProxyProfile,
        network: NetworkFingerprint,
        signature: String,
    ): List<RouteSpeedRow> {
        val merged = mergeRouteRows(
            restoreFinalStageSnapshotLight(profile.id, signature, network),
            restorePersistedRowSnapshot(profile.id, signature, network),
        )
        if (merged.isNotEmpty()) return merged
        val champion = adaptiveProfileStore.savedRoute(network, profile, signature)
        val backup = adaptiveProfileStore.savedBackupRoute(network, profile, signature)
        val savedRoutes = listOfNotNull(champion, backup).distinctBy(AdaptiveSavedRoute::id)
        if (savedRoutes.isNotEmpty()) {
            return savedRoutes.map(::routeSpeedRowFromAdaptive).sortedWith(routeRankingComparator())
        }
        val details = savedRouteDetails?.takeIf { it.profileId == profile.id }
        if (details != null) {
            return listOfNotNull(details.champion, details.backup)
                .distinctBy(SavedRouteDetails::id)
                .map(::routeSpeedRowFromSavedDetailsLight)
                .sortedWith(routeRankingComparator())
        }
        return emptyList()
    }

    private fun restoreFinalStageSnapshotLight(
        profileId: String,
        signature: String,
        network: NetworkFingerprint,
    ): List<RouteSpeedRow> {
        val raw = preferences.getString(finalStageSnapshotKey(profileId, signature, network), null)
            ?: return emptyList()
        return runCatching {
            val snapshot = JSONObject(raw)
            if (
                snapshot.optString("profileId") != profileId ||
                snapshot.optString("signature") != signature ||
                snapshot.optString("networkKey") != network.exactStorageKey() ||
                snapshot.optString("carrier") != network.carrier ||
                snapshot.optString("carrierClass") != network.carrierClass
            ) {
                return@runCatching emptyList()
            }
            finalStageHistoryTitle = buildString {
                append(snapshot.optString("profileName", "Previous profile"))
                snapshot.optString("networkLabel", "").takeIf(String::isNotBlank)?.let {
                    append(" • ").append(it)
                }
            }
            val array = snapshot.optJSONArray("rows") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSnapshotRow()?.let(::add)
                }
            }.filter(::isSnapshotEligible).sortedWith(routeRankingComparator())
        }.getOrDefault(emptyList())
    }

    private fun restorePersistedRowSnapshot(
        profileId: String,
        signature: String,
        network: NetworkFingerprint,
    ): List<RouteSpeedRow> {
        if (!preferences.getBoolean(KEY_SESSION_EXISTS, false)) return emptyList()
        if (preferences.getString(KEY_PROFILE_ID, null) != profileId) return emptyList()
        if (preferences.getString(KEY_SIGNATURE, null) != signature) return emptyList()
        if (preferences.getString(KEY_NETWORK_KEY, null) != network.exactStorageKey()) return emptyList()
        val participantIds = allStageParticipantIds()
        val restored = participantIds.mapNotNull { candidateId ->
            preferences.getString(rowKey(candidateId), null)?.let { raw ->
                runCatching { JSONObject(raw).toSnapshotRow() }.getOrNull()
            }
        }.filter(::isSnapshotEligible)
        return rankedRows(restored)
    }

    private fun routeSpeedRowFromAdaptive(saved: AdaptiveSavedRoute): RouteSpeedRow {
        val existing = rows.firstOrNull { it.candidateId == saved.id }
        if (existing != null && existing.usable) return existing
        val resolver = AdaptiveDnsResolvers.idFor(saved.resolverUrl)
        val fragment = if (saved.finalmaskEnabled) {
            "${saved.finalmaskPacket}/${saved.maxSplit}/${saved.finalmaskDelayMs}ms"
        } else {
            "Fragment off"
        }
        val throughput = saved.downloadKbps.coerceAtLeast(saved.uploadKbps)
        return RouteSpeedRow(
            candidateId = saved.id,
            label = saved.label,
            route = "${saved.address}:${saved.port}  •  $resolver  •  $fragment  •  MTU ${saved.tunMtu}",
            edgeKey = "${saved.address}:${saved.port}",
            resolverKey = resolver,
            fragmentKey = fragment,
            mtu = saved.tunMtu,
            status = RouteSpeedStatus.PASSED,
            stageReached = RouteTournamentStage.CHAMPIONSHIP,
            score = saved.score,
            tournamentScore = saved.score,
            confidence = saved.confidence,
            latencyMs = saved.pingMs,
            p95LatencyMs = saved.pingMs,
            jitterMs = saved.jitterMs,
            throughputKbps = throughput,
            downloadKbps = saved.downloadKbps,
            uploadKbps = saved.uploadKbps,
            mtuValidated = saved.mtuValidated,
            successfulSamples = 1,
            detail = "Saved route profile",
        )
    }

    private fun routeSpeedRowFromSavedDetailsLight(details: SavedRouteDetails): RouteSpeedRow {
        val existing = rows.firstOrNull { it.candidateId == details.id }
        if (existing != null && existing.usable) return existing
        return RouteSpeedRow(
            candidateId = details.id,
            label = details.label,
            route = "${details.edge}  •  ${details.resolver}  •  ${details.fragment}  •  MTU ${details.mtu}",
            edgeKey = details.edge,
            resolverKey = details.resolver,
            fragmentKey = details.fragment,
            mtu = details.mtu,
            status = RouteSpeedStatus.PASSED,
            stageReached = RouteTournamentStage.CHAMPIONSHIP,
            successfulSamples = 1,
            detail = "Saved route profile",
        )
    }

    private fun applyPreparedPlanState(prepared: RouteSpeedTestPlan, clearHistory: Boolean) {
        profileLibrary = selectedTestProfileLibrary(profileStore.snapshot())
        profileName = prepared.profile.name
        activeRouteNetwork = prepared.session.network
        networkLabel = buildNetworkLabel(prepared)
        if (clearHistory) {
            viewingFinalStageHistory = false
            finalStageRows.clear()
        }
        selectedCandidateId = prepared.savedChampionId
        savedRouteProfileId = prepared.savedChampionLabel?.let { prepared.profile.id }
        savedRouteProfileName = prepared.savedChampionLabel?.let { prepared.profile.name }
        savedChampionLabel = prepared.savedChampionLabel
        savedBackupLabel = prepared.savedBackupLabel
        savedRouteDetails = buildSavedRouteDetails(
            prepared,
            prepared.savedChampion?.toSavedRouteDetails(),
            prepared.savedBackup?.toSavedRouteDetails(),
        )
        finalStageHistoryAvailable = hasFinalStageSnapshot(prepared) ||
            prepared.savedChampion != null ||
            hasSavedRouteInStore(prepared) ||
            savedRouteDetails != null ||
            (hasMatchingSession(prepared) &&
                !restoreStageIds(RouteTournamentStage.CHAMPIONSHIP).isNullOrEmpty())
        restoreRows(prepared)
        currentStage = restoreCurrentStage()
        updatePhaseProgress(currentStage)
        updateRecommendedCandidates()
        paused = hasMatchingSession(prepared) && currentStage != RouteTournamentStage.COMPLETE
    }

    private fun buildLoadableRouteList(prepared: RouteSpeedTestPlan): List<RouteSpeedRow> {
        val merged = mergeRouteRows(
            if (hasMatchingSession(prepared)) completeSessionSnapshotRows() else emptyList(),
            restoreFinalStageSnapshotForLoad(prepared),
            restorePersistedRowSnapshot(
                prepared.profile.id,
                prepared.signature,
                prepared.session.network,
            ),
        )
        if (merged.isNotEmpty()) return merged
        if (hasMatchingSession(prepared)) {
            val ids = restoreStageIds(RouteTournamentStage.CHAMPIONSHIP).orEmpty()
            val championship = rows.filter { it.candidateId in ids }
            if (championship.isNotEmpty()) return rankedRows(championship)
        }
        val (champion, backup) = resolveSavedRoutes(prepared)
        val savedRoutes = listOfNotNull(champion, backup).distinctBy(AdaptiveSavedRoute::id)
        if (savedRoutes.isNotEmpty()) {
            val savedRows = savedRoutes.map { routeSpeedRowFromSaved(it, prepared) }
            val savedIds = savedRows.map(RouteSpeedRow::candidateId)
            val extras = rows.filter { it.usable && it.candidateId !in savedIds }
            return (savedRows + extras).sortedWith(routeRankingComparator())
        }
        return buildLoadableRouteListFromDetails(prepared)
    }

    private fun buildLoadableRouteListFromDetails(prepared: RouteSpeedTestPlan): List<RouteSpeedRow> {
        val details = savedRouteDetails?.takeIf { it.profileId == prepared.profile.id } ?: return emptyList()
        return listOfNotNull(details.champion, details.backup)
            .distinctBy(SavedRouteDetails::id)
            .map { routeSpeedRowFromSavedDetails(it, prepared) }
            .sortedWith(routeRankingComparator())
    }

    private fun routeSpeedRowFromSavedDetails(
        details: SavedRouteDetails,
        prepared: RouteSpeedTestPlan,
    ): RouteSpeedRow {
        val existing = rows.firstOrNull { it.candidateId == details.id }
        if (existing != null && existing.usable) return existing
        val candidate = prepared.candidates.firstOrNull { it.id == details.id }
        val base = candidate?.let(::initialRow) ?: RouteSpeedRow(
            candidateId = details.id,
            label = details.label,
            route = "${details.edge}  •  ${details.resolver}  •  ${details.fragment}  •  MTU ${details.mtu}",
            edgeKey = details.edge,
            resolverKey = details.resolver,
            fragmentKey = details.fragment,
            mtu = details.mtu,
        )
        return base.copy(
            status = RouteSpeedStatus.PASSED,
            stageReached = RouteTournamentStage.CHAMPIONSHIP,
            successfulSamples = 1,
            detail = "Saved route profile",
        )
    }

    private fun resolveSavedRoutes(prepared: RouteSpeedTestPlan): Pair<AdaptiveSavedRoute?, AdaptiveSavedRoute?> {
        val network = prepared.session.network
        val signature = prepared.signature
        val profile = prepared.profile
        val champion = prepared.savedChampion ?: adaptiveProfileStore.savedRoute(network, profile, signature)
        val backup = prepared.savedBackup ?: adaptiveProfileStore.savedBackupRoute(network, profile, signature)
        return champion to backup
    }

    private fun routeSpeedRowFromSaved(
        saved: AdaptiveSavedRoute,
        prepared: RouteSpeedTestPlan,
    ): RouteSpeedRow {
        val existing = rows.firstOrNull { it.candidateId == saved.id }
        if (existing != null && existing.usable) return existing
        val candidate = prepared.candidates.firstOrNull { it.id == saved.id }
        val resolver = AdaptiveDnsResolvers.idFor(saved.resolverUrl)
        val fragment = if (saved.finalmaskEnabled) {
            "${saved.finalmaskPacket}/${saved.maxSplit}/${saved.finalmaskDelayMs}ms"
        } else {
            "Fragment off"
        }
        val base = candidate?.let(::initialRow) ?: RouteSpeedRow(
            candidateId = saved.id,
            label = saved.label,
            route = "${saved.address}:${saved.port}  •  $resolver  •  $fragment  •  MTU ${saved.tunMtu}",
            edgeKey = "${saved.address}:${saved.port}",
            resolverKey = resolver,
            fragmentKey = fragment,
            mtu = saved.tunMtu,
        )
        val throughput = saved.downloadKbps.coerceAtLeast(saved.uploadKbps)
        return base.copy(
            status = RouteSpeedStatus.PASSED,
            stageReached = RouteTournamentStage.CHAMPIONSHIP,
            score = saved.score,
            tournamentScore = saved.score,
            confidence = saved.confidence,
            latencyMs = saved.pingMs,
            p95LatencyMs = saved.pingMs,
            jitterMs = saved.jitterMs,
            throughputKbps = throughput,
            downloadKbps = saved.downloadKbps,
            uploadKbps = saved.uploadKbps,
            mtuValidated = saved.mtuValidated,
            successfulSamples = 1,
            detail = "Saved route profile",
        )
    }

    private fun persistedFinalRouteListRows(): List<RouteSpeedRow> {
        if (rows.isEmpty()) return emptyList()
        val lateStages = listOf(
            RouteTournamentStage.CHAMPIONSHIP,
            RouteTournamentStage.STRESS,
            RouteTournamentStage.STABILITY,
        )
        for (stage in lateStages) {
            val ids = restoreStageIds(stage).orEmpty()
            if (ids.isEmpty()) continue
            val idSet = ids.toHashSet()
            val tested = rows.filter { row ->
                row.candidateId in idSet &&
                    row.observations.any { observation ->
                        observation.stage.ordinal >= stage.ordinal
                    }
            }
            if (tested.isNotEmpty()) return rankedRows(tested)
        }
        val advanced = rows.filter { row ->
            row.usable && row.stageReached.ordinal >= RouteTournamentStage.STABILITY.ordinal
        }
        if (advanced.isNotEmpty()) return rankedRows(advanced)
        return rankedRows(rows.filter(RouteSpeedRow::usable))
    }

    private fun snapshotSourceRows(): List<RouteSpeedRow> = when {
        rows.isNotEmpty() -> rows.toList()
        finalStageRows.isNotEmpty() -> finalStageRows.toList()
        else -> emptyList()
    }

    private fun isSnapshotEligible(row: RouteSpeedRow): Boolean =
        row.observations.isNotEmpty() ||
            row.confidence > 0 ||
            row.successfulSamples > 0 ||
            row.status != RouteSpeedStatus.QUEUED ||
            row.stageReached.ordinal > RouteTournamentStage.QUALIFIER.ordinal

    private fun allStageParticipantIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        RouteTournamentStage.entries
            .filter { it != RouteTournamentStage.COMPLETE }
            .forEach { stage ->
                restoreStageIds(stage).orEmpty().forEach(ids::add)
            }
        if (ids.isEmpty()) {
            preferences.getString(KEY_CANDIDATE_IDS, null)
                .orEmpty()
                .lineSequence()
                .filter(String::isNotBlank)
                .forEach(ids::add)
        }
        return ids
    }

    private fun mergeRouteRows(vararg lists: List<RouteSpeedRow>): List<RouteSpeedRow> =
        lists.asSequence()
            .flatten()
            .distinctBy(RouteSpeedRow::candidateId)
            .sortedWith(routeRankingComparator())
            .toList()

    private fun completeSessionSnapshotRows(): List<RouteSpeedRow> {
        val source = snapshotSourceRows()
        if (source.isEmpty()) return persistedFinalRouteListRows()
        val eligible = source.filter(::isSnapshotEligible)
        if (eligible.isNotEmpty()) return rankedRows(eligible)
        return rankedRows(source)
    }

    private fun persistFinalStageSnapshot(candidateIds: List<String>) {
        val finalists = rows.filter { it.candidateId in candidateIds }
            .sortedWith(routeRankingComparator())
        persistFinalStageSnapshotRows(finalists)
    }

    private fun persistSavedRouteListSnapshot() {
        val ranked = completeSessionSnapshotRows()
        if (ranked.isEmpty()) return
        persistFinalStageSnapshotRows(ranked)
    }

    private fun restoreFinalStageSnapshotForLoad(prepared: RouteSpeedTestPlan): List<RouteSpeedRow> {
        val raw = preferences.getString(finalStageSnapshotKey(prepared), null) ?: return emptyList()
        return runCatching {
            val snapshot = JSONObject(raw)
            val network = prepared.session.network
            if (
                snapshot.optString("profileId") != prepared.profile.id ||
                snapshot.optString("signature") != prepared.signature ||
                snapshot.optString("networkKey") != network.exactStorageKey() ||
                snapshot.optString("carrier") != network.carrier ||
                snapshot.optString("carrierClass") != network.carrierClass
            ) {
                return@runCatching emptyList()
            }
            finalStageHistoryTitle = buildString {
                append(snapshot.optString("profileName", "Previous profile"))
                snapshot.optString("networkLabel", "").takeIf(String::isNotBlank)?.let {
                    append(" • ").append(it)
                }
            }
            val array = snapshot.optJSONArray("rows") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSnapshotRow()?.let(::add)
                }
            }.filter(::isSnapshotEligible).sortedWith(routeRankingComparator())
        }.getOrDefault(emptyList())
    }

    private fun filterLateStageSnapshotRows(rows: List<RouteSpeedRow>): List<RouteSpeedRow> =
        rows.filter { row -> row.stageReached.ordinal >= RouteTournamentStage.STABILITY.ordinal }

    private fun persistFinalStageSnapshotRows(finalists: List<RouteSpeedRow>) {
        if (finalists.isEmpty()) return
        val prepared = plan
        val profile = prepared?.profile ?: profileLibrary.selectedProfile
        val signature = prepared?.signature ?: adaptivePlanner.signature(
            settingsStore.snapshot().validated(),
            profile,
        )
        val network = prepared?.session?.network ?: activeRouteNetwork ?: return
        val snapshot = JSONObject()
            .put("profileId", profile.id)
            .put("profileName", profile.name)
            .put("signature", signature)
            .put("networkKey", network.exactStorageKey())
            .put("carrier", network.carrier)
            .put("carrierClass", network.carrierClass)
            .put("networkLabel", prepared?.let(::buildNetworkLabel) ?: buildNetworkLabelFrom(network))
            .put("savedAt", System.currentTimeMillis())
            .put("tournamentStage", currentStage.name)
            .put("rows", JSONArray().apply { finalists.forEach { put(it.toJson()) } })
        preferences.edit()
            .putString(finalStageSnapshotKey(profile.id, signature, network), snapshot.toString())
            .apply()
        finalStageHistoryAvailable = true
        finalStageHistoryTitle = "${profile.name} • ${prepared?.let(::buildNetworkLabel) ?: buildNetworkLabelFrom(network)}"
    }

    private fun hasFinalStageSnapshot(prepared: RouteSpeedTestPlan): Boolean =
        restoreFinalStageSnapshotForLoad(prepared).isNotEmpty()

    private fun restoreFinalStageSnapshot(prepared: RouteSpeedTestPlan): List<RouteSpeedRow> {
        val raw = preferences.getString(finalStageSnapshotKey(prepared), null) ?: return emptyList()
        return runCatching {
            val snapshot = JSONObject(raw)
            val network = prepared.session.network
            if (
                snapshot.optString("profileId") != prepared.profile.id ||
                snapshot.optString("signature") != prepared.signature ||
                snapshot.optString("networkKey") != network.exactStorageKey() ||
                snapshot.optString("carrier") != network.carrier ||
                snapshot.optString("carrierClass") != network.carrierClass
            ) {
                return@runCatching emptyList()
            }
            finalStageHistoryTitle = buildString {
                append(snapshot.optString("profileName", "Previous profile"))
                snapshot.optString("networkLabel", "").takeIf(String::isNotBlank)?.let {
                    append(" • ").append(it)
                }
            }
            val array = snapshot.optJSONArray("rows") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSnapshotRow()?.let(::add)
                }
            }.filter(::isSnapshotEligible).sortedWith(routeRankingComparator())
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.toSnapshotRow(): RouteSpeedRow? = runCatching {
        val observations = optJSONArray("observations")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toObservation()?.let(::add)
                }
            }
        }.orEmpty()
        RouteSpeedRow(
            candidateId = optString("candidateId"),
            label = optString("label", "Saved finalist"),
            route = optString("route"),
            edgeKey = optString("edgeKey"),
            resolverKey = optString("resolverKey"),
            fragmentKey = optString("fragmentKey"),
            mtu = optInt("mtu", 1_280),
            status = runCatching { RouteSpeedStatus.valueOf(optString("status")) }
                .getOrDefault(RouteSpeedStatus.PASSED),
            stageReached = runCatching { RouteTournamentStage.valueOf(optString("stageReached")) }
                .getOrDefault(RouteTournamentStage.CHAMPIONSHIP),
            score = optInt("score"),
            tournamentScore = optInt("tournamentScore"),
            confidence = optInt("confidence"),
            latencyMs = optLongOrNull("latency"),
            p95LatencyMs = optLongOrNull("p95Latency"),
            jitterMs = optLongOrNull("jitter"),
            dnsLatencyMs = optLongOrNull("dnsLatency"),
            payloadBytes = optInt("payload"),
            throughputKbps = optLong("throughput"),
            httpSucceeded = optInt("httpSucceeded"),
            httpAttempted = optInt("httpAttempted"),
            dnsSucceeded = optBoolean("dnsSucceeded"),
            dnsSuccessCount = optInt("dnsSuccessCount"),
            successfulSamples = optInt("successfulSamples"),
            observations = observations,
            failureFingerprint = optString("failureFingerprint", "Saved Championship finalist"),
            detail = optString("detail", "Loaded from previous Championship"),
            uploadBytes = optInt("uploadBytes", 0),
            downloadBytes = optInt("downloadBytes", 0),
            uploadKbps = optLong("uploadKbps", 0L),
            downloadKbps = optLong("downloadKbps", 0L),
            transferSuccessCount = optInt("transferSuccessCount", 0),
            endpointFailureCount = optInt("endpointFailureCount", 0),
            txDelta = optLong("txDelta", 0L),
            rxDelta = optLong("rxDelta", 0L),
            nativeSampleCount = optInt("nativeSampleCount", 0),
            mtuValidated = optBoolean("mtuValidated", false),
        )
    }.getOrNull()

    private fun RouteObservation.toJson() = JSONObject()
        .put("stage", stage.name)
        .put("accepted", accepted)
        .put("score", score)
        .put("latency", latencyMs ?: JSONObject.NULL)
        .put("dnsLatency", dnsLatencyMs ?: JSONObject.NULL)
        .put("payload", payloadBytes)
        .put("throughput", throughputKbps)
        .put("httpSucceeded", httpSucceeded)
        .put("httpAttempted", httpAttempted)
        .put("dnsSucceeded", dnsSucceeded)
        .put("detail", detail.take(MAX_PERSISTED_DETAIL))
        .put("failure", failureFingerprint)
        .put("uploadBytes", uploadBytes)
        .put("downloadBytes", downloadBytes)
        .put("uploadKbps", uploadKbps)
        .put("downloadKbps", downloadKbps)
        .put("jitter", jitterMs ?: JSONObject.NULL)
        .put("transferValidated", transferValidated)
        .put("endpointFailure", endpointFailure)
        .put("txDelta", txDelta)
        .put("rxDelta", rxDelta)
        .put("mtuValidated", mtuValidated)

    private fun JSONObject.toObservation(): RouteObservation? = runCatching {
        RouteObservation(
            stage = RouteTournamentStage.valueOf(optString("stage", RouteTournamentStage.QUALIFIER.name)),
            accepted = optBoolean("accepted", false),
            score = optInt("score", 0),
            latencyMs = optLongOrNull("latency"),
            dnsLatencyMs = optLongOrNull("dnsLatency"),
            payloadBytes = optInt("payload", 0),
            throughputKbps = optLong("throughput", 0L),
            httpSucceeded = optInt("httpSucceeded", 0),
            httpAttempted = optInt("httpAttempted", 0),
            dnsSucceeded = optBoolean("dnsSucceeded", false),
            detail = optString("detail", "").take(MAX_PERSISTED_DETAIL),
            failureFingerprint = optString("failure", "Connectivity probe rejected the route"),
            uploadBytes = optInt("uploadBytes", 0),
            downloadBytes = optInt("downloadBytes", optInt("payload", 0)),
            uploadKbps = optLong("uploadKbps", 0L),
            downloadKbps = optLong("downloadKbps", optLong("throughput", 0L)),
            jitterMs = optLongOrNull("jitter"),
            transferValidated = optBoolean("transferValidated", false),
            endpointFailure = optBoolean("endpointFailure", false),
            txDelta = optLong("txDelta", 0L),
            rxDelta = optLong("rxDelta", 0L),
            mtuValidated = optBoolean("mtuValidated", false),
        )
    }.getOrNull()

    private fun RouteSpeedProbeResult.toObservation(
        stage: RouteTournamentStage,
        failure: String,
    ) = RouteObservation(
        stage = stage,
        accepted = accepted,
        score = score,
        latencyMs = latencyMs,
        dnsLatencyMs = dnsLatencyMs,
        payloadBytes = payloadBytes,
        throughputKbps = throughputKbps,
        httpSucceeded = httpSucceeded,
        httpAttempted = httpAttempted,
        dnsSucceeded = dnsSucceeded,
        detail = error ?: detail,
        failureFingerprint = failure,
        uploadBytes = uploadBytes,
        downloadBytes = downloadBytes,
        uploadKbps = uploadKbps,
        downloadKbps = downloadKbps,
        jitterMs = jitterMs,
        transferValidated = transferValidated,
        endpointFailure = endpointFailure != null,
        txDelta = txDelta,
        rxDelta = rxDelta,
        mtuValidated = mtuValidated,
    )

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun clearPersistedRows() {
        val ids = preferences.getString(KEY_CANDIDATE_IDS, null).orEmpty().lineSequence().filter(String::isNotBlank)
        val editor = preferences.edit()
        ids.forEach { editor.remove(rowKey(it)) }
        RouteTournamentStage.entries.forEach { editor.remove(stageIdsKey(it)) }
        editor.remove(KEY_PROFILE_ID)
            .remove(KEY_SIGNATURE)
            .remove(KEY_NETWORK_KEY)
            .remove(KEY_DISCOVERY_ID)
            .remove(KEY_CANDIDATE_IDS)
            .remove(KEY_STAGE)
            .remove(KEY_SESSION_EXISTS)
            .apply()
    }

    private fun updateRow(candidateId: String, transform: (RouteSpeedRow) -> RouteSpeedRow) {
        val index = rows.indexOfFirst { it.candidateId == candidateId }
        if (index >= 0) rows[index] = transform(rows[index])
    }

    private fun buildNetworkLabel(plan: RouteSpeedTestPlan): String =
        buildNetworkLabelFrom(plan.session.network)

    private fun buildNetworkLabelFrom(network: NetworkFingerprint): String {
        val provider = network.networkProvider
            .takeUnless { it.isBlank() || it == "unknown" || it == network.carrierClass }
            ?: network.carrier.takeUnless { it.isBlank() || it == "unknown" }
        return buildString {
            append(network.transport.replaceFirstChar(Char::uppercase))
            provider?.let { append(" • ").append(it) }
            if (network.networkAsn.isNotBlank() && network.networkAsn != "unknown") {
                append(" • AS").append(network.networkAsn)
            }
        }
    }

    private fun buildSavedRouteDetails(
        prepared: RouteSpeedTestPlan,
        champion: SavedRouteDetails?,
        backup: SavedRouteDetails?,
    ): SavedRouteProfileDetails? = buildSavedRouteDetails(
        profile = prepared.profile,
        settings = prepared.session.settings,
        network = prepared.session.network,
        champion = champion,
        backup = backup,
    )

    private fun buildSavedRouteDetails(
        profile: ProxyProfile,
        settings: AdvancedSettingsData,
        network: NetworkFingerprint,
        champion: SavedRouteDetails?,
        backup: SavedRouteDetails?,
    ): SavedRouteProfileDetails? {
        if (champion == null) return null

        val runtime = profile.runtimeIdentity(settings)

        return SavedRouteProfileDetails(
            profileName = profile.name,
            profileId = profile.id,
            profileType = if (profile.isBuiltIn) "Built-in" else "Custom",
            protocol = runtime.protocol.wireName.uppercase(),
            server = "${profile.serverHost}:${profile.serverPort}",
            transport = runtime.network.ifBlank { "tcp" },
            security = runtime.security.ifBlank { "none" },
            sni = runtime.sni.ifBlank { "Not set" },
            host = runtime.host.ifBlank { "Not set" },
            path = runtime.path.ifBlank { "Not set" },
            alpn = runtime.alpn.ifBlank { "Not set" },
            fingerprint = runtime.fingerprint.ifBlank { "Not set" },
            networkTransport = network.transport,
            carrier = network.carrier.ifBlank { "Unknown" },
            carrierClass = network.carrierClass.ifBlank { "unknown" },
            provider = network.networkProvider.ifBlank { "Unknown" },
            asn = network.networkAsn
                .takeUnless { it.isBlank() || it == "unknown" }
                ?.let { "AS$it" }
                ?: "Unknown",
            networkFingerprint = network.exactStorageKey(),
            networkMtu = network.mtu,
            metered = network.metered,
            validated = network.validated,
            ipSupport = when {
                network.hasIpv4 && network.hasIpv6 -> "IPv4 + IPv6"
                network.hasIpv4 -> "IPv4"
                network.hasIpv6 -> "IPv6"
                else -> "Unknown"
            },
            champion = champion,
            backup = backup,
        )
    }

    private fun AdaptiveSavedRoute.toSavedRouteDetails() = SavedRouteDetails(
        id = id,
        label = label,
        edge = "$address:$port",
        role = role,
        resolver = AdaptiveDnsResolvers.idFor(resolverUrl),
        fragment = if (finalmaskEnabled) {
            "$finalmaskPacket/$maxSplit/${finalmaskDelayMs}ms • length $finalmaskLength"
        } else {
            "Fragment off"
        },
        mtu = tunMtu,
        directCompat = directCompat,
    )

    private fun RouteSpeedRow.toAdaptiveRouteMetrics() = AdaptiveRouteMetrics(
        score = score,
        pingMs = latencyMs,
        jitterMs = jitterMs,
        uploadKbps = uploadKbps,
        downloadKbps = downloadKbps,
        confidence = confidence,
        mtuValidated = mtuValidated,
    )

    private fun AdaptiveCandidate.toSavedRouteDetails() = SavedRouteDetails(
        id = id,
        label = label,
        edge = "${edge.address}:${edge.port}",
        role = edge.role,
        resolver = AdaptiveDnsResolvers.idFor(settings.dnsResolverUrl),
        fragment = if (runtimeOptions.finalmaskEnabled) {
            "${settings.finalmaskPacket}/${edge.finalmaskMaxSplit}/${settings.finalmaskDelayMs}ms • length ${settings.finalmaskLength}"
        } else {
            "Fragment off"
        },
        mtu = settings.tunMtu,
        directCompat = runtimeOptions.preserveTransportFields || id.contains("direct-compat", ignoreCase = true),
    )

    private data class RouteSelectionContext(
        val profile: ProxyProfile,
        val network: NetworkFingerprint,
        val signature: String,
    )

    private fun routeSelectionContext(): RouteSelectionContext? {
        val prepared = plan
        if (prepared != null) {
            return RouteSelectionContext(
                profile = prepared.profile,
                network = prepared.session.network,
                signature = prepared.signature,
            )
        }
        val network = activeRouteNetwork ?: return null
        val profile = profileLibrary.selectedProfile
        val settings = settingsStore.snapshot().validated()
        return RouteSelectionContext(
            profile = profile,
            network = network,
            signature = adaptivePlanner.signature(settings, profile),
        )
    }

    private fun resolveCandidateForSelection(
        context: RouteSelectionContext,
        row: RouteSpeedRow,
        settings: AdvancedSettingsData,
    ): AdaptiveCandidate? {
        plan?.candidates?.firstOrNull { it.id == row.candidateId }?.let { return it }
        findStoredSavedRoute(context, row.candidateId)?.let { saved ->
            return adaptivePlanner.candidateFromSavedRoute(saved, settings, context.profile)
        }
        return candidateFromRouteRow(row, settings, context.profile)
    }

    private fun findStoredSavedRoute(
        context: RouteSelectionContext,
        candidateId: String,
    ): AdaptiveSavedRoute? {
        val champion = adaptiveProfileStore.savedRoute(
            context.network,
            context.profile,
            context.signature,
        )
        val backup = adaptiveProfileStore.savedBackupRoute(
            context.network,
            context.profile,
            context.signature,
        )
        return when (candidateId) {
            champion?.id -> champion
            backup?.id -> backup
            else -> null
        }
    }

    private fun candidateFromRouteRow(
        row: RouteSpeedRow,
        base: AdvancedSettingsData,
        profile: ProxyProfile,
    ): AdaptiveCandidate {
        val (address, port) = parseEdgeKey(row.edgeKey)
        val resolver = AdaptiveDnsResolvers.all.firstOrNull { it.id == row.resolverKey } ?: AdaptiveDnsResolvers.CLOUDFLARE
        val fragmentOff = row.fragmentKey.equals("Fragment off", ignoreCase = true)
        val fragmentParts = row.fragmentKey.substringBefore(" •").split('/')
        val maxSplit = if (fragmentOff) {
            1
        } else {
            fragmentParts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10_000) ?: 2
        }
        val delayMs = if (fragmentOff) {
            0
        } else {
            fragmentParts.getOrNull(2)?.removeSuffix("ms")?.toIntOrNull()?.coerceIn(0, 60_000) ?: 0
        }
        val packet = fragmentParts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: base.finalmaskPacket
        val parsedIdentity = when {
            profile.usesAdvancedSettingsIdentity() -> null
            LocalForwardProfile.isLocalForward(profile) -> LocalForwardProfile.routingIdentity(profile, base)
            else -> DirectCompatProfileParser.parse(profile)?.identity ?: profile.runtimeIdentity(base)
        }
        return AdaptiveCandidate(
            id = row.candidateId,
            label = row.label,
            edge = MciEdge(
                address = address,
                port = port,
                role = "saved-route",
                finalmaskMaxSplit = maxSplit,
            ),
            settings = base.copy(
                dnsResolverUrl = resolver.url,
                finalmaskPacket = packet,
                finalmaskDelayMs = delayMs,
                tunMtu = row.mtu,
            ).validated(),
            runtimeOptions = MciXrayRuntimeOptions(
                identityOverride = parsedIdentity,
                finalmaskEnabled = !fragmentOff,
                preserveEmptyAlpn = !profile.usesAdvancedSettingsIdentity(),
                preserveTransportFields = !profile.usesAdvancedSettingsIdentity(),
            ),
        )
    }

    private fun parseEdgeKey(edgeKey: String): Pair<String, Int> {
        val lastColon = edgeKey.lastIndexOf(':')
        require(lastColon > 0) { "Invalid edge key: $edgeKey" }
        val address = edgeKey.substring(0, lastColon)
        val port = edgeKey.substring(lastColon + 1).toIntOrNull() ?: 443
        return address to port
    }

    private fun RouteSpeedRow.toSavedRouteDetails() = SavedRouteDetails(
        id = candidateId,
        label = label,
        edge = edgeKey,
        role = "saved-route",
        resolver = resolverKey,
        fragment = fragmentKey,
        mtu = mtu,
        directCompat = candidateId.contains("direct-compat", ignoreCase = true) ||
            candidateId.contains("matrix-direct", ignoreCase = true),
    )

    private fun startKeepAliveService() {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, RouteSpeedTestService::class.java).setAction(RouteSpeedTestService.ACTION_START),
        )
    }

    private fun stopKeepAliveService() {
        appContext.stopService(Intent(appContext, RouteSpeedTestService::class.java))
    }

    private fun rowKey(candidateId: String) = "$KEY_ROW_PREFIX$candidateId"
    private fun stageIdsKey(stage: RouteTournamentStage) = "$KEY_STAGE_IDS_PREFIX${stage.name}"

    private fun finalStageSnapshotKey(prepared: RouteSpeedTestPlan): String =
        finalStageSnapshotKey(
            prepared.profile.id,
            prepared.signature,
            prepared.session.network,
        )

    private fun finalStageSnapshotKey(
        profileId: String,
        signature: String,
        network: NetworkFingerprint,
    ): String {
        val identity = listOf(
            profileId,
            signature,
            network.exactStorageKey(),
            network.carrier,
            network.carrierClass,
        ).joinToString("\u001F")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "$KEY_FINAL_STAGE_SNAPSHOT_PREFIX$digest"
    }

    private fun Throwable.shortMessage(): String =
        message?.substringBefore('\n')?.take(120)?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun RouteTournamentStage.next(): RouteTournamentStage = when (this) {
        RouteTournamentStage.QUALIFIER -> RouteTournamentStage.VERIFICATION
        RouteTournamentStage.VERIFICATION -> RouteTournamentStage.MTU_VALIDATION
        RouteTournamentStage.MTU_VALIDATION -> RouteTournamentStage.STABILITY
        RouteTournamentStage.STABILITY -> RouteTournamentStage.STRESS
        RouteTournamentStage.STRESS -> RouteTournamentStage.CHAMPIONSHIP
        RouteTournamentStage.CHAMPIONSHIP,
        RouteTournamentStage.COMPLETE -> RouteTournamentStage.COMPLETE
    }

    private fun RouteTournamentStage.previous(): RouteTournamentStage? = when (this) {
        RouteTournamentStage.QUALIFIER -> null
        RouteTournamentStage.VERIFICATION -> RouteTournamentStage.QUALIFIER
        RouteTournamentStage.MTU_VALIDATION -> RouteTournamentStage.VERIFICATION
        RouteTournamentStage.STABILITY -> RouteTournamentStage.MTU_VALIDATION
        RouteTournamentStage.STRESS -> RouteTournamentStage.STABILITY
        RouteTournamentStage.CHAMPIONSHIP -> RouteTournamentStage.STRESS
        RouteTournamentStage.COMPLETE -> RouteTournamentStage.CHAMPIONSHIP
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle] else values[middle - 1] + (values[middle] - values[middle - 1]) / 2
    }

    private fun percentile95(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val index = (ceil(values.size * 0.95).toInt() - 1).coerceIn(0, values.lastIndex)
        return values[index]
    }

    companion object {
        private const val PREFERENCES_NAME = "route_speed_test_session_v3"
        private const val KEY_SESSION_EXISTS = "session_exists"
        private const val KEY_RUN_REQUESTED = "run_requested"
        private const val KEY_TEST_PROFILE_ID = "test_profile_id"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_NETWORK_KEY = "network_key"
        private const val KEY_DISCOVERY_ID = "discovery_id"
        private const val KEY_CANDIDATE_IDS = "candidate_ids"
        private const val KEY_STAGE = "tournament_stage"
        private const val KEY_STAGE_IDS_PREFIX = "stage_ids:"
        private const val KEY_ROW_PREFIX = "row:"
        private const val KEY_FINAL_STAGE_SNAPSHOT_PREFIX = "final_stage_snapshot:"
        private const val MAX_PERSISTED_DETAIL = 800
        private const val MIN_STABLE_THROUGHPUT_KBPS = 96L
        @Volatile private var instance: RouteSpeedTestController? = null

        fun get(context: Context): RouteSpeedTestController = instance ?: synchronized(this) {
            instance ?: RouteSpeedTestController(context.applicationContext).also { instance = it }
        }
    }
}
