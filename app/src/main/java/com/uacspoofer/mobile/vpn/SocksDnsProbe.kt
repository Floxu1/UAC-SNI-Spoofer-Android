package com.uacspoofer.mobile.vpn

import android.net.Network
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

data class DnsProbeResult(
    val success: Boolean,
    val server: String,
    val latencyMs: Long? = null,
    val answerCount: Int = 0,
    val rcode: Int = -1,
    val detail: String,
)

class SocksDnsProbe {
    suspend fun verify(
        settings: AdvancedSettingsData,
        totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
        socketTimeoutMs: Int = SOCKET_TIMEOUT_MS,
    ): DnsProbeResult =
        withTimeoutOrNull(totalTimeoutMs.coerceAtLeast(500L)) {
            val started = System.nanoTime()
            try {
                val response = runInterruptible(Dispatchers.IO) {
                    query(settings, socketTimeoutMs.coerceAtLeast(250))
                }
                response.copy(
                    latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DnsProbeResult(
                    success = false,
                    server = settings.nativeDns,
                    latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
                    detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }
        } ?: DnsProbeResult(
            success = false,
            server = settings.nativeDns,
            detail = "DNS probe timed out after $totalTimeoutMs ms",
        )

    suspend fun verifyOnNetwork(
        settings: AdvancedSettingsData,
        network: Network,
        totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
        socketTimeoutMs: Int = SOCKET_TIMEOUT_MS,
    ): DnsProbeResult =
        withTimeoutOrNull(totalTimeoutMs.coerceAtLeast(500L)) {
            val started = System.nanoTime()
            try {
                val response = runInterruptible(Dispatchers.IO) {
                    queryOnNetwork(settings, network, socketTimeoutMs.coerceAtLeast(250))
                }
                response.copy(
                    latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DnsProbeResult(
                    success = false,
                    server = settings.nativeDns,
                    latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
                    detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }
        } ?: DnsProbeResult(
            success = false,
            server = settings.nativeDns,
            detail = "DNS probe timed out after $totalTimeoutMs ms",
        )

    private fun query(settings: AdvancedSettingsData, socketTimeoutMs: Int): DnsProbeResult {
        val dnsAddress = InetAddress.getByName(settings.nativeDns)
        val transactionId = SecureRandom().nextInt(65_536)
        val query = buildDnsQuery(transactionId)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(settings.socksAddress, settings.socksPort), socketTimeoutMs)
            socket.soTimeout = socketTimeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            check(input.readUnsignedByte() == 0x05) { "invalid SOCKS version" }
            check(input.readUnsignedByte() == 0x00) { "SOCKS authentication rejected" }
            output.write(byteArrayOf(0x05, 0x01, 0x00, if (dnsAddress.address.size == 4) 0x01 else 0x04))
            output.write(dnsAddress.address)
            output.writeShort(53)
            output.flush()
            check(input.readUnsignedByte() == 0x05) { "invalid SOCKS connect version" }
            val reply = input.readUnsignedByte()
            check(reply == 0x00) { "SOCKS DNS connect failed code=$reply" }
            input.readUnsignedByte()
            readAddress(input, settings.socksAddress)
            input.readUnsignedShort()
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val responseLength = input.readUnsignedShort()
            check(responseLength in 12..4_096) { "invalid DNS TCP response length=$responseLength" }
            val responseBytes = ByteArray(responseLength).also(input::readFully)
            val dns = ByteBuffer.wrap(responseBytes).order(ByteOrder.BIG_ENDIAN)
            val responseId = dns.short.toInt() and 0xffff
            val flags = dns.short.toInt() and 0xffff
            dns.short
            val answers = dns.short.toInt() and 0xffff
            check(responseId == transactionId) { "DNS transaction mismatch" }
            val rcode = flags and 0x0f
            val response = flags and 0x8000 != 0
            val success = response && rcode == 0 && answers > 0
            return DnsProbeResult(
                success = success,
                server = settings.nativeDns,
                answerCount = answers,
                rcode = rcode,
                detail = "transport=tcp-over-socks resolver=${AdaptiveDnsResolvers.idFor(settings.dnsResolverUrl)} " +
                    "server=${settings.nativeDns} response=$response rcode=$rcode answers=$answers",
            )
        }
    }

    private fun queryOnNetwork(
        settings: AdvancedSettingsData,
        network: Network,
        socketTimeoutMs: Int,
    ): DnsProbeResult {
        val dnsAddress = InetAddress.getByName(settings.nativeDns)
        val transactionId = SecureRandom().nextInt(65_536)
        val query = buildDnsQuery(transactionId)
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(dnsAddress, 53), socketTimeoutMs)
            socket.soTimeout = socketTimeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val responseLength = input.readUnsignedShort()
            check(responseLength in 12..4_096) { "invalid DNS TCP response length=$responseLength" }
            val responseBytes = ByteArray(responseLength).also(input::readFully)
            return parseResponse(
                settings = settings,
                transactionId = transactionId,
                responseBytes = responseBytes,
                transport = "tcp-vpn-network",
            )
        }
    }

    private fun parseResponse(
        settings: AdvancedSettingsData,
        transactionId: Int,
        responseBytes: ByteArray,
        transport: String,
    ): DnsProbeResult {
        val dns = ByteBuffer.wrap(responseBytes).order(ByteOrder.BIG_ENDIAN)
        val responseId = dns.short.toInt() and 0xffff
        val flags = dns.short.toInt() and 0xffff
        dns.short
        val answers = dns.short.toInt() and 0xffff
        check(responseId == transactionId) { "DNS transaction mismatch" }
        val rcode = flags and 0x0f
        val response = flags and 0x8000 != 0
        val success = response && rcode == 0 && answers > 0
        return DnsProbeResult(
            success = success,
            server = settings.nativeDns,
            answerCount = answers,
            rcode = rcode,
            detail = "transport=$transport resolver=${AdaptiveDnsResolvers.idFor(settings.dnsResolverUrl)} " +
                "server=${settings.nativeDns} response=$response rcode=$rcode answers=$answers",
        )
    }

    private fun buildDnsQuery(transactionId: Int): ByteArray {
        val labels = PROBE_HOST.split('.')
        val size = 12 + labels.sumOf { it.toByteArray(Charsets.US_ASCII).size + 1 } + 1 + 4
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(transactionId.toShort())
        buffer.putShort(0x0100.toShort())
        buffer.putShort(1.toShort())
        buffer.putShort(0.toShort())
        buffer.putShort(0.toShort())
        buffer.putShort(0.toShort())
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buffer.put(bytes.size.toByte())
            buffer.put(bytes)
        }
        buffer.put(0.toByte())
        buffer.putShort(1.toShort())
        buffer.putShort(1.toShort())
        return buffer.array()
    }

    private fun readAddress(input: DataInputStream, fallback: String): InetAddress {
        val address = when (val type = input.readUnsignedByte()) {
            0x01 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) })
            0x03 -> {
                val size = input.readUnsignedByte()
                InetAddress.getByName(String(ByteArray(size).also { input.readFully(it) }, Charsets.US_ASCII))
            }
            0x04 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) })
            else -> error("unsupported SOCKS address type=$type")
        }
        return if (address.isAnyLocalAddress) InetAddress.getByName(fallback) else address
    }

    companion object {
        private const val PROBE_HOST = "connectivitycheck.gstatic.com"
        private const val SOCKET_TIMEOUT_MS = 6_000
        private const val TOTAL_TIMEOUT_MS = 7_000L
    }
}
