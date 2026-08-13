package com.uacspoofer.mobile.profiles

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.vpn.AdaptiveCandidate
import com.uacspoofer.mobile.vpn.AdaptiveCandidatePlanner
import com.uacspoofer.mobile.vpn.AdaptiveConnectionProbe
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import com.uacspoofer.mobile.vpn.AdaptiveProfileStore
import com.uacspoofer.mobile.vpn.NetworkFingerprint
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver
import com.uacspoofer.mobile.vpn.SocksDnsProbe
import com.uacspoofer.mobile.vpn.TunStats
import com.uacspoofer.mobile.vpn.VpnConnectivityProbe
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
    private val adaptiveProbe = AdaptiveConnectionProbe(
        connectivityProbe = VpnConnectivityProbe { TunStats.ZERO },
        tunConnectivityProbe = VpnConnectivityProbe { TunStats.ZERO },
        dnsProbe = SocksDnsProbe(),
    )

    suspend fun measure(profile: ProxyProfile): Long =
        measureInternal(
            profile = profile,
            probeCount = PROBE_COUNT,
            minSuccessCount = MIN_SUCCESS_COUNT,
            resolveCountry = false,
            probeTimeoutMs = PROBE_TIMEOUT_MS,
            parallelProbes = false,
        ).latencyMs

    suspend fun measureCompatibilityScan(profile: ProxyProfile): SniProfileProbeResult =
        measureInternal(
            profile = profile,
            probeCount = PROBE_COUNT,
            minSuccessCount = MIN_SUCCESS_COUNT,
            resolveCountry = true,
            probeTimeoutMs = PROBE_TIMEOUT_MS,
            parallelProbes = true,
        )

    
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
        private const val PROBE_TIMEOUT_MS = 4_000
        private const val PROBE_COUNT = 5
        private const val MIN_SUCCESS_COUNT = 3
        private const val MAKER_DEFAULT_TIMEOUT_MS = 20_000
        private const val MIN_MAKER_TIMEOUT_MS = 3_000
        private const val MAX_MAKER_TIMEOUT_MS = 30_000
        private const val MIN_ROUTE_BUDGET_MS = 1_000L
        private const val MAX_ROUTE_BUDGET_MS = 7_500L
        private const val SNI_MAKER_WARMUP_MS = 100L
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
