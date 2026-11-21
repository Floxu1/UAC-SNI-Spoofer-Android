package com.uacspoofer.mobile.engine.direct

import com.uacspoofer.mobile.vpn.AdaptiveDnsResolver
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import com.uacspoofer.mobile.vpn.SocksDnsProbe
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal data class DirectDnsSelection(val resolver: AdaptiveDnsResolver, val latencyMs: Long)

internal class DirectDnsSelector(private val dnsProbe: SocksDnsProbe) {
    suspend fun select(proxy: Proxy): DirectDnsSelection? = coroutineScope {
        resolvers.map { resolver ->
            async {
                var total = 0L
                for (host in probeHosts) {
                    val elapsed = runInterruptible(Dispatchers.IO) { probe(resolver, proxy, host) }
                        ?: return@async null
                    total += elapsed
                }
                DirectDnsSelection(resolver, total / probeHosts.size)
            }
        }.awaitAll().filterNotNull().minByOrNull { it.latencyMs }
    }

    private fun probe(resolver: AdaptiveDnsResolver, proxy: Proxy, host: String): Long? = runCatching {
        // A zero ID follows RFC 8484's recommendation for cache-friendly queries.
        val query = dnsProbe.buildDnsQuery(0, host)
        val conn = URL(resolver.url).openConnection(proxy) as HttpsURLConnection
        val started = System.nanoTime()
        try {
            conn.connectTimeout = 2_500
            conn.readTimeout = 2_500
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setFixedLengthStreamingMode(query.size)
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode != 200 ||
                !conn.contentType.orEmpty().substringBefore(';').trim().equals("application/dns-message", true)
            ) return null
            val response = conn.inputStream.use { input ->
                val bytes = ByteArray(4_097)
                var count = 0
                while (count < bytes.size) {
                    val size = input.read(bytes, count, bytes.size - count)
                    if (size < 0) break
                    count += size
                }
                bytes.copyOf(count)
            }
            if (!answersWithPublicIpv4(query, response)) return null
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    companion object {
        private val probeHosts = listOf("www.google.com", "m.youtube.com")
        private val resolvers = AdaptiveDnsResolvers.all + listOf(
            AdaptiveDnsResolver("rethink-sky", "ReThinkDNS Sky", "https://sky.rethinkdns.com/dns-query", emptyMap()),
            AdaptiveDnsResolver("rethink-max", "ReThinkDNS Max", "https://max.rethinkdns.com/dns-query", emptyMap()),
        )

        internal fun answersWithPublicIpv4(query: ByteArray, response: ByteArray): Boolean = runCatching {
            if (response.size !in query.size..4_096) return false
            val dns = ByteBuffer.wrap(response)
            if (dns.short != ByteBuffer.wrap(query).short) return false
            val flags = dns.short.toInt() and 0xffff
            if (flags and 0xfa0f != 0x8000 || dns.short.toInt() != 1) return false
            val answers = dns.short.toInt() and 0xffff
            if (!(12 until query.size).all { query[it] == response[it] }) return false
            dns.position(query.size)
            repeat(answers) {
                skipName(dns)
                val type = dns.short.toInt()
                val dnsClass = dns.short.toInt()
                dns.int // TTL
                val length = dns.short.toInt() and 0xffff
                if (type == 1 && dnsClass == 1 && length == 4) {
                    val bytes = ByteArray(4).also { dns.get(it) }
                    val addr = InetAddress.getByAddress(bytes)
                    if (!addr.isAnyLocalAddress && !addr.isLoopbackAddress && !addr.isLinkLocalAddress &&
                        !addr.isSiteLocalAddress && !addr.isMulticastAddress && (bytes[0].toInt() and 0xff) in 1..223
                    ) return true
                } else {
                    dns.position(dns.position() + length)
                }
            }
            false
        }.getOrDefault(false)

        private fun skipName(dns: ByteBuffer) {
            while (true) {
                val length = dns.get().toInt() and 0xff
                if (length == 0) return
                if (length and 0xc0 == 0xc0) {
                    val target = (length and 0x3f) * 256 + (dns.get().toInt() and 0xff)
                    require(target < dns.position() - 2)
                    return
                }
                require(length <= 63)
                dns.position(dns.position() + length)
            }
        }
    }
}
