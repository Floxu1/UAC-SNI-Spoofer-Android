package com.uacspoofer.mobile.vpn

import android.content.Context
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.pow.PowCoreConfig
import com.uacspoofer.mobile.engine.pow.PowLiveExit
import com.uacspoofer.mobile.engine.pow.PowUiProbeGate
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.delay

/**
 * Latency probes.
 *
 * The home chip uses HTTPS `generate_204` through the local SOCKS proxy so the
 * number matches a typical browser-style fetch. The first TLS handshake is
 * discarded so the chip does not publish the cold sample. Config delay tests
 * keep the SOCKS5 HTTP/80 path in [measure].
 */
internal object LivePing {
    const val INTERVAL_MS = 5_000L
    const val SETTLE_MS = 1_200L
    const val QUIET_MS = 400L
    const val DEFER_POLL_MS = 250L
    const val TIMEOUT_MS = 5_000
    const val TOR_TIMEOUT_MS = 12_000

    data class Target(val host: String, val path: String, val port: Int = 80)

    val CHIP_URLS = listOf(
        "https://www.google.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "https://1.1.1.1/cdn-cgi/trace",
    )

    val TARGETS = listOf(
        Target("cp.cloudflare.com", "/generate_204"),
        Target("www.gstatic.com", "/generate_204"),
        Target("www.google.com", "/generate_204"),
        Target("1.1.1.1", "/cdn-cgi/trace"),
    )

    fun shouldDefer(context: Context): Boolean {
        if (!EngineModeStore.get(context).snapshot().isPow) return false
        return PowUiProbeGate.shouldDeferUiPing()
    }

    fun timeoutMs(engine: EngineMode): Int = if (engine.isTor) TOR_TIMEOUT_MS else TIMEOUT_MS

    fun socksEndpoint(context: Context): Pair<String, Int> {
        val engine = EngineModeStore.get(context).snapshot()
        val advanced = AdvancedSettingsStore(context).snapshot()
        val proxy = advanced.connectionMode == CONNECTION_MODE_PROXY
        return when {
            engine.isPow -> "127.0.0.1" to if (proxy) advanced.socksPort else PowLiveExit.tunSocksPort()
            engine.isTor -> "127.0.0.1" to TorEngineStore.get(context).snapshot().socksPort
            else -> advanced.socksAddress.ifBlank { "127.0.0.1" } to advanced.socksPort
        }
    }

    fun measure(context: Context): Long? {
        val engine = EngineModeStore.get(context).snapshot()
        val (host, port) = socksEndpoint(context)
        return measureChip(host, port, timeoutMs(engine))
    }

    suspend fun awaitChipSlot(context: Context, stillWanted: () -> Boolean) {
        while (stillWanted() && shouldDefer(context)) delay(DEFER_POLL_MS)
        if (!stillWanted()) return
        delay(QUIET_MS)
        while (stillWanted() && shouldDefer(context)) delay(DEFER_POLL_MS)
    }

    fun measureChip(socksHost: String, socksPort: Int, timeoutMs: Int): Long? {
        for (url in CHIP_URLS) {
            measureChipUrl(url, socksHost, socksPort, timeoutMs) ?: continue
            measureChipUrl(url, socksHost, socksPort, timeoutMs)?.let { return it }
        }
        return null
    }

    fun measure(socksHost: String, socksPort: Int, timeoutMs: Int): Long? {
        for (target in TARGETS) {
            measureOne(target, socksHost, socksPort, timeoutMs)?.let { return it }
        }
        return null
    }

    fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun measureChipUrl(
        url: String,
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int,
    ): Long? = runCatching {
        val started = System.nanoTime()
        val connection = URL(url).openConnection(
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)),
        ) as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            check(connection.responseCode in 200..399) { "HTTP ${connection.responseCode}" }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun measureOne(
        target: Target,
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int,
    ): Long? = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = readExact(input, 2)
            check(greeting[0] == 0x05.toByte() && greeting[1] == 0x00.toByte()) { "socks auth" }
            output.write(connectRequest(target.host, target.port))
            output.flush()
            val head = readExact(input, 4)
            check(head[1] == 0x00.toByte()) { "socks connect" }
            when (head[3].toInt() and 0xff) {
                0x01 -> readExact(input, 6)
                0x04 -> readExact(input, 18)
                0x03 -> {
                    val len = readExact(input, 1)[0].toInt() and 0xff
                    readExact(input, len + 2)
                }
                else -> error("socks atyp")
            }
            val request = buildString {
                append("GET ").append(target.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(target.host).append("\r\n")
                append("User-Agent: Mozilla/5.0\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
            val status = readStatusLine(input)
            val code = statusLineCode(status)
            check(code in 200..399) { "HTTP $status" }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        }
    }.getOrNull()

    internal fun connectRequest(host: String, port: Int): ByteArray {
        return if (isIpv4Literal(host)) {
            val octets = host.split('.').map { it.toInt().toByte() }
            byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                octets[0], octets[1], octets[2], octets[3],
                ((port shr 8) and 0xff).toByte(),
                (port and 0xff).toByte(),
            )
        } else {
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            ByteArray(7 + hostBytes.size).also { request ->
                request[0] = 0x05
                request[1] = 0x01
                request[2] = 0x00
                request[3] = 0x03
                request[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
                request[5 + hostBytes.size] = ((port shr 8) and 0xff).toByte()
                request[6 + hostBytes.size] = (port and 0xff).toByte()
            }
        }
    }

    internal fun statusLineCode(statusLine: String): Int {
        val parts = statusLine.trim().split(' ')
        return parts.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun readStatusLine(input: InputStream): String {
        val builder = StringBuilder(64)
        while (builder.length < 128) {
            val byte = input.read()
            check(byte >= 0) { "http eof" }
            if (byte == '\n'.code) return builder.toString()
            if (byte != '\r'.code) builder.append(byte.toChar())
        }
        return builder.toString()
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            check(read > 0) { "socks eof" }
            offset += read
        }
        return buffer
    }
}
