package com.uacspoofer.mobile.auto

import android.net.Network
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.Base64Codec
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** DNS wire helpers for the HTTPS (type 65) record that carries the ECH config list (unit-tested). */
object EchDns {
    const val TYPE_HTTPS = 65
    private const val SVC_ECH = 5

    /** ('cloudflare-ech.com', 'udp://1.1.1.1') for the domain+server form; blank domain for raw keys. */
    fun parseEchValue(value: String): Pair<String, String> {
        val v = value.trim()
        if (v.isEmpty() || isBase64List(v)) return "" to ""
        val domain = v.substringBefore('+').trim().trim('.').lowercase()
        if (domain.isEmpty() || '.' !in domain) return "" to ""
        return domain to v.substringAfter('+', "").trim()
    }

    fun hintedServer(hint: String): String? =
        Regex("^(?:udp://)?(\\d{1,3}(?:\\.\\d{1,3}){3})(?::53)?$").find(hint.trim())?.groupValues?.get(1)

    fun isBase64List(value: String): Boolean {
        val v = value.trim()
        if (v.length < 16 || '.' in v || ("+" in v && "://" in v)) return false
        if (!Regex("^[A-Za-z0-9+/]+={0,2}$").matches(v)) return false
        val raw = runCatching { Base64Codec.decode(v) }.getOrNull() ?: return false
        return raw.size >= 4 && (((raw[0].toInt() and 0xff) shl 8) or (raw[1].toInt() and 0xff)) == raw.size - 2
    }

    fun query(domain: String, id: Int = Random.nextInt(0, 0xffff)): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xff); out.write(v and 0xff) }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(1)
        domain.trim('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        u16(TYPE_HTTPS); u16(1)
        // EDNS0 OPT, 1232-byte UDP payload
        out.write(0); u16(41); u16(1232); u16(0); u16(0); u16(0)
        return out.toByteArray()
    }

    /** Base64 ECHConfigList from the first HTTPS answer carrying an `ech` SvcParam, else blank. */
    fun echFromResponse(msg: ByteArray, expectedId: Int? = null): String {
        if (msg.size < 12) return ""
        fun u16(at: Int) = ((msg[at].toInt() and 0xff) shl 8) or (msg[at + 1].toInt() and 0xff)
        if (expectedId != null && u16(0) != expectedId) return ""
        val qd = u16(4)
        val an = u16(6)
        var pos = 12
        fun skipName(start: Int): Int {
            var p = start
            while (p < msg.size) {
                val len = msg[p].toInt() and 0xff
                if (len == 0) return p + 1
                if (len and 0xc0 == 0xc0) return p + 2
                p += len + 1
            }
            return msg.size
        }
        repeat(qd) { pos = skipName(pos) + 4 }
        repeat(an) {
            if (pos >= msg.size) return ""
            pos = skipName(pos)
            if (pos + 10 > msg.size) return ""
            val type = u16(pos)
            val rdLen = u16(pos + 8)
            val rdStart = pos + 10
            val rdEnd = rdStart + rdLen
            if (rdEnd > msg.size) return ""
            if (type == TYPE_HTTPS) {
                var p = skipName(rdStart + 2)
                while (p + 4 <= rdEnd) {
                    val key = u16(p)
                    val len = u16(p + 2)
                    val vStart = p + 4
                    if (vStart + len > rdEnd) break
                    if (key == SVC_ECH && len > 0) {
                        return Base64Codec.encode(msg.copyOfRange(vStart, vStart + len))
                    }
                    p = vStart + len
                }
            }
            pos = rdEnd
        }
        return ""
    }
}

/**
 * Port of the desktop ech_keys.py: fetch the ECH key ourselves (hinted UDP resolver first, then the
 * system resolver and DoH), on sockets bound to the underlying network, 3 s per stage, cache per
 * domain. Falls back to the cached key, and as a last resort hands Xray the original value.
 */
class EchKeyResolver(
    private val store: AutoConfigStore,
    private val network: Network?,
    private val systemDns: List<InetAddress> = emptyList(),
) {
    data class Resolution(val value: String, val state: String, val source: String, val ageMs: Long)

    suspend fun resolve(echValue: String, now: Long = System.currentTimeMillis()): Resolution {
        val value = echValue.trim()
        if (value.isEmpty()) return Resolution("", "none", "", 0)
        if (EchDns.isBase64List(value)) return Resolution(value, "raw", "uri", 0)
        val (domain, hint) = EchDns.parseEchValue(value)
        if (domain.isEmpty()) return Resolution(value, "raw", "uri", 0)
        val cached = store.echKey(domain)
        if (cached != null && now - cached.second in 0..FRESH_MS) {
            return Resolution(cached.first, "cached", "cache", now - cached.second)
        }
        val udpServers = buildList {
            EchDns.hintedServer(hint)?.let(::add)
            addAll(UDP_RESOLVERS)
        }.distinct()
        var result = race(udpServers.map { server -> "udp $server" to suspend { queryUdp(domain, server) } })
        if (result == null) {
            val second = systemDns.take(3).map { addr -> "system ${addr.hostAddress}" to suspend { queryUdp(domain, addr.hostAddress.orEmpty()) } } +
                DOH_RESOLVERS.map { url -> "doh ${url.substringAfter("//").substringBefore('/')}" to suspend { queryDoh(domain, url) } }
            result = race(second)
        }
        if (result != null) {
            store.putEchKey(domain, result.first, now)
            log("ECH KEY: fetched via ${result.second} for $domain")
            return Resolution(result.first, "fresh", result.second, 0)
        }
        if (cached != null) {
            log("ECH KEY: fetch failed; using cached key (age ${(now - cached.second) / 60_000}m) for $domain")
            return Resolution(cached.first, "cached", "cache", now - cached.second)
        }
        log("ECH KEY: fetch failed and no cache for $domain; letting Xray resolve $value")
        return Resolution(value, "none", "", 0)
    }

    private suspend fun race(fetchers: List<Pair<String, suspend () -> String>>): Pair<String, String>? {
        if (fetchers.isEmpty()) return null
        return coroutineScope {
            val results = Channel<Pair<String, String>>(fetchers.size)
            val jobs = fetchers.map { (label, fetch) ->
                launch(Dispatchers.IO) {
                    val key = runCatching { fetch() }.getOrDefault("")
                    results.send(key to label)
                }
            }
            try {
                withTimeoutOrNull(STAGE_DEADLINE_MS) {
                    repeat(fetchers.size) {
                        val (key, label) = results.receive()
                        if (EchDns.isBase64List(key)) return@withTimeoutOrNull key to label
                    }
                    null
                }
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }

    private fun queryUdp(domain: String, server: String): String {
        if (server.isBlank()) return ""
        val id = Random.nextInt(0, 0xffff)
        val query = EchDns.query(domain, id)
        DatagramSocket().use { socket ->
            runCatching { network?.bindSocket(socket) }
            socket.soTimeout = STAGE_DEADLINE_MS.toInt()
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return EchDns.echFromResponse(buffer.copyOf(packet.length), id)
        }
    }

    private fun queryDoh(domain: String, url: String): String {
        val query = EchDns.query(domain, 0)
        val connection = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = STAGE_DEADLINE_MS.toInt()
            connection.readTimeout = STAGE_DEADLINE_MS.toInt()
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            connection.outputStream.use { it.write(query) }
            if (connection.responseCode != 200) return ""
            return EchDns.echFromResponse(connection.inputStream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
    }

    private fun log(message: String) = runCatching { AppLogRepository.info(LogSource.ADAPTIVE, message) }

    companion object {
        val UDP_RESOLVERS = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "9.9.9.9")
        val DOH_RESOLVERS = listOf("https://1.1.1.1/dns-query", "https://dns.google/dns-query")
        const val STAGE_DEADLINE_MS = 3_000L
        /** Cloudflare rotates ECH keys; a key younger than this is used without a refetch. */
        const val FRESH_MS = 60L * 60 * 1000
    }
}