package com.uacspoofer.mobile.engine.pow

import android.content.Context
import android.os.SystemClock
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Name warm-up for the PoW TUN path.
 *
 * In TUN mode hev answers DNS locally with a fake IP (no network, sub-ms), and
 * the real lookup happens at the Psiphon server when the relay sends SOCKS
 * CONNECT with the host name. The first connection to a new name therefore
 * pays the server's resolver miss on top of the channel round trip. One light
 * pass after connect sends CONNECT host:443 for popular names and the user's
 * own top names, and closes as soon as the server answers - no TLS, no data.
 * .ir names are resolved locally into the relay's direct-route cache instead.
 *
 * Battery: one pass per connect; retunes only re-run after a long gap; Power
 * Save trims the pass to the user's top few names.
 */
internal object PowDnsWarm {
    val POPULAR: List<String> = listOf(
        "google.com",
        "www.google.com",
        "gstatic.com",
        "youtube.com",
        "googlevideo.com",
        "instagram.com",
        "cdninstagram.com",
        "telegram.org",
        "web.telegram.org",
        "whatsapp.net",
        "x.com",
        "twimg.com",
        "github.com",
        "wikipedia.org",
        "cloudflare.com",
    )

    const val TOP_LEARNED = 20
    private const val SESSION_CAP = 512
    private const val PREFS = "uac_pow_dns_warm"
    private const val KEY_TOP = "top_hosts"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val PARALLEL = 6
    private const val START_DELAY_MS = 2_500L
    const val REFRESH_OPTIMIZED_MS = 30 * 60_000L
    const val REFRESH_FULL_MS = 10 * 60_000L

    private val session = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var job: Job? = null
    @Volatile private var lastPassAt = 0L

    /** Lower-case host name suitable for warming, or null (IP literals, junk, single labels). */
    fun normalizeHost(raw: String): String? {
        val host = raw.trim().trimEnd('.').lowercase()
        if (host.length < 4 || host.length > 253) return null
        if (!host.contains('.')) return null
        if (host.all { it.isDigit() || it == '.' }) return null
        if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return null
        if (host.startsWith('.') || host.startsWith('-') || host.contains("..")) return null
        return host
    }

    /** Called by the TUN relay for every SOCKS CONNECT that carries a host name. */
    fun note(raw: String) {
        val host = normalizeHost(raw) ?: return
        val counter = session[host]
        if (counter != null) {
            counter.incrementAndGet()
            return
        }
        if (session.size >= SESSION_CAP) return
        session.putIfAbsent(host, AtomicInteger(1))?.incrementAndGet()
    }

    /** Older counts decay by half so the list follows what the user does now. */
    fun mergeTop(
        stored: Map<String, Int>,
        sessionCounts: Map<String, Int>,
        limit: Int = TOP_LEARNED,
    ): Map<String, Int> {
        val merged = HashMap<String, Int>()
        stored.forEach { (host, count) -> if (count > 0) merged[host] = (count / 2).coerceAtLeast(1) }
        sessionCounts.forEach { (host, count) -> if (count > 0) merged[host] = (merged[host] ?: 0) + count }
        return merged.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .associate { it.key to it.value }
    }

    fun encode(top: Map<String, Int>): String = top.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        raw.split(',').forEach { item ->
            val host = normalizeHost(item.substringBefore('=')) ?: return@forEach
            val count = item.substringAfter('=', "").toIntOrNull() ?: return@forEach
            if (count > 0) out[host] = count
        }
        return out
    }

    /** Hosts for one warm pass, most useful first. */
    fun plan(learned: List<String>, optimized: Boolean, powerSave: Boolean): List<String> {
        val learnedLimit = when {
            powerSave -> 5
            optimized -> 12
            else -> TOP_LEARNED
        }
        val popular = if (powerSave) emptyList() else POPULAR
        return (learned.take(learnedLimit) + popular).distinct()
    }

    fun learned(context: Context): Map<String, Int> =
        decode(prefs(context).getString(KEY_TOP, null))

    /** Folds this session's names into the saved top list. Cheap; safe to call on every stop. */
    fun persist(context: Context) {
        val snapshot = session.mapValues { it.value.get() }
        if (snapshot.isEmpty()) return
        session.clear()
        val merged = mergeTop(learned(context), snapshot)
        prefs(context).edit().putString(KEY_TOP, encode(merged)).apply()
        AppLogRepository.info(
            LogSource.POW,
            "DNS warm: learned ${snapshot.size} names this session; saved top ${merged.size}",
        )
    }

    fun kick(
        scope: CoroutineScope,
        context: Context,
        socksPort: Int,
        optimized: Boolean,
        powerSave: Boolean,
        force: Boolean,
    ) {
        if (socksPort <= 0) return
        val now = SystemClock.elapsedRealtime()
        val gap = if (optimized) REFRESH_OPTIMIZED_MS else REFRESH_FULL_MS
        if (!force && lastPassAt > 0L && now - lastPassAt < gap) {
            AppLogRepository.debug(
                LogSource.POW,
                "DNS warm: skipped (last pass ${(now - lastPassAt) / 60_000L} min ago)",
            )
            return
        }
        val appContext = context.applicationContext
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            delay(START_DELAY_MS)
            lastPassAt = SystemClock.elapsedRealtime()
            val saved = learned(appContext)
            val learnedHosts = saved.keys.toList()
            val hosts = plan(learnedHosts, optimized, powerSave)
            val (domestic, remote) = hosts.partition(PowSocksConnectOnly::isDomesticHost)
            val started = SystemClock.elapsedRealtime()
            val localOk = domestic.count { PowSocksConnectOnly.prefetchDomestic(it) }
            val gate = Semaphore(PARALLEL)
            val results = remote.map { host ->
                async { gate.withPermit { connectOnly(socksPort, host) } }
            }.awaitAll()
            val ok = results.count { it }
            val learnedUsed = hosts.count { it in saved }
            AppLogRepository.info(
                LogSource.POW,
                "DNS warm: $ok/${remote.size} names primed at the Psiphon exit" +
                    (if (domestic.isNotEmpty()) ", $localOk/${domestic.size} .ir resolved locally" else "") +
                    " in ${SystemClock.elapsedRealtime() - started}ms " +
                    "(learned $learnedUsed, popular ${hosts.size - learnedUsed}" +
                    (if (powerSave) ", power save" else "") + ")",
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun resetSchedule() {
        lastPassAt = 0L
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** SOCKS5 CONNECT host:443 and close once the server replies. */
    private fun connectOnly(socksPort: Int, host: String): Boolean = runCatching {
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.soTimeout = CONNECT_TIMEOUT_MS
            sock.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val auth = readExact(input, 2)
            if (auth[0] != 0x05.toByte() || auth[1] != 0x00.toByte()) return@use false
            val name = host.toByteArray(Charsets.US_ASCII)
            val request = ByteArray(7 + name.size)
            request[0] = 0x05
            request[1] = 0x01
            request[2] = 0x00
            request[3] = 0x03
            request[4] = name.size.toByte()
            System.arraycopy(name, 0, request, 5, name.size)
            request[5 + name.size] = 0x01
            request[6 + name.size] = 0xBB.toByte()
            output.write(request)
            output.flush()
            val head = readExact(input, 2)
            head[1] == 0x00.toByte()
        }
    }.getOrDefault(false)

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read <= 0) error("socks eof")
            offset += read
        }
        return buffer
    }
}
