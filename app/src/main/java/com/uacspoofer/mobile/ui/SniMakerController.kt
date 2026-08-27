package com.uacspoofer.mobile.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.ProfileLatencyTester
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.SniCandidateProgress
import com.uacspoofer.mobile.profiles.SniCandidateStage
import com.uacspoofer.mobile.profiles.SubscriptionConfigParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal enum class MakerTestStatus { QUEUED, TESTING, HEALTHY, FAILED }

internal enum class MakerImportSource { SUBSCRIPTION, CLIPBOARD }

internal enum class MakerSortMode {
    ORIGINAL,
    HEALTHY_FIRST,
    FAILED_FIRST;

    fun next(): MakerSortMode = when (this) {
        ORIGINAL -> HEALTHY_FIRST
        HEALTHY_FIRST -> FAILED_FIRST
        FAILED_FIRST -> ORIGINAL
    }
}

internal data class MakerConfigRow(
    val profile: ProxyProfile,
    val displayUri: String = ProfileUriParser.canonicalUri(profile),
    val status: MakerTestStatus = MakerTestStatus.QUEUED,
    val latencyMs: Long? = null,
    
    
    val country: CountryMetadata = CountryMetadata.UNKNOWN,
    val error: String = "",
    val marked: Boolean = false,
    val candidateId: String = "",
    val candidateLabel: String = "",
    val candidateIndex: Int = 0,
    val candidateCount: Int = 0,
    val candidateStage: SniCandidateStage? = null,
    val candidateRoute: String = "",
    val candidateDetail: String = "",
)





internal class SniMakerController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val tester = ProfileLatencyTester(appContext)
    private val profileStore = ProfileStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receiveJob: Job? = null
    private var testJob: Job? = null
    private var saveJob: Job? = null
    private var testGeneration = 0
    private var fullClipboardPayload: String? = null

    val rows = mutableStateListOf<MakerConfigRow>()
    var subscriptionUrl by mutableStateOf(preferences.getString(KEY_URL, DEFAULT_SUBSCRIPTION_URL) ?: DEFAULT_SUBSCRIPTION_URL)
        private set
    var pastedConfigs by mutableStateOf("")
        private set
    var notice by mutableStateOf("Add a subscription URL or paste configurations")
        private set
    var loading by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var importSource by mutableStateOf(MakerImportSource.SUBSCRIPTION)
        private set
    var sortMode by mutableStateOf(MakerSortMode.HEALTHY_FIRST)
        private set
    var workerCount by mutableIntStateOf(
        preferences.getInt(KEY_WORKERS, DEFAULT_WORKERS).coerceIn(MIN_WORKERS, MAX_WORKERS),
    )
        private set
    var timeoutMs by mutableIntStateOf(
        preferences.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
    )
        private set

    init {
        if (!preferences.getBoolean(KEY_ADAPTIVE_TEST_MIGRATION, false)) {
            if (preferences.getInt(KEY_WORKERS, LEGACY_DEFAULT_WORKERS) == LEGACY_DEFAULT_WORKERS) {
                workerCount = DEFAULT_WORKERS
            }
            if (preferences.getInt(KEY_TIMEOUT, LEGACY_DEFAULT_TIMEOUT_MS) == LEGACY_DEFAULT_TIMEOUT_MS) {
                timeoutMs = DEFAULT_TIMEOUT_MS
            }
            preferences.edit()
                .putInt(KEY_WORKERS, workerCount)
                .putInt(KEY_TIMEOUT, timeoutMs)
                .putBoolean(KEY_ADAPTIVE_TEST_MIGRATION, true)
                .apply()
        }
    }

    val healthyCount: Int get() = rows.count { it.status == MakerTestStatus.HEALTHY }
    val failedCount: Int get() = rows.count { it.status == MakerTestStatus.FAILED }
    val testingCount: Int get() = rows.count { it.status == MakerTestStatus.TESTING }
    val hasSelectedInput: Boolean
        get() = when (importSource) {
            MakerImportSource.SUBSCRIPTION -> subscriptionUrl.isNotBlank()
            MakerImportSource.CLIPBOARD -> (fullClipboardPayload ?: pastedConfigs).isNotBlank()
        }

    fun selectImportSource(source: MakerImportSource) {
        importSource = source
    }

    fun updateSubscriptionUrl(value: String) {
        importSource = MakerImportSource.SUBSCRIPTION
        subscriptionUrl = value.take(MAX_URL_CHARS)
    }

    fun resetSubscriptionUrl() {
        importSource = MakerImportSource.SUBSCRIPTION
        subscriptionUrl = DEFAULT_SUBSCRIPTION_URL
        preferences.edit().putString(KEY_URL, DEFAULT_SUBSCRIPTION_URL).apply()
        notice = "Default subscription URL restored"
    }

    fun updatePastedConfigs(value: String) {
        importSource = MakerImportSource.CLIPBOARD
        if (value.length > MAX_EDITOR_CHARS) {
            fullClipboardPayload = value.take(MAX_PASTE_CHARS)
            pastedConfigs = clipboardPreview(fullClipboardPayload.orEmpty())
        } else {
            fullClipboardPayload = null
            pastedConfigs = value
        }
    }

    fun loadClipboard(value: String) {
        importSource = MakerImportSource.CLIPBOARD
        val input = value.take(MAX_PASTE_CHARS)
        fullClipboardPayload = input.takeIf { it.length > MAX_EDITOR_CHARS }
        pastedConfigs = if (fullClipboardPayload != null) clipboardPreview(input) else input
        notice = if (fullClipboardPayload != null) {
            "Large clipboard loaded safely • ${input.length} characters"
        } else {
            "Clipboard loaded"
        }
    }

    fun updateWorkerCount(value: Int) {
        workerCount = value.coerceIn(MIN_WORKERS, MAX_WORKERS)
        persistTestSettings()
    }

    fun updateTimeoutMs(value: Int) {
        timeoutMs = normalizeTimeout(value)
        persistTestSettings()
    }

    fun resetTestSettings() {
        workerCount = DEFAULT_WORKERS
        timeoutMs = DEFAULT_TIMEOUT_MS
        persistTestSettings()
    }

    fun cycleStatusSort() {
        sortMode = sortMode.next()
    }

    fun visibleRows(): List<MakerConfigRow> = when (sortMode) {
        MakerSortMode.ORIGINAL -> rows.toList()
        MakerSortMode.HEALTHY_FIRST -> rows.inStatusOrder(
            MakerTestStatus.HEALTHY,
            MakerTestStatus.TESTING,
            MakerTestStatus.QUEUED,
            MakerTestStatus.FAILED,
        )
        MakerSortMode.FAILED_FIRST -> rows.inStatusOrder(
            MakerTestStatus.FAILED,
            MakerTestStatus.TESTING,
            MakerTestStatus.QUEUED,
            MakerTestStatus.HEALTHY,
        )
    }

    fun receiveConfigs() {
        if (loading || testing || saving || !hasSelectedInput) return
        receiveJob?.cancel()
        loading = true
        val selectedSource = importSource
        val subscriptionSnapshot = subscriptionUrl.trim()
        val clipboardSnapshot = fullClipboardPayload ?: pastedConfigs
        notice = when (selectedSource) {
            MakerImportSource.SUBSCRIPTION -> "Receiving subscription configurations…"
            MakerImportSource.CLIPBOARD -> "Decoding clipboard configurations…"
        }
        if (selectedSource == MakerImportSource.SUBSCRIPTION) {
            preferences.edit().putString(KEY_URL, subscriptionSnapshot).apply()
        }
        receiveJob = scope.launch {
            try {
                val selectedInput = when (selectedSource) {
                    MakerImportSource.SUBSCRIPTION -> readSubscription(subscriptionSnapshot)
                    MakerImportSource.CLIPBOARD -> clipboardSnapshot
                }
                val result = withContext(Dispatchers.Default) { SubscriptionConfigParser.parse(selectedInput) }
                val currentProfiles = rows.map(MakerConfigRow::profile)
                val currentRowCount = rows.size
                val existingKeys = withContext(Dispatchers.Default) {
                    currentProfiles.mapTo(HashSet(currentProfiles.size * 2), ::profileIdentityKey)
                }
                val unseenProfiles = withContext(Dispatchers.Default) {
                    val seen = existingKeys.toMutableSet()
                    result.profiles.asSequence()
                        .map(::prepareSniMakerProfile)
                        .filter { profile -> seen.add(profileIdentityKey(profile)) }
                        .toList()
                }
                val newProfiles = unseenProfiles.take((MAX_TOTAL_ROWS - currentRowCount).coerceAtLeast(0))
                newProfiles.chunked(LOAD_BATCH_SIZE).forEach { batch ->
                    rows.addAll(batch.map(::MakerConfigRow))
                    yield()
                }
                val duplicateCount = (result.profiles.size - unseenProfiles.size).coerceAtLeast(0)
                val limitSkippedCount = unseenProfiles.size - newProfiles.size
                notice = buildString {
                    append("${newProfiles.size} new configurations added | ${rows.size} total")
                    if (duplicateCount > 0) append(" | $duplicateCount duplicates kept once")
                    if (limitSkippedCount > 0) append(" | $limitSkippedCount skipped by list limit")
                    if (result.decodedPayloadCount > 0) append(" • ${result.decodedPayloadCount} Base64 decoded")
                    if (result.invalidCount > 0) append(" • ${result.invalidCount} invalid skipped")
                    if (result.truncated) append(" • input limit reached")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notice = "Receive failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    fun toggleTests() {
        if (rows.isEmpty() || loading || saving) return
        if (testing) {
            testJob?.cancel()
            return
        }
        val generation = ++testGeneration
        val workersSnapshot = workerCount
        val timeoutSnapshot = timeoutMs
        testing = true
        val resetRows = rows.map { row ->
            val unresolvedProfile = row.profile.copy(country = CountryMetadata.UNKNOWN)
            row.copy(
                profile = unresolvedProfile,
                displayUri = ProfileUriParser.canonicalUri(unresolvedProfile),
                status = MakerTestStatus.QUEUED,
                latencyMs = null,
                error = "",
                country = CountryMetadata.UNKNOWN,
                candidateId = "",
                candidateLabel = "",
                candidateIndex = 0,
                candidateCount = 0,
                candidateStage = null,
                candidateRoute = "",
                candidateDetail = "",
            )
        }
        rows.clear()
        rows.addAll(resetRows)
        notice = "Preparing adaptive test for ${rows.size} configs…"
        testJob = scope.launch {
            try {
                val session = withContext(Dispatchers.IO) { tester.prepareSniMakerSession() }
                val preferredCandidate = AtomicReference(session.initialPreferredCandidateId)
                notice = "Deep Adaptive Test | ${rows.size} configs | $workersSnapshot workers | ${timeoutSnapshot}ms"
                coroutineScope {
                    val queue = Channel<Int>(workersSnapshot * 2)
                    val workers = List(workersSnapshot) {
                        launch(Dispatchers.IO) {
                            for (index in queue) {
                                val startingRow = withContext(Dispatchers.Main.immediate) {
                                    rows[index].also { rows[index] = it.copy(status = MakerTestStatus.TESTING) }
                                }
                                val updated = try {
                                    val result = tester.measureForSniMaker(
                                        profile = startingRow.profile,
                                        session = session,
                                        preferredCandidateId = preferredCandidate.get(),
                                        totalTimeoutMs = timeoutSnapshot,
                                        onCandidateProgress = { progress ->
                                            withContext(Dispatchers.Main.immediate) {
                                                if (generation == testGeneration && index < rows.size &&
                                                    rows[index].profile.id == startingRow.profile.id
                                                ) {
                                                    rows[index] = rows[index].withCandidate(progress)
                                                }
                                            }
                                        },
                                    )
                                    if (result.candidateId.isNotBlank()) {
                                        preferredCandidate.set(result.candidateId)
                                    }
                                    val latestRow = withContext(Dispatchers.Main.immediate) {
                                        rows.getOrNull(index)?.takeIf { it.profile.id == startingRow.profile.id }
                                            ?: startingRow
                                    }
                                    latestRow.copy(
                                        status = MakerTestStatus.HEALTHY,
                                        latencyMs = result.latencyMs,
                                        country = result.country,
                                        profile = startingRow.profile.copy(country = result.country),
                                        displayUri = ProfileUriParser.canonicalUri(
                                            startingRow.profile.copy(country = result.country),
                                        ),
                                        error = "",
                                        candidateId = result.candidateId,
                                        candidateLabel = result.candidateLabel,
                                        candidateIndex = if (result.candidateId.isNotBlank()) 1 else 0,
                                        candidateCount = if (result.candidateId.isNotBlank()) 1 else 0,
                                        candidateStage = SniCandidateStage.PASSED,
                                        candidateDetail = result.probeDetail,
                                        candidateRoute = latestRow.candidateRoute,
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    val latestRow = withContext(Dispatchers.Main.immediate) {
                                        rows.getOrNull(index)?.takeIf { it.profile.id == startingRow.profile.id }
                                            ?: startingRow
                                    }
                                    latestRow.copy(
                                        status = MakerTestStatus.FAILED,
                                        error = error.message.orEmpty().take(120),
                                    )
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    if (index < rows.size) rows[index] = updated
                                }
                            }
                        }
                    }
                    rows.indices.forEach { queue.send(it) }
                    queue.close()
                    workers.joinAll()
                }
                sortMode = MakerSortMode.HEALTHY_FIRST
                notice = "Test complete • $healthyCount healthy • $failedCount failed"
            } catch (_: CancellationException) {
                
                
                if (generation == testGeneration) {
                    val resetActive = rows.map { row ->
                        if (row.status == MakerTestStatus.TESTING) {
                            row.copy(
                                status = MakerTestStatus.QUEUED,
                                candidateStage = null,
                                candidateDetail = "",
                            )
                        } else {
                            row
                        }
                    }
                    rows.clear()
                    rows.addAll(resetActive)
                    notice = "Tests stopped • $healthyCount healthy results kept"
                }
            } catch (error: Throwable) {
                if (generation == testGeneration) {
                    val failed = rows.map { row ->
                        if (row.status == MakerTestStatus.QUEUED || row.status == MakerTestStatus.TESTING) {
                            row.copy(
                                status = MakerTestStatus.FAILED,
                                error = error.message.orEmpty().take(120),
                            )
                        } else {
                            row
                        }
                    }
                    rows.clear()
                    rows.addAll(failed)
                    notice = "Adaptive test setup failed: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                if (generation == testGeneration) testing = false
            }
        }
    }

    
    fun clearResults() {
        ++testGeneration
        receiveJob?.cancel()
        testJob?.cancel()
        saveJob?.cancel()
        receiveJob = null
        testJob = null
        saveJob = null
        loading = false
        testing = false
        saving = false
        rows.clear()
        sortMode = MakerSortMode.HEALTHY_FIRST
        notice = "Results cleared"
    }

    fun saveHealthy() {
        if (saving || loading || testing) return
        val anyMarked = rows.any { it.marked }
        val selected = rows.filter { it.status == MakerTestStatus.HEALTHY && (!anyMarked || it.marked) }
        if (selected.isEmpty()) {
            notice = "No healthy configuration selected"
            return
        }
        saving = true
        notice = "Saving ${selected.size} healthy configurations…"
        saveJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    profileStore.importProfiles(selected.map(MakerConfigRow::profile))
                }
                notice = "${result.importedCount} healthy configurations saved to Configs"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notice = "Save failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                saving = false
            }
        }
    }

    fun toggleMarked(profileId: String) {
        val index = rows.indexOfFirst { it.profile.id == profileId }
        if (index >= 0) rows[index] = rows[index].copy(marked = !rows[index].marked)
    }

    fun toggleAllMarked() {
        val mark = rows.any { !it.marked }
        val updated = rows.map { it.copy(marked = mark) }
        rows.clear()
        rows.addAll(updated)
    }

    override fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun persistTestSettings() {
        preferences.edit()
            .putInt(KEY_WORKERS, workerCount)
            .putInt(KEY_TIMEOUT, timeoutMs)
            .apply()
    }

    private fun normalizeTimeout(value: Int): Int {
        val bounded = value.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        return (bounded / TIMEOUT_STEP_MS) * TIMEOUT_STEP_MS
    }

    private suspend fun readSubscription(rawUrl: String): String = withContext(Dispatchers.IO) {
        val url = URL(rawUrl)
        require(url.protocol == "https" || url.protocol == "http") { "URL must use HTTP or HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = SUBSCRIPTION_CONNECT_TIMEOUT_MS
            connection.readTimeout = SUBSCRIPTION_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "text/plain,*/*")
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/SNI-Maker")
            val code = connection.responseCode
            check(code in 200..299) { "Subscription HTTP $code" }
            connection.inputStream.buffered().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_DOWNLOAD_BYTES) { "Subscription is larger than 24 MB" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun clipboardPreview(value: String): String = buildString {
        append(value.take(MAX_EDITOR_CHARS))
        append("\n\n… ${value.length - MAX_EDITOR_CHARS} more characters loaded; Receive uses all data.")
    }

    private fun MakerConfigRow.withCandidate(progress: SniCandidateProgress): MakerConfigRow = copy(
        candidateId = progress.candidateId,
        candidateLabel = progress.candidateLabel,
        candidateIndex = progress.candidateIndex,
        candidateCount = progress.candidateCount,
        candidateStage = progress.stage,
        candidateRoute = progress.routeSummary,
        candidateDetail = progress.detail,
        error = if (progress.stage == SniCandidateStage.EXHAUSTED) progress.detail else error,
    )

    private fun profileIdentityKey(profile: ProxyProfile): String {
        val direct = DirectCompatProfileParser.parse(profile)
        if (direct != null) {
            val identity = direct.identity
            return listOf(
                identity.protocol.wireName,
                direct.address.lowercase(),
                direct.port.toString(),
                identity.credential,
                identity.network.lowercase(),
                identity.security.lowercase(),
                identity.sni.lowercase(),
                identity.host.lowercase(),
                identity.path,
                identity.alpn.lowercase(),
                identity.fingerprint.lowercase(),
                identity.allowInsecure.toString(),
                identity.flow,
                identity.encryption.lowercase(),
                identity.alterId.toString(),
                identity.serviceName,
                identity.authority.lowercase(),
                identity.xhttpMode.lowercase(),
                identity.xhttpExtra,
                identity.packetEncoding.lowercase(),
            ).joinToString("\u001F")
        }
        return ProfileUriParser.canonicalUri(
            profile.copy(name = "", country = CountryMetadata.UNKNOWN),
        )
    }

    private fun List<MakerConfigRow>.inStatusOrder(vararg order: MakerTestStatus): List<MakerConfigRow> {
        val buckets = Array(MakerTestStatus.entries.size) { ArrayList<MakerConfigRow>() }
        forEach { row -> buckets[row.status.ordinal].add(row) }
        
        
        buckets[MakerTestStatus.HEALTHY.ordinal].sortBy { it.latencyMs ?: Long.MAX_VALUE }
        return buildList(size) { order.forEach { status -> addAll(buckets[status.ordinal]) } }
    }

    companion object {
        const val MIN_WORKERS = 1
        const val MAX_WORKERS = 4
        const val MIN_TIMEOUT_MS = 3_000
        const val MAX_TIMEOUT_MS = 30_000
        const val TIMEOUT_STEP_MS = 1_000
        const val DEFAULT_WORKERS = 3
        const val DEFAULT_TIMEOUT_MS = 20_000

        private const val PREFERENCES_NAME = "sni_config_maker"
        private const val KEY_URL = "subscription_url"
        private const val KEY_WORKERS = "test_workers"
        private const val KEY_TIMEOUT = "test_timeout_ms"
        private const val KEY_ADAPTIVE_TEST_MIGRATION = "adaptive_test_settings_v1"
        private const val LEGACY_DEFAULT_WORKERS = 6
        private const val LEGACY_DEFAULT_TIMEOUT_MS = 8_000
        private const val DEFAULT_SUBSCRIPTION_URL =
            "https://gitverse.ru/api/repos/flaafix/AetrisVPN_Black_list/raw/branch/master/configs.txt"
        private const val LOAD_BATCH_SIZE = 250
        private const val MAX_TOTAL_ROWS = 10_000
        private const val MAX_URL_CHARS = 2_048
        private const val MAX_PASTE_CHARS = 24 * 1024 * 1024
        private const val MAX_EDITOR_CHARS = 64 * 1024
        private const val MAX_DOWNLOAD_BYTES = 24 * 1024 * 1024
        private const val SUBSCRIPTION_CONNECT_TIMEOUT_MS = 8_000
        private const val SUBSCRIPTION_READ_TIMEOUT_MS = 15_000
    }
}
