package com.uacspoofer.mobile.engine.tor

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OnionHop reachability check: a live WebTunnel bridge answers HTTP 101 to a
 * WebSocket upgrade on its exact `url=` path. TCP to the dummy bridge IP is useless.
 */
internal object WebTunnelHandshake {
    const val PROBE_TIMEOUT_MS = 3_000
    const val SCAN_BUDGET_MS = 6_000L
    const val WORKERS = 8
    const val MIN_CANDIDATES_TO_SCAN = 4
    const val MAX_PROBE = 48
    const val MAX_LAUNCH = 8

    fun isSwitchingProtocols(statusLine: String): Boolean {
        val parts = statusLine.trim().split(Regex("\\s+"))
        return parts.size >= 2 &&
            parts[0].startsWith("HTTP/", ignoreCase = true) &&
            parts[1] == "101"
    }

    suspend fun rankLive(bridges: List<WebTunnelBridge>): List<WebTunnelBridge> {
        if (bridges.size < MIN_CANDIDATES_TO_SCAN) return bridges
        val slice = bridges.take(MAX_PROBE)
        val live = withContext(Dispatchers.IO) {
            val collected = java.util.Collections.synchronizedList(mutableListOf<Pair<WebTunnelBridge, Int>>())
            withTimeoutOrNull(SCAN_BUDGET_MS) {
                coroutineScope {
                    val limiter = Semaphore(WORKERS)
                    slice.map { bridge ->
                        async {
                            limiter.withPermit {
                                val started = System.currentTimeMillis()
                                val liveBridge = runCatching {
                                    probeOnce(bridge, PROBE_TIMEOUT_MS)
                                }.getOrDefault(false)
                                if (liveBridge) {
                                    collected += bridge to (System.currentTimeMillis() - started).toInt()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            collected.sortedBy { it.second }.map { it.first }
        }
        return live.ifEmpty { bridges }
    }

    internal fun probeOnce(bridge: WebTunnelBridge, timeoutMs: Int): Boolean {
        val uri = runCatching { URI(bridge.url) }.getOrNull() ?: return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        val port = when {
            uri.port > 0 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        val path = buildString {
            append(uri.rawPath.ifBlank { "/" })
            if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery)
        }
        val socket = Socket()
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        try {
            socket.connect(InetSocketAddress(ipv4Preferred(host), port), timeoutMs)
            val streamSocket = if (scheme == "https") wrapTls(socket, host) else socket
            val key = websocketKey()
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: Mozilla/5.0\r\n" +
                "Connection: Upgrade\r\n" +
                "Upgrade: websocket\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "\r\n"
            val output = streamSocket.getOutputStream()
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
            val status = readStatusLine(streamSocket.getInputStream(), timeoutMs)
            return isSwitchingProtocols(status)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun wrapTls(plain: Socket, host: String): SSLSocket {
        val trustAll = arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAll, SecureRandom())
        val ssl = context.socketFactory.createSocket(plain, host, plain.port, true) as SSLSocket
        ssl.soTimeout = plain.soTimeout
        val params = ssl.sslParameters
        runCatching { params.serverNames = listOf(SNIHostName(host)) }
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    private fun readStatusLine(input: java.io.InputStream, timeoutMs: Int): String {
        val buffer = ByteArrayOutputStream()
        val started = System.currentTimeMillis()
        while (buffer.size() < 512 && System.currentTimeMillis() - started < timeoutMs) {
            val next = input.read()
            if (next < 0) break
            buffer.write(next)
            val text = buffer.toString(Charsets.US_ASCII.name())
            if (text.contains("\r\n")) return text.substringBefore("\r\n")
        }
        return buffer.toString(Charsets.US_ASCII.name())
    }

    private fun websocketKey(): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val raw = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val out = StringBuilder(24)
        var index = 0
        while (index < raw.size) {
            val b0 = raw[index].toInt() and 0xff
            val b1 = if (index + 1 < raw.size) raw[index + 1].toInt() and 0xff else 0
            val b2 = if (index + 2 < raw.size) raw[index + 2].toInt() and 0xff else 0
            out.append(table[b0 shr 2])
            out.append(table[((b0 and 3) shl 4) or (b1 shr 4)])
            out.append(if (index + 1 < raw.size) table[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            out.append(if (index + 2 < raw.size) table[b2 and 63] else '=')
            index += 3
        }
        return out.toString()
    }

    private fun ipv4Preferred(host: String): InetAddress {
        val all = InetAddress.getAllByName(host)
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }
}
