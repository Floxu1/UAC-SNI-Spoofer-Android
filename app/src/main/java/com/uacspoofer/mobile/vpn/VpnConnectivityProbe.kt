package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.mci.MciConfig
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

data class ProbeResult(
    val success: Boolean,
    val totalBytes: Int,
    val detail: String,
    val latencyMs: Long? = null,
)

class VpnConnectivityProbe(
    private val statsProvider: () -> TunStats,
) {
    
    suspend fun verify(): ProbeResult = verifyTargets(requireAllTargets = true)

    



    suspend fun verifyRuntime(): ProbeResult = verifyTargets(requireAllTargets = false)

    private suspend fun verifyTargets(requireAllTargets: Boolean): ProbeResult =
        withTimeoutOrNull(MciConfig.PROBE_TOTAL_TIMEOUT_MS) {
            val before = statsProvider()
            val nonce = System.nanoTime().toString(16)
            val successes = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val latencySamples = mutableListOf<Long>()
            var total = 0

            for (target in MciConfig.PROBE_TARGETS) {
                val targetStartedNs = System.nanoTime()
                val count = try {
                    runInterruptible(Dispatchers.IO) {
                        downloadProbeBytes("${target.url}?uac_nonce=$nonce")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += "${target.name}: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    continue
                }

                total += count
                if (count >= MciConfig.PROBE_MIN_BYTES_PER_TARGET) {
                    successes += target.name
                    latencySamples += ((System.nanoTime() - targetStartedNs) / 1_000_000L).coerceAtLeast(1L)
                    if (!requireAllTargets) break
                } else {
                    failures += "${target.name}: only $count bytes"
                }
            }

            delay(200L)
            val after = statsProvider()
            val crossedTun = after.hasBidirectionalGrowthSince(before)
            val success = if (requireAllTargets) {
                successes.size == MciConfig.PROBE_TARGETS.size &&
                    total >= MciConfig.PROBE_MIN_TOTAL_BYTES
            } else {
                successes.isNotEmpty()
            }
            ProbeResult(
                success = success,
                totalBytes = total,
                detail = if (success) {
                    val traffic = if (crossedTun) {
                        ", tx=${after.txBytes - before.txBytes}, rx=${after.rxBytes - before.rxBytes}"
                    } else {
                        ""
                    }
                    "targets=${successes.joinToString("+")}, payload=$total$traffic"
                } else {
                    failures.joinToString(" | ").ifBlank {
                        "payload gate $total/${MciConfig.PROBE_MIN_TOTAL_BYTES} bytes"
                    }
                },
                latencyMs = latencySamples.sorted().let { samples ->
                    samples.takeIf { it.isNotEmpty() }?.get(samples.size / 2)
                },
            )
        } ?: ProbeResult(
            success = false,
            totalBytes = 0,
            detail = "probe timed out after ${MciConfig.PROBE_TOTAL_TIMEOUT_MS} ms",
        )

    private fun downloadProbeBytes(url: String): Int {
        val socks = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(
                MciConfig.LOCAL_SOCKS_ADDRESS,
                MciConfig.LOCAL_SOCKS_PORT,
            ),
        )
        val connection = URL(url).openConnection(socks) as HttpsURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
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
                while (total < MciConfig.PROBE_READ_BYTES_PER_TARGET) {
                    val read = input.read(
                        buffer,
                        0,
                        minOf(buffer.size, MciConfig.PROBE_READ_BYTES_PER_TARGET - total),
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
}
