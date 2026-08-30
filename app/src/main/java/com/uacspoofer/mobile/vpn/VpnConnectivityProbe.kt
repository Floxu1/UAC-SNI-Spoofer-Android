package com.uacspoofer.mobile.vpn

import android.net.Network
import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

data class ProbeResult(
    val success: Boolean,
    val totalBytes: Int,
    val detail: String,
    val latencyMs: Long? = null,
    val succeededTargets: Int = 0,
    val attemptedTargets: Int = 0,
    val durationMs: Long = 0L,
    val txDelta: Long = 0L,
    val rxDelta: Long = 0L,
) {
    fun hasSuccessfulPayload(): Boolean = succeededTargets > 0 && totalBytes > 0
}

class VpnConnectivityProbe(
    private val statsProvider: () -> TunStats,
) {
    
    suspend fun verify(): ProbeResult = verifyTargets(
        requireAllTargets = true,
        attemptAllTargets = true,
        socksAddress = MciConfig.LOCAL_SOCKS_ADDRESS,
        socksPort = MciConfig.LOCAL_SOCKS_PORT,
        requireTrafficGrowth = false,
        totalTimeoutMs = MciConfig.PROBE_TOTAL_TIMEOUT_MS,
        readBytesPerTarget = MciConfig.PROBE_READ_BYTES_PER_TARGET,
        network = null,
    )

    



    suspend fun verifyRuntime(
        socksAddress: String = MciConfig.LOCAL_SOCKS_ADDRESS,
        socksPort: Int = MciConfig.LOCAL_SOCKS_PORT,
        totalTimeoutMs: Long = MciConfig.PROBE_TOTAL_TIMEOUT_MS,
        socketTimeoutMs: Int = 4_000,
    ): ProbeResult = verifyTargets(
        requireAllTargets = false,
        attemptAllTargets = false,
        socksAddress = socksAddress,
        socksPort = socksPort,
        requireTrafficGrowth = false,
        totalTimeoutMs = totalTimeoutMs,
        readBytesPerTarget = MciConfig.PROBE_READ_BYTES_PER_TARGET,
        network = null,
        socketTimeoutMs = socketTimeoutMs,
    )

    suspend fun verifyCandidate(
        settings: AdvancedSettingsData,
        totalTimeoutMs: Long = CANDIDATE_TOTAL_TIMEOUT_MS,
        readBytesPerTarget: Int = CANDIDATE_READ_BYTES_PER_TARGET,
    ): ProbeResult = verifyTargets(
        requireAllTargets = false,
        attemptAllTargets = true,
        socksAddress = settings.socksAddress,
        socksPort = settings.socksPort,
        requireTrafficGrowth = false,
        totalTimeoutMs = totalTimeoutMs.coerceAtLeast(500L),
        readBytesPerTarget = readBytesPerTarget.coerceAtLeast(MciConfig.PROBE_MIN_BYTES_PER_TARGET),
        network = null,
    )

    suspend fun verifyScreening(settings: AdvancedSettingsData): ProbeResult = verifyTargets(
        requireAllTargets = false,
        attemptAllTargets = false,
        socksAddress = settings.socksAddress,
        socksPort = settings.socksPort,
        requireTrafficGrowth = false,
        totalTimeoutMs = SCREENING_TOTAL_TIMEOUT_MS,
        readBytesPerTarget = SCREENING_READ_BYTES_PER_TARGET,
        network = null,
    )

    suspend fun verifyConfirmation(settings: AdvancedSettingsData): ProbeResult = verifyTargets(
        requireAllTargets = false,
        attemptAllTargets = false,
        socksAddress = settings.socksAddress,
        socksPort = settings.socksPort,
        requireTrafficGrowth = false,
        totalTimeoutMs = CONFIRMATION_TOTAL_TIMEOUT_MS,
        readBytesPerTarget = CONFIRMATION_READ_BYTES_PER_TARGET,
        network = null,
    )

    suspend fun verifyTunCandidate(network: Network? = null): ProbeResult = verifyTargets(
        requireAllTargets = false,
        attemptAllTargets = false,
        socksAddress = null,
        socksPort = 0,
        requireTrafficGrowth = true,
        totalTimeoutMs = TUN_CANDIDATE_TOTAL_TIMEOUT_MS,
        readBytesPerTarget = CONFIRMATION_READ_BYTES_PER_TARGET,
        network = network,
    )

    suspend fun verifyTunRuntime(network: Network? = null): ProbeResult = verifyTargets(
        requireAllTargets = false,
        attemptAllTargets = false,
        socksAddress = null,
        socksPort = 0,
        requireTrafficGrowth = true,
        totalTimeoutMs = TUN_RUNTIME_TOTAL_TIMEOUT_MS,
        readBytesPerTarget = MciConfig.PROBE_READ_BYTES_PER_TARGET,
        network = network,
    )

    private suspend fun verifyTargets(
        requireAllTargets: Boolean,
        attemptAllTargets: Boolean,
        socksAddress: String?,
        socksPort: Int,
        requireTrafficGrowth: Boolean,
        totalTimeoutMs: Long,
        readBytesPerTarget: Int,
        network: Network?,
        socketTimeoutMs: Int = 4_000,
    ): ProbeResult =
        withTimeoutOrNull(totalTimeoutMs) {
            val startedNs = System.nanoTime()
            val before = statsProvider()
            val nonce = System.nanoTime().toString(16)
            val successes = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val latencySamples = mutableListOf<Long>()
            val outcomes = if (attemptAllTargets) {
                coroutineScope {
                    MciConfig.PROBE_TARGETS.map { target ->
                        async {
                            probeTarget(
                                target.name,
                                target.url,
                                nonce,
                                socksAddress,
                                socksPort,
                                readBytesPerTarget,
                                network,
                                socketTimeoutMs,
                            )
                        }
                    }.awaitAll()
                }
            } else {
                val sequential = mutableListOf<TargetOutcome>()
                for (target in MciConfig.PROBE_TARGETS) {
                    val outcome = probeTarget(
                        target.name,
                        target.url,
                        nonce,
                        socksAddress,
                        socksPort,
                        readBytesPerTarget,
                        network,
                        socketTimeoutMs,
                    )
                    sequential += outcome
                    if (outcome.bytes >= MciConfig.PROBE_MIN_BYTES_PER_TARGET) break
                }
                sequential
            }
            outcomes.forEach { outcome ->
                if (outcome.bytes >= MciConfig.PROBE_MIN_BYTES_PER_TARGET) {
                    successes += outcome.name
                    latencySamples += outcome.latencyMs
                } else {
                    failures += outcome.failure ?: "${outcome.name}: only ${outcome.bytes} bytes"
                }
            }
            val total = outcomes.sumOf(TargetOutcome::bytes)

            if (requireTrafficGrowth) delay(200L)
            val after = statsProvider()
            val crossedTun = after.hasBidirectionalGrowthSince(before)
            val txDelta = (after.txBytes - before.txBytes).coerceAtLeast(0L)
            val rxDelta = (after.rxBytes - before.rxBytes).coerceAtLeast(0L)
            val payloadSuccess = if (requireAllTargets) {
                successes.size == MciConfig.PROBE_TARGETS.size &&
                    total >= MciConfig.PROBE_MIN_TOTAL_BYTES
            } else {
                successes.isNotEmpty()
            }
            val success = payloadSuccess && (!requireTrafficGrowth || crossedTun)
            ProbeResult(
                success = success,
                totalBytes = total,
                detail = if (success) {
                    val traffic = if (crossedTun) {
                        ", tx=$txDelta, rx=$rxDelta"
                    } else {
                        ""
                    }
                    "targets=${successes.joinToString("+")}, payload=$total$traffic"
                } else {
                    buildList {
                        addAll(failures)
                        if (payloadSuccess && requireTrafficGrowth && !crossedTun) {
                            add("direct payload passed; TUN counters unchanged")
                        }
                    }.joinToString(" | ").ifBlank { "payload gate $total/${MciConfig.PROBE_MIN_TOTAL_BYTES} bytes" }
                },
                latencyMs = latencySamples.sorted().let { samples ->
                    samples.takeIf { it.isNotEmpty() }?.get(samples.size / 2)
                },
                succeededTargets = successes.size,
                attemptedTargets = successes.size + failures.size,
                durationMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L),
                txDelta = txDelta,
                rxDelta = rxDelta,
            )
        } ?: ProbeResult(
            success = false,
            totalBytes = 0,
            detail = "probe timed out after $totalTimeoutMs ms",
            durationMs = totalTimeoutMs,
        )

    private suspend fun probeTarget(
        name: String,
        url: String,
        nonce: String,
        socksAddress: String?,
        socksPort: Int,
        readBytes: Int,
        network: Network?,
        socketTimeoutMs: Int,
    ): TargetOutcome {
        val startedNs = System.nanoTime()
        return try {
            val count = runInterruptible(Dispatchers.IO) {
                downloadProbeBytes(
                    url = "$url?uac_nonce=$nonce",
                    socksAddress = socksAddress,
                    socksPort = socksPort,
                    readBytes = readBytes,
                    network = network,
                    socketTimeoutMs = socketTimeoutMs,
                )
            }
            TargetOutcome(
                name = name,
                bytes = count,
                latencyMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L),
                failure = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TargetOutcome(
                name = name,
                bytes = 0,
                latencyMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L),
                failure = "$name: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }
    }

    private fun downloadProbeBytes(
        url: String,
        socksAddress: String?,
        socksPort: Int,
        readBytes: Int,
        network: Network?,
        socketTimeoutMs: Int = 4_000,
    ): Int {
        val target = URL(url)
        val connection = if (socksAddress == null) {
            (network?.openConnection(target) ?: target.openConnection()) as HttpsURLConnection
        } else {
            val socks = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(socksAddress, socksPort),
            )
            target.openConnection(socks) as HttpsURLConnection
        }
        try {
            connection.connectTimeout = socketTimeoutMs
            connection.readTimeout = socketTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.defaultUseCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/0.1")
            val status = connection.responseCode
            check(status in 200..399) { "HTTP $status from $url" }
            return connection.inputStream.use { input ->
                val buffer = ByteArray(256)
                var total = 0
                while (total < readBytes) {
                    val read = input.read(
                        buffer,
                        0,
                        minOf(buffer.size, readBytes - total),
                    )
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                }
                total
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CANDIDATE_TOTAL_TIMEOUT_MS = 8_000L
        private const val CANDIDATE_READ_BYTES_PER_TARGET = 16_384
        private const val SCREENING_TOTAL_TIMEOUT_MS = 3_500L
        private const val SCREENING_READ_BYTES_PER_TARGET = 1_024
        private const val CONFIRMATION_TOTAL_TIMEOUT_MS = 9_000L
        private const val CONFIRMATION_READ_BYTES_PER_TARGET = 1_024
        private const val TUN_CANDIDATE_TOTAL_TIMEOUT_MS = 7_000L
        private const val TUN_RUNTIME_TOTAL_TIMEOUT_MS = 6_000L
    }

    private data class TargetOutcome(
        val name: String,
        val bytes: Int,
        val latencyMs: Long,
        val failure: String?,
    )
}
