package com.uacspoofer.mobile.profiles

import android.net.Network
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class RouteTransferMeasurementMode {
    SOCKS_PROXY,
    DIRECT_NETWORK,
    NATIVE_TUN,
}

enum class RouteTransferPhase {
    LATENCY,
    UPLOAD,
    DOWNLOAD,
}

enum class RouteEndpointFailureKind {
    RATE_LIMITED,
    SERVER_ERROR,
    HTTP_ERROR,
}

data class RouteSocksProxy(
    val address: String,
    val port: Int,
) {
    init {
        require(address.isNotBlank()) { "SOCKS proxy address is blank" }
        require(port in 1..65_535) { "SOCKS proxy port is invalid" }
    }
}

data class RouteTransferProbeConfig(
    val latencySamples: Int = 3,
    val uploadBytes: Int = 64 * 1_024,
    val downloadBytes: Int = 64 * 1_024,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 10_000,
) {
    init {
        require(latencySamples in 2..10) { "Latency sample count must be between 2 and 10" }
        require(uploadBytes in 1..MAX_TRANSFER_BYTES) { "Upload payload size is invalid" }
        require(downloadBytes in 1..MAX_TRANSFER_BYTES) { "Download payload size is invalid" }
        require(connectTimeoutMs in 250..60_000) { "Connect timeout is invalid" }
        require(readTimeoutMs in 250..120_000) { "Read timeout is invalid" }
    }

    companion object {
        const val MAX_TRANSFER_BYTES = 50 * 1_024 * 1_024
    }
}

data class RouteEndpointFailure(
    val phase: RouteTransferPhase,
    val kind: RouteEndpointFailureKind,
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    val message: String,
)

data class RouteTransferProbeResult(
    val success: Boolean,
    val measurementMode: RouteTransferMeasurementMode,
    val latencySamplesMs: List<Long>,
    val latencyMedianMs: Long?,
    val jitterMs: Long?,
    val requestedUploadBytes: Int,
    val uploadBytes: Int,
    val uploadDurationMs: Long,
    val uploadKbps: Long,
    val requestedDownloadBytes: Int,
    val downloadBytes: Int,
    val downloadDurationMs: Long,
    val downloadKbps: Long,
    val byteValidationPassed: Boolean,
    val endpointFailure: RouteEndpointFailure? = null,
    val candidateFailure: String? = null,
) {
    val endpointUnavailable: Boolean
        get() = endpointFailure?.kind?.let { RouteTransferProbe.isEndpointTemporarilyUnavailable(it) } == true
}

class RouteTransferProbe(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun measure(
        measurementMode: RouteTransferMeasurementMode,
        socksProxy: RouteSocksProxy? = null,
        network: Network? = null,
        config: RouteTransferProbeConfig = RouteTransferProbeConfig(),
    ): RouteTransferProbeResult = withContext(Dispatchers.IO) {
        require((measurementMode == RouteTransferMeasurementMode.SOCKS_PROXY) == (socksProxy != null)) {
            "SOCKS_PROXY mode requires a proxy and direct modes must not use one"
        }
        require(socksProxy == null || network == null) {
            "A SOCKS route cannot also be bound to an Android Network"
        }
        val proxy = socksProxy?.let {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(it.address, it.port))
        }
        val latencySamples = ArrayList<Long>(config.latencySamples)
        var upload = DirectionMeasurement.EMPTY
        var download = DirectionMeasurement.EMPTY
        try {
            repeat(config.latencySamples) {
                coroutineContext.ensureActive()
                latencySamples += runInterruptible {
                    measureLatency(proxy, network, config)
                }
            }
            coroutineContext.ensureActive()
            val payload = ByteArray(config.uploadBytes).also(secureRandom::nextBytes)
            upload = runInterruptible {
                measureUpload(proxy, network, config, payload)
            }
            coroutineContext.ensureActive()
            download = runInterruptible {
                measureDownload(proxy, network, config)
            }
            val byteValidation = transferredExactly(config.uploadBytes, upload.bytes) &&
                transferredExactly(config.downloadBytes, download.bytes)
            result(
                success = byteValidation,
                measurementMode = measurementMode,
                config = config,
                latencySamples = latencySamples,
                upload = upload,
                download = download,
                byteValidationPassed = byteValidation,
                candidateFailure = if (byteValidation) null else "Transferred byte count did not match the requested payload",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: EndpointHttpException) {
            result(
                success = false,
                measurementMode = measurementMode,
                config = config,
                latencySamples = latencySamples,
                upload = upload,
                download = download,
                byteValidationPassed = false,
                endpointFailure = error.failure,
            )
        } catch (error: Throwable) {
            result(
                success = false,
                measurementMode = measurementMode,
                config = config,
                latencySamples = latencySamples,
                upload = upload,
                download = download,
                byteValidationPassed = false,
                candidateFailure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trimEnd(),
            )
        }
    }

    private fun measureLatency(proxy: Proxy?, network: Network?, config: RouteTransferProbeConfig): Long {
        val nonce = nanoTime().toString(16)
        val connection = open(
            url = "$CLOUDFLARE_DOWNLOAD_ENDPOINT?bytes=0&uac_nonce=$nonce",
            proxy = proxy,
            network = network,
            config = config,
        )
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            val startedNs = nanoTime()
            val status = connection.responseCode
            val elapsedMs = elapsedMs(startedNs, nanoTime())
            requireHealthyStatus(connection, RouteTransferPhase.LATENCY, status)
            connection.inputStream.use { input ->
                while (input.read() >= 0) Unit
            }
            elapsedMs
        } finally {
            connection.disconnect()
        }
    }

    private fun measureUpload(
        proxy: Proxy?,
        network: Network?,
        config: RouteTransferProbeConfig,
        payload: ByteArray,
    ): DirectionMeasurement {
        val connection = open(CLOUDFLARE_UPLOAD_ENDPOINT, proxy, network, config)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Connection", "close")
            try {
                var transferred = 0
                val output = BufferedOutputStream(connection.outputStream, TRANSFER_BUFFER_BYTES)
                val startedNs = nanoTime()
                output.use { stream ->
                    var offset = 0
                    while (offset < payload.size) {
                        val count = minOf(TRANSFER_BUFFER_BYTES, payload.size - offset)
                        stream.write(payload, offset, count)
                        offset += count
                        transferred += count
                    }
                    stream.flush()
                }
                val status = connection.responseCode
                val durationMs = elapsedMs(startedNs, nanoTime())
                requireHealthyStatus(connection, RouteTransferPhase.UPLOAD, status)
                runCatching {
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(1_024)
                        while (input.read(buffer) >= 0) Unit
                    }
                }
                DirectionMeasurement(
                    bytes = transferred,
                    durationMs = durationMs,
                    kbps = throughputKbps(transferred, durationMs),
                )
            } catch (error: EndpointHttpException) {
                throw error
            } catch (error: Throwable) {
                val status = runCatching { connection.responseCode }.getOrNull()
                if (status != null && status !in 200..299) {
                    requireHealthyStatus(connection, RouteTransferPhase.UPLOAD, status)
                }
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun measureDownload(
        proxy: Proxy?,
        network: Network?,
        config: RouteTransferProbeConfig,
    ): DirectionMeasurement {
        val nonce = nanoTime().toString(16)
        val connection = open(
            url = "$CLOUDFLARE_DOWNLOAD_ENDPOINT?bytes=${config.downloadBytes}&uac_nonce=$nonce",
            proxy = proxy,
            network = network,
            config = config,
        )
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            val status = connection.responseCode
            requireHealthyStatus(connection, RouteTransferPhase.DOWNLOAD, status)
            var transferred = 0
            val startedNs = nanoTime()
            BufferedInputStream(connection.inputStream, TRANSFER_BUFFER_BYTES).use { input ->
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    transferred += read
                    if (transferred > config.downloadBytes) break
                }
            }
            val durationMs = elapsedMs(startedNs, nanoTime())
            DirectionMeasurement(
                bytes = transferred,
                durationMs = durationMs,
                kbps = throughputKbps(transferred, durationMs),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        url: String,
        proxy: Proxy?,
        network: Network?,
        config: RouteTransferProbeConfig,
    ): HttpsURLConnection {
        val target = URL(url)
        val connection = when {
            proxy != null -> target.openConnection(proxy)
            network != null -> network.openConnection(target)
            else -> target.openConnection()
        } as HttpsURLConnection
        return connection.apply {
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            defaultUseCaches = false
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private fun requireHealthyStatus(
        connection: HttpsURLConnection,
        phase: RouteTransferPhase,
        status: Int,
    ) {
        if (status in 200..299) return
        val kind = endpointFailureKind(status)
        val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        throw EndpointHttpException(
            RouteEndpointFailure(
                phase = phase,
                kind = kind,
                statusCode = status,
                retryAfterSeconds = retryAfter,
                message = "Cloudflare speed endpoint returned HTTP $status during ${phase.name.lowercase()}",
            ),
        )
    }

    private fun result(
        success: Boolean,
        measurementMode: RouteTransferMeasurementMode,
        config: RouteTransferProbeConfig,
        latencySamples: List<Long>,
        upload: DirectionMeasurement,
        download: DirectionMeasurement,
        byteValidationPassed: Boolean,
        endpointFailure: RouteEndpointFailure? = null,
        candidateFailure: String? = null,
    ) = RouteTransferProbeResult(
        success = success,
        measurementMode = measurementMode,
        latencySamplesMs = latencySamples.toList(),
        latencyMedianMs = medianMs(latencySamples),
        jitterMs = jitterMs(latencySamples),
        requestedUploadBytes = config.uploadBytes,
        uploadBytes = upload.bytes,
        uploadDurationMs = upload.durationMs,
        uploadKbps = upload.kbps,
        requestedDownloadBytes = config.downloadBytes,
        downloadBytes = download.bytes,
        downloadDurationMs = download.durationMs,
        downloadKbps = download.kbps,
        byteValidationPassed = byteValidationPassed,
        endpointFailure = endpointFailure,
        candidateFailure = candidateFailure,
    )

    private data class DirectionMeasurement(
        val bytes: Int,
        val durationMs: Long,
        val kbps: Long,
    ) {
        companion object {
            val EMPTY = DirectionMeasurement(0, 0L, 0L)
        }
    }

    private class EndpointHttpException(val failure: RouteEndpointFailure) : Exception(failure.message)

    companion object {
        const val CLOUDFLARE_DOWNLOAD_ENDPOINT = "https://speed.cloudflare.com/__down"
        const val CLOUDFLARE_UPLOAD_ENDPOINT = "https://speed.cloudflare.com/__up"
        private const val USER_AGENT = "UAC-SNI-Spoofer-Android/RouteProbe"
        private const val TRANSFER_BUFFER_BYTES = 16 * 1_024

        internal fun transferredExactly(requestedBytes: Int, transferredBytes: Int): Boolean =
            requestedBytes >= 0 && requestedBytes == transferredBytes

        internal fun endpointFailureKind(statusCode: Int): RouteEndpointFailureKind = when {
            statusCode == 429 -> RouteEndpointFailureKind.RATE_LIMITED
            statusCode in 500..599 -> RouteEndpointFailureKind.SERVER_ERROR
            else -> RouteEndpointFailureKind.HTTP_ERROR
        }

        internal fun isEndpointTemporarilyUnavailable(kind: RouteEndpointFailureKind): Boolean =
            kind == RouteEndpointFailureKind.RATE_LIMITED || kind == RouteEndpointFailureKind.SERVER_ERROR

        internal fun throughputKbps(bytes: Int, durationMs: Long): Long {
            if (bytes <= 0 || durationMs <= 0L) return 0L
            return bytes.toLong() * 8L / durationMs
        }

        internal fun medianMs(samples: List<Long>): Long? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2L
            }
        }

        internal fun jitterMs(samples: List<Long>): Long? {
            if (samples.size < 2) return null
            val total = samples.zipWithNext().sumOf { (left, right) ->
                if (left >= right) left - right else right - left
            }
            return total / (samples.size - 1)
        }

        private fun elapsedMs(startedNs: Long, finishedNs: Long): Long =
            ((finishedNs - startedNs).coerceAtLeast(1L) / 1_000_000L).coerceAtLeast(1L)
    }
}
