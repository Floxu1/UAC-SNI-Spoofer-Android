package com.uacspoofer.mobile.engine.tor

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Once a day, while Tor is up, ask for fresh WebTunnel bridges *through Tor itself*
 * (Tor Project Moat circumvention API, or a user URL that lists webtunnel lines).
 * Nothing here touches Xray, Cloudflare candidates or PoW.
 */
internal object TorBridgeRefresh {
    const val INTERVAL_MS = 24 * 60 * 60_000L
    const val MOAT_SETTINGS = "https://bridges.torproject.org/moat/circumvention/settings"
    private const val TIMEOUT_MS = 45_000
    private val WEBTUNNEL_STRING = Regex(""""(webtunnel (?:[^"\\]|\\.)+)"""")

    fun due(lastAt: Long, now: Long): Boolean = now - lastAt >= INTERVAL_MS

    fun run(store: TorEngineStore, settings: TorEngineSettings): Int {
        val now = System.currentTimeMillis()
        if (!due(store.lastRefreshAt, now)) return 0
        store.lastRefreshAt = now
        val custom = settings.bridgeRefreshUrl
        val body = runCatching {
            if (custom.isNotBlank()) {
                fetch(custom, settings.socksPort, method = "GET", payload = null)
            } else {
                fetch(
                    MOAT_SETTINGS,
                    settings.socksPort,
                    method = "POST",
                    payload = """{"country":"ir","transports":["webtunnel"]}""",
                )
            }
        }.onFailure {
            AppLogRepository.warning(LogSource.TOR, "Bridge refresh through Tor failed: ${it.message.orEmpty()}")
        }.getOrNull() ?: return 0
        val lines = extract(body)
        if (lines.isEmpty()) {
            AppLogRepository.info(LogSource.TOR, "Bridge refresh returned no webtunnel lines")
            return 0
        }
        val merged = (lines + WebTunnelBridgeParser.parseAll(store.refreshedBridges).map { it.raw })
            .distinctBy { it.lowercase() }
            .take(MAX_KEPT)
        store.refreshedBridges = merged.joinToString("\n")
        AppLogRepository.info(LogSource.TOR, "Bridge refresh added ${lines.size} webtunnel bridge(s); kept ${merged.size}")
        return lines.size
    }

    /** webtunnel lines from Moat JSON (bridge_strings) or a plain text list. */
    fun extract(body: String): List<String> {
        val fromJson = WEBTUNNEL_STRING.findAll(body).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val candidates = fromJson.ifEmpty { body.lines() }
        return candidates.mapNotNull { WebTunnelBridgeParser.parseLine(it)?.raw }.distinct()
    }

    private fun fetch(url: String, socksPort: Int, method: String, payload: String?): String {
        val uri = URI(url)
        val host = requireNotNull(uri.host) { "bad refresh URL" }
        require(uri.scheme.equals("https", ignoreCase = true)) { "refresh URL must be https" }
        val port = if (uri.port > 0) uri.port else 443
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val raw = Socket(proxy)
        raw.soTimeout = TIMEOUT_MS
        raw.connect(InetSocketAddress.createUnresolved(host, port), TIMEOUT_MS)
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, host, port, true) as SSLSocket
        tls.use { socket ->
            socket.startHandshake()
            val path = (uri.rawPath.ifBlank { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
            val bytes = payload?.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("$method $path HTTP/1.0\r\n")
                append("Host: $host\r\n")
                append("User-Agent: Mozilla/5.0\r\n")
                append("Accept: */*\r\n")
                if (bytes != null) {
                    append("Content-Type: application/vnd.api+json\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.US_ASCII))
            if (bytes != null) out.write(bytes)
            out.flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = response.substringBefore("\r\n")
            check(status.contains(" 200")) { "HTTP ${status.take(60)}" }
            return response.substringAfter("\r\n\r\n")
        }
    }

    private const val MAX_KEPT = 60
}