package com.uacspoofer.mobile.profiles

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SniProfileProbeResult(
    val latencyMs: Long,
    val country: CountryMetadata,
    val exitIp: String,
    val countrySource: String,
)


class ProfileLatencyTester(context: Context) {
    private val appContext = context.applicationContext

    suspend fun measure(profile: ProxyProfile): Long =
        measureInternal(
            profile = profile,
            probeCount = PROBE_COUNT,
            minSuccessCount = MIN_SUCCESS_COUNT,
            resolveCountry = false,
            probeTimeoutMs = PROBE_TIMEOUT_MS,
        ).latencyMs

    
    suspend fun measureForSniMaker(
        profile: ProxyProfile,
        probeTimeoutMs: Int = MAKER_DEFAULT_TIMEOUT_MS,
    ): SniProfileProbeResult = measureInternal(
        profile = profile,
        probeCount = MAKER_PROBE_COUNT,
        minSuccessCount = MAKER_MIN_SUCCESS_COUNT,
        resolveCountry = true,
        probeTimeoutMs = probeTimeoutMs.coerceIn(MIN_MAKER_TIMEOUT_MS, MAX_MAKER_TIMEOUT_MS),
    )

    private suspend fun measureInternal(
        profile: ProxyProfile,
        probeCount: Int,
        minSuccessCount: Int,
        resolveCountry: Boolean,
        probeTimeoutMs: Int,
    ): SniProfileProbeResult = withContext(Dispatchers.IO) {
        val totalStarted = SystemClock.elapsedRealtime()
        val reservedPort = reservePort()
        try {
        val prepareStarted = SystemClock.elapsedRealtime()
        val base = AdvancedSettingsStore(appContext).snapshot().validated()
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

                check(samples.size >= minSuccessCount) {
                    "Delay test had ${samples.size}/$probeCount successful probes"
                }
                val rawProbeMs = samples.map(ProbeSample::httpProbeMs)
                val reportedLatencyMs = medianSuccessful(rawProbeMs)
                val totalTestMs = SystemClock.elapsedRealtime() - totalStarted
                val exit = if (resolveCountry) {
                    lookupExitCountry(
                        probeSettings.socksAddress,
                        probeSettings.socksPort,
                        probeTimeoutMs.coerceAtMost(COUNTRY_TIMEOUT_MS),
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
        runCatching {
            val json = JSONObject(fetchSmall("https://ipwho.is/?fields=success,ip,country_code,country", proxy, timeoutMs))
            if (json.optBoolean("success", true)) {
                val country = CountryMetadata.resolve(json.optString("country_code"), json.optString("country"))
                if (country.isKnown) return ExitLocation(json.optString("ip"), country, "ipwho.is")
            }
        }
        runCatching {
            val json = JSONObject(fetchSmall("https://api.country.is/", proxy, timeoutMs))
            val country = CountryMetadata.resolve(json.optString("country"), null)
            if (country.isKnown) return ExitLocation(json.optString("ip"), country, "api.country.is")
        }
        runCatching {
            val values = fetchSmall("https://www.cloudflare.com/cdn-cgi/trace", proxy, timeoutMs)
                .lineSequence()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0].trim() to it[1].trim() }
            val country = CountryMetadata.resolve(values["loc"], null)
            if (country.isKnown) return ExitLocation(values["ip"].orEmpty(), country, "cloudflare-trace")
        }
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
        private const val MAKER_PROBE_COUNT = 1
        private const val MAKER_MIN_SUCCESS_COUNT = 1
        private const val MAKER_DEFAULT_TIMEOUT_MS = 2_500
        private const val MIN_MAKER_TIMEOUT_MS = 1_000
        private const val MAX_MAKER_TIMEOUT_MS = 12_000
        private const val COUNTRY_TIMEOUT_MS = 3_500
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
