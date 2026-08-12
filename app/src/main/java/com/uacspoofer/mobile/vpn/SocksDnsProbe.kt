package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
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
    suspend fun verify(settings: AdvancedSettingsData): DnsProbeResult =
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val started = System.nanoTime()
            try {
                val response = runInterruptible(Dispatchers.IO) { query(settings) }
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
            detail = "DNS probe timed out after $TOTAL_TIMEOUT_MS ms",
        )

    private fun query(settings: AdvancedSettingsData): DnsProbeResult {
        val dnsAddress = InetAddress.getByName(settings.nativeDns)
        val transactionId = SecureRandom().nextInt(65_536)
        val query = buildDnsQuery(transactionId)
        Socket().use { control ->
            control.connect(InetSocketAddress(settings.socksAddress, settings.socksPort), SOCKET_TIMEOUT_MS)
            control.soTimeout = SOCKET_TIMEOUT_MS
            val input = DataInputStream(control.getInputStream())
            val output = DataOutputStream(control.getOutputStream())
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            check(input.readUnsignedByte() == 0x05) { "invalid SOCKS version" }
            check(input.readUnsignedByte() == 0x00) { "SOCKS authentication rejected" }
            output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            output.flush()
            check(input.readUnsignedByte() == 0x05) { "invalid UDP associate version" }
            val reply = input.readUnsignedByte()
            check(reply == 0x00) { "SOCKS UDP associate failed code=$reply" }
            input.readUnsignedByte()
            val relayAddress = readAddress(input, settings.socksAddress)
            val relayPort = input.readUnsignedShort()
            check(relayPort > 0) { "SOCKS returned invalid UDP relay port" }
            val packet = buildSocksUdpPacket(dnsAddress, query)
            DatagramSocket().use { datagram ->
                datagram.soTimeout = SOCKET_TIMEOUT_MS
                datagram.send(DatagramPacket(packet, packet.size, relayAddress, relayPort))
                val buffer = ByteArray(4_096)
                val incoming = DatagramPacket(buffer, buffer.size)
                datagram.receive(incoming)
                val dnsOffset = socksUdpPayloadOffset(buffer, incoming.length)
                check(incoming.length - dnsOffset >= 12) { "short DNS response" }
                val dns = ByteBuffer.wrap(buffer, dnsOffset, incoming.length - dnsOffset)
                    .order(ByteOrder.BIG_ENDIAN)
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
                    detail = "server=${settings.nativeDns} response=$response rcode=$rcode answers=$answers",
                )
            }
        }
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

    private fun buildSocksUdpPacket(address: InetAddress, payload: ByteArray): ByteArray {
        val raw = address.address
        val type = if (raw.size == 4) 0x01 else 0x04
        return ByteBuffer.allocate(3 + 1 + raw.size + 2 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0.toByte())
            .put(0.toByte())
            .put(0.toByte())
            .put(type.toByte())
            .put(raw)
            .putShort(53.toShort())
            .put(payload)
            .array()
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

    private fun socksUdpPayloadOffset(packet: ByteArray, length: Int): Int {
        check(length >= 4) { "short SOCKS UDP response" }
        check(packet[2].toInt() == 0) { "fragmented SOCKS UDP response" }
        return when (packet[3].toInt() and 0xff) {
            0x01 -> 10
            0x03 -> 7 + (packet[4].toInt() and 0xff)
            0x04 -> 22
            else -> error("invalid SOCKS UDP response address")
        }.also { offset -> check(length > offset) { "short SOCKS UDP payload" } }
    }

    companion object {
        private const val PROBE_HOST = "connectivitycheck.gstatic.com"
        private const val SOCKET_TIMEOUT_MS = 5_500
        private const val TOTAL_TIMEOUT_MS = 6_500L
    }
}
