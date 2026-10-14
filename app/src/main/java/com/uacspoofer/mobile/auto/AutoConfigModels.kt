package com.uacspoofer.mobile.auto

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** How the network filters TLS: by SNI (MCI-like, needs ECH) or by IP (Irancell/Rightel-like). */
enum class DpiType { SNI, IP, UNKNOWN }

/** The three Patterniha shapes of one node, plus anything else. */
enum class AutoVariant { ECH, FM, IPV6, OTHER }

data class AutoCandidate(
    val uri: String,
    val variant: AutoVariant,
    val address: String,
    val nodeKey: String,
    /** Set when this is a clean-IP copy of a Cloudflare config. */
    val cleanIp: Boolean = false,
)

/** Pure parsing/ordering of the Patterniha Free-Configs list (unit-tested). */
object PatternihaList {
    const val RAW_URL = "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"
    const val JSDELIVR_URL = "https://cdn.jsdelivr.net/gh/patterniha/Free-Configs@main/configs.txt"
    const val API_URL = "https://api.github.com/repos/patterniha/Free-Configs/contents/configs.txt"
    val MIRRORS = listOf(RAW_URL, JSDELIVR_URL, API_URL)

    private val ECH_KEYS = setOf("ech", "echcl", "echconfiglist")

    /** The URL itself first; Patterniha URLs fall back to the other mirrors. */
    fun mirrorsFor(url: String): List<String> {
        val clean = url.trim()
        if (!clean.contains("patterniha/Free-Configs", ignoreCase = true)) return listOf(clean)
        return (listOf(clean) + MIRRORS).distinct()
    }

    fun parse(text: String): List<AutoCandidate> = text.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("vless://", ignoreCase = true) || it.startsWith("trojan://", ignoreCase = true) }
        .distinct()
        .mapNotNull(::classify)
        .toList()

    fun classify(uri: String): AutoCandidate? {
        val address = addressOf(uri)?.takeIf(String::isNotBlank) ?: return null
        val query = queryOf(uri)
        val variant = when {
            address.contains(':') -> AutoVariant.IPV6
            ECH_KEYS.any { !query[it].isNullOrBlank() } -> AutoVariant.ECH
            !query["fm"].isNullOrBlank() || !query["cs"].isNullOrBlank() -> AutoVariant.FM
            else -> AutoVariant.OTHER
        }
        val userInfo = uri.substringAfter("://").substringBefore('?').substringBeforeLast('@', "")
        val sni = query["sni"].orEmpty().ifBlank { query["host"].orEmpty() }
        val nodeKey = listOf(uri.substringBefore("://").lowercase(), userInfo, sni.lowercase(), query["path"].orEmpty())
            .joinToString("|")
        return AutoCandidate(uri, variant, address, nodeKey)
    }

    fun echValue(uri: String): String {
        val query = queryOf(uri)
        return ECH_KEYS.firstNotNullOfOrNull { query[it]?.takeIf(String::isNotBlank) }.orEmpty().replace(' ', '+')
    }

    /**
     * MCI-like (SNI filtering): ECH first, then the fragment (fm) variant.
     * Irancell/Rightel-like (IP filtering): fm first (clean-IP copies lead), then ECH.
     * IPv6 only when the network has IPv6. Unknown interleaves ECH and fm.
     */
    fun order(candidates: List<AutoCandidate>, dpi: DpiType, hasIpv6: Boolean, limit: Int): List<AutoCandidate> {
        val pool = candidates.filter { hasIpv6 || it.variant != AutoVariant.IPV6 }
        val groups = pool.groupBy(AutoCandidate::variant).mapValues { (_, list) -> diversify(list) }
        fun g(v: AutoVariant) = groups[v].orEmpty()
        val fm = g(AutoVariant.FM).sortedByDescending { if (dpi == DpiType.IP) it.cleanIp else false }
        val ordered = when (dpi) {
            // Never let one variant fill the whole capped test: interleave, preferred variant first.
            DpiType.SNI -> interleave(g(AutoVariant.ECH), fm) + g(AutoVariant.OTHER) + g(AutoVariant.IPV6)
            DpiType.IP -> interleave(fm, g(AutoVariant.ECH)) + g(AutoVariant.OTHER) + g(AutoVariant.IPV6)
            DpiType.UNKNOWN -> interleave(g(AutoVariant.ECH), fm) + g(AutoVariant.OTHER) + g(AutoVariant.IPV6)
        }
        return ordered.distinctBy(AutoCandidate::uri).take(limit)
    }

    /** Round-robin across servers so one host cannot fill a whole batch. */
    internal fun diversify(list: List<AutoCandidate>): List<AutoCandidate> {
        val byServer = list.groupBy { it.nodeKey.split('|').getOrElse(2) { "" } }.values.map { ArrayDeque(it) }
        val out = ArrayList<AutoCandidate>(list.size)
        while (out.size < list.size) {
            byServer.forEach { queue -> queue.removeFirstOrNull()?.let(out::add) }
        }
        return out
    }

    internal fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            a.getOrNull(i)?.let(::add)
            b.getOrNull(i)?.let(::add)
        }
    }

    fun addressOf(uri: String): String? {
        val afterScheme = uri.substringAfter("://", "").takeIf(String::isNotBlank) ?: return null
        val authority = afterScheme.substringBefore('?').substringBefore('#').substringBefore('/')
        val hostPort = authority.substringAfterLast('@')
        return if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }
    }

    /** Same link dialing [address] instead (clean-IP copy). */
    fun withAddress(uri: String, address: String): String {
        val scheme = uri.substringBefore("://")
        val rest = uri.substringAfter("://")
        val authorityEnd = rest.indexOfFirst { it == '?' || it == '#' || it == '/' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val userInfo = authority.substringBeforeLast('@', "")
        val hostPort = authority.substringAfterLast('@')
        val port = if (hostPort.startsWith("[")) hostPort.substringAfter("]", "") else hostPort.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
        val host = if (address.contains(':')) "[$address]" else address
        val prefix = if (userInfo.isEmpty()) "" else "$userInfo@"
        return "$scheme://$prefix$host$port${rest.substring(authorityEnd)}"
    }

    /** Swaps the ECH value (domain+resolver form) for a fetched base64 ECHConfigList. */
    fun withEchKey(uri: String, key: String): String {
        val encoded = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        return Regex("([?&])(?i:ech|echcl|echconfiglist)=[^&#]*").replace(uri) { m -> "${m.groupValues[1]}ech=$encoded" }
    }

    fun queryOf(uri: String): Map<String, String> {
        val raw = uri.substringAfter('?', "").substringBefore('#')
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val key = decode(part.substringBefore('=')).trim().lowercase()
            val value = decode(part.substringAfter('=', ""))
            if (key.isNotEmpty() && out[key].isNullOrBlank()) out[key] = value
        }
        return out
    }

    fun displayName(uri: String): String =
        decode(uri.substringAfter('#', "")).trim().ifBlank { addressOf(uri).orEmpty() }.take(60)

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}

/** One tested config remembered for a network. [uri] keeps the ECH domain form; keys are fetched at connect. */
data class AutoRankedEntry(
    val uri: String,
    val delayMs: Long,
    val testedAt: Long,
    val failures: Int = 0,
    /** Measured throughput after a connect (0 = not measured yet). */
    val kbps: Long = 0,
)

/** Pure per-network ranking cache logic (unit-tested). */
object AutoRanking {
    const val KEEP = 10
    const val STALE_MS = 24L * 60 * 60 * 1000
    const val MAX_FAILURES = 2

    fun healthy(entries: List<AutoRankedEntry>, now: Long): List<AutoRankedEntry> = entries
        .filter { now - it.testedAt in 0..STALE_MS && it.failures < MAX_FAILURES }
        .sortedBy(::score)

    /**
     * Expected time to first byte plus a 256 KB transfer: combines real delay and real speed.
     * Unmeasured entries assume an average link so fresh results still compete.
     */
    fun score(e: AutoRankedEntry): Long =
        e.delayMs + if (e.kbps > 0) SPEED_SAMPLE_KBIT * 1000 / e.kbps else UNMEASURED_TRANSFER_MS

    const val SPEED_SAMPLE_KBIT = 2048L
    const val UNMEASURED_TRANSFER_MS = 800L

    fun withSpeed(entries: List<AutoRankedEntry>, speeds: Map<String, Long>): List<AutoRankedEntry> =
        entries.map { e -> speeds[e.uri]?.takeIf { it > 0 }?.let { e.copy(kbps = it) } ?: e }

    fun needsBootstrap(entries: List<AutoRankedEntry>, now: Long): Boolean = healthy(entries, now).isEmpty()

    /** Fresh results first (by delay), then still-healthy older ones; top [KEEP]. */
    fun merge(old: List<AutoRankedEntry>, fresh: List<AutoRankedEntry>, now: Long): List<AutoRankedEntry> {
        val freshSorted = fresh.sortedBy(AutoRankedEntry::delayMs)
        val seen = freshSorted.mapTo(HashSet(), AutoRankedEntry::uri)
        val oldSpeed = old.associate { it.uri to it.kbps }
        val carried = freshSorted.map { if (it.kbps == 0L) it.copy(kbps = oldSpeed[it.uri] ?: 0L) else it }
        val kept = healthy(old, now).filter { it.uri !in seen }
        return (carried + kept).take(KEEP)
    }

    fun markFailure(entries: List<AutoRankedEntry>, uri: String): List<AutoRankedEntry> =
        entries.map { if (it.uri == uri) it.copy(failures = it.failures + 1) else it }

    fun markSuccess(entries: List<AutoRankedEntry>, uri: String): List<AutoRankedEntry> =
        entries.map { if (it.uri == uri) it.copy(failures = 0) else it }

    fun encode(entries: List<AutoRankedEntry>): String =
        entries.joinToString("\n") { "${it.delayMs}\t${it.testedAt}\t${it.failures}\t${it.kbps}\t${it.uri}" }

    fun decode(text: String?): List<AutoRankedEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        // v2 lines carry kbps before the uri; v1 lines (build 443/444) do not.
        val v2 = line.split('\t', limit = 5).takeIf { it.size == 5 && it[3].toLongOrNull() != null && it[4].isNotBlank() }
        val parts = v2 ?: line.split('\t', limit = 4)
        if (parts.size < 4 || parts.last().isBlank()) return@mapNotNull null
        AutoRankedEntry(
            uri = parts.last(),
            kbps = if (v2 != null) parts[3].toLong() else 0L,
            delayMs = parts[0].toLongOrNull() ?: return@mapNotNull null,
            testedAt = parts[1].toLongOrNull() ?: return@mapNotNull null,
            failures = parts[2].toIntOrNull() ?: 0,
        )
    }.toList()
}

/** Port of the desktop classify_dpi plus the carrier vote (unit-tested). */
object DpiDecision {
    data class Result(val dpi: DpiType, val confidence: String, val by: String)

    fun classify(reachable: Int, fragOk: Int, carrierClass: String): Result {
        val r = reachable.coerceAtLeast(0)
        val ratio = if (r > 0) fragOk.toDouble() / r else 0.0
        if (r >= 2) {
            val dpi = if (ratio >= 0.34) DpiType.IP else DpiType.SNI
            val confidence = if (ratio >= 0.6 || ratio <= 0.1) "high" else "medium"
            return Result(dpi, confidence, "probe")
        }
        return Result(carrierHint(carrierClass), "low", "carrier")
    }

    /** MCI filters by SNI; Irancell and Rightel filter by IP. */
    fun carrierHint(carrierClass: String): DpiType = when (carrierClass.lowercase()) {
        "mci" -> DpiType.SNI
        "irancell", "rightel" -> DpiType.IP
        else -> DpiType.UNKNOWN
    }
}