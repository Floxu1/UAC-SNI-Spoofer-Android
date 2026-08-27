package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.Network
import android.os.Build
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.LocalForwardProfile
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.RuntimeProxyIdentity
import com.uacspoofer.mobile.profiles.TlsAlpnResolver
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray

internal enum class CloudflareEdgeSource {
    CURRENT,
    ORIGINAL,
    DNS_SERVER,
    DNS_SNI,
    DNS_HOST,
    SAVED_EXACT,
    SAVED_ASN,
    SAVED_CARRIER,
    OFFICIAL_CIDR,
}

internal enum class CloudflareSuitability {
    ELIGIBLE,
    UNKNOWN,
    INELIGIBLE,
}

internal data class CloudflareSuitabilityDecision(
    val status: CloudflareSuitability,
    val reason: String,
)

internal data class CloudflareRangeSnapshot(
    val etag: String,
    val ranges: List<IpCidr>,
    val fetchedAtMs: Long,
    val source: String,
)

internal data class CloudflareEdgeHistory(
    val address: String,
    val port: Int,
    val source: CloudflareEdgeSource = CloudflareEdgeSource.SAVED_EXACT,
    val score: Int = 0,
    val lastSuccessAtMs: Long = 0L,
)

internal data class CloudflareTlsProbeSpec(
    val serverName: String,
    val applicationProtocols: List<String>,
    val strictCertificate: Boolean,
)

internal data class CloudflareEdgePreflightResult(
    val tcpSucceeded: Boolean,
    val tlsAttempted: Boolean,
    val tlsSucceeded: Boolean,
    val tcpLatencyMs: Long? = null,
    val tlsLatencyMs: Long? = null,
    val negotiatedAlpn: String? = null,
    val alpnObservable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    val detail: String,
) {
    companion object {
        fun unresolved(detail: String) = CloudflareEdgePreflightResult(
            tcpSucceeded = false,
            tlsAttempted = false,
            tlsSucceeded = false,
            detail = detail,
        )
    }
}

internal data class CloudflareEdgeCandidate(
    val key: String,
    val address: String,
    val port: Int,
    val ip: IpAddress?,
    val sources: Set<CloudflareEdgeSource>,
    val reserved: Boolean,
    val historyScore: Int = 0,
    val sourceCidr: String? = null,
    val preflight: CloudflareEdgePreflightResult? = null,
    val score: Int = 0,
) {
    val subnetKey: String get() = ip?.subnetKey() ?: "host:${address.lowercase(Locale.ROOT)}"

    fun toMciEdge(role: String, maxSplit: Int): MciEdge = MciEdge(
        address = address,
        port = port,
        role = role,
        finalmaskMaxSplit = maxSplit,
    )
}

internal data class CloudflareEdgeDiscoveryResult(
    val suitability: CloudflareSuitabilityDecision,
    val rangeEtag: String,
    val primary: List<CloudflareEdgeCandidate>,
    val backups: List<CloudflareEdgeCandidate>,
    val candidates: List<CloudflareEdgeCandidate>,
) {
    val selected: List<CloudflareEdgeCandidate> get() = primary + backups
    val discoveryId: String = discoveryHash(
        listOf(rangeEtag, suitability.status.name) +
            candidates.asSequence().map(CloudflareEdgeCandidate::key).distinct().sorted().toList(),
    ).take(24)
}

internal enum class CloudflareDiscoveryPhase {
    COLLECTING,
    PREFLIGHT,
}

internal data class CloudflareDiscoveryProgress(
    val phase: CloudflareDiscoveryPhase,
    val completed: Int = 0,
    val total: Int = 0,
    val healthy: Int = 0,
    val currentTarget: String = "",
    val detail: String = "",
)

internal fun interface CloudflareRangeSource {
    suspend fun load(network: Network?): CloudflareRangeSnapshot
}

internal fun interface CloudflareHostResolver {
    suspend fun resolve(network: Network?, hostname: String): List<IpAddress>
}

internal fun interface CloudflareEdgePreflight {
    suspend fun probe(
        network: Network?,
        candidate: CloudflareEdgeCandidate,
        tls: CloudflareTlsProbeSpec?,
    ): CloudflareEdgePreflightResult
}

internal fun interface CloudflareEdgeHistorySource {
    suspend fun load(profileSignature: String, network: NetworkFingerprint): List<CloudflareEdgeHistory>
}

internal class CloudflareEdgeHistoryStore(context: Context) : CloudflareEdgeHistorySource {
    private val prefs = context.applicationContext
        .getSharedPreferences("cloudflare_edge_history_v1", Context.MODE_PRIVATE)

    override suspend fun load(
        profileSignature: String,
        network: NetworkFingerprint,
    ): List<CloudflareEdgeHistory> = synchronized(this) {
        val now = System.currentTimeMillis()
        val cohorts = buildList {
            add(historyKey("exact", profileSignature, network.exactStorageKey()) to CloudflareEdgeSource.SAVED_EXACT)
            if (network.networkAsn.isNotBlank() && network.networkAsn != "unknown") {
                add(historyKey("asn", profileSignature, network.transport, network.networkAsn) to CloudflareEdgeSource.SAVED_ASN)
            }
            if (network.carrierClass.isNotBlank() && network.carrierClass != "unknown") {
                add(historyKey("carrier", profileSignature, network.transport, network.carrierClass) to CloudflareEdgeSource.SAVED_CARRIER)
            }
        }
        cohorts.flatMap { (key, source) -> read(key, source, now) }
            .distinctBy { canonicalEndpointKey(it.address, it.port) }
            .sortedWith(compareByDescending<CloudflareEdgeHistory> { it.score }.thenByDescending { it.lastSuccessAtMs })
            .take(MAX_HISTORY)
    }

    @Synchronized
    fun record(
        profileSignature: String,
        network: NetworkFingerprint,
        candidate: AdaptiveCandidate,
        score: Int,
    ) {
        val now = System.currentTimeMillis()
        val keys = buildList {
            add(historyKey("exact", profileSignature, network.exactStorageKey()))
            if (network.networkAsn.isNotBlank() && network.networkAsn != "unknown") {
                add(historyKey("asn", profileSignature, network.transport, network.networkAsn))
            }
            if (network.carrierClass.isNotBlank() && network.carrierClass != "unknown") {
                add(historyKey("carrier", profileSignature, network.transport, network.carrierClass))
            }
        }
        keys.forEach { key ->
            val existing = read(key, CloudflareEdgeSource.SAVED_EXACT, now).toMutableList()
            existing.removeAll { canonicalEndpointKey(it.address, it.port) == canonicalEndpointKey(candidate.edge.address, candidate.edge.port) }
            existing.add(
                0,
                CloudflareEdgeHistory(
                    address = candidate.edge.address,
                    port = candidate.edge.port,
                    score = score.coerceIn(0, 100),
                    lastSuccessAtMs = now,
                ),
            )
            val array = JSONArray()
            existing.take(MAX_HISTORY).forEach { edge ->
                array.put(
                    JSONObject()
                        .put("address", edge.address)
                        .put("port", edge.port)
                        .put("score", edge.score)
                        .put("lastSuccess", edge.lastSuccessAtMs),
                )
            }
            prefs.edit().putString(key, array.toString()).apply()
        }
    }

    private fun read(
        key: String,
        source: CloudflareEdgeSource,
        now: Long,
    ): List<CloudflareEdgeHistory> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val savedAt = item.optLong("lastSuccess", 0L)
                if (savedAt <= 0L || now - savedAt > HISTORY_TTL_MS) continue
                val address = item.optString("address")
                val port = item.optInt("port")
                if (address.isBlank() || port !in 1..65_535) continue
                add(
                    CloudflareEdgeHistory(
                        address = address,
                        port = port,
                        source = source,
                        score = item.optInt("score", 0),
                        lastSuccessAtMs = savedAt,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun historyKey(vararg parts: String): String = "history:" + discoveryHash(parts.toList()).take(32)

    private companion object {
        const val MAX_HISTORY = 32
        const val HISTORY_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}

internal class CloudflareEdgeDiscovery(
    private val rangeSource: CloudflareRangeSource,
    private val resolver: CloudflareHostResolver,
    private val preflight: CloudflareEdgePreflight,
    private val historySource: CloudflareEdgeHistorySource = CloudflareEdgeHistorySource { _, _ -> emptyList() },
    private val maxCandidates: Int = MAX_CANDIDATES,
    private val preflightWorkers: Int = PREFLIGHT_WORKERS,
) {
    suspend fun discover(
        settings: AdvancedSettingsData,
        profile: ProxyProfile,
        networkContext: UnderlyingNetworkSnapshot,
        profileSignature: String,
        savedEdges: List<MciEdge> = emptyList(),
        onProgress: suspend (CloudflareDiscoveryProgress) -> Unit = {},
    ): CloudflareEdgeDiscoveryResult {
        val validated = settings.validated()
        val network = networkContext.network
        onProgress(
            CloudflareDiscoveryProgress(
                phase = CloudflareDiscoveryPhase.COLLECTING,
                detail = "Loading official Cloudflare ranges",
            ),
        )
        val ranges = runCatching { rangeSource.load(network) }.getOrElse { bundledCloudflareRanges() }
        val localForward = LocalForwardProfile.isLocalForward(profile)
        val identity = if (localForward) {
            LocalForwardProfile.routingIdentity(profile, validated)
        } else {
            profile.runtimeIdentity(validated)
        }
        val direct = if (localForward) null else DirectCompatProfileParser.parse(profile)
        val originalAddress = when {
            localForward -> identity.sni.ifBlank { identity.host }.ifBlank { profile.sni }
            else -> direct?.address.orEmpty().ifBlank { profile.serverHost }
        }
        val originalPort = when {
            localForward -> LocalForwardProfile.ROUTING_PORT
            else -> direct?.port ?: profile.serverPort
        }
        val builders = LinkedHashMap<String, MutableCandidate>()
        val resolvedCache = LinkedHashMap<String, List<IpAddress>>()
        onProgress(
            CloudflareDiscoveryProgress(
                phase = CloudflareDiscoveryPhase.COLLECTING,
                detail = "Resolving profile, saved and DNS edge sources",
            ),
        )

        suspend fun resolveHost(host: String): List<IpAddress> {
            val normalized = normalizeDiscoveryHostname(host) ?: return emptyList()
            return resolvedCache.getOrPut(normalized) {
                runCatching { resolver.resolve(network, normalized) }.getOrDefault(emptyList())
            }
        }

        suspend fun addAddress(
            rawAddress: String,
            port: Int,
            source: CloudflareEdgeSource,
            reserved: Boolean,
            historyScore: Int = 0,
        ) {
            if (port !in 1..65_535 || rawAddress.isBlank()) return
            val literal = IpAddress.parse(rawAddress)
            if (literal != null) {
                mergeCandidate(builders, literal.canonical, port, literal, source, reserved, historyScore, null)
                return
            }
            val hostname = normalizeDiscoveryHostname(rawAddress) ?: return
            val resolved = resolveHost(hostname)
            if (resolved.isEmpty()) {
                mergeCandidate(builders, hostname, port, null, source, reserved, historyScore, null)
            } else {
                resolved.forEach { address ->
                    mergeCandidate(builders, address.canonical, port, address, source, reserved, historyScore, null)
                }
            }
        }

        val currentEdges = buildList {
            addAll(validated.edges())
            add(MciEdge(validated.telegramFallbackAddress, validated.telegramPort, "cdn-a", 2))
            add(MciEdge(validated.telegramAddress, validated.telegramPort, "cdn-b", validated.telegramMaxSplit))
        }
        currentEdges.forEach { edge ->
            addAddress(edge.address, edge.port, CloudflareEdgeSource.CURRENT, reserved = true)
        }
        addAddress(originalAddress, originalPort, CloudflareEdgeSource.ORIGINAL, reserved = true)
        savedEdges.forEach { edge ->
            addAddress(edge.address, edge.port, CloudflareEdgeSource.SAVED_EXACT, reserved = true, historyScore = 100)
        }
        historySource.load(profileSignature, networkContext.fingerprint)
            .sortedWith(compareByDescending<CloudflareEdgeHistory> { it.score }.thenByDescending { it.lastSuccessAtMs })
            .take(MAX_HISTORY_EDGES)
            .forEach { history ->
                val savedSource = history.source.takeIf { it in SAVED_SOURCES } ?: CloudflareEdgeSource.SAVED_EXACT
                addAddress(
                    history.address,
                    history.port,
                    savedSource,
                    reserved = true,
                    historyScore = history.score,
                )
            }

        val dnsNames = listOf(
            Triple(originalAddress, originalPort, CloudflareEdgeSource.DNS_SERVER),
            Triple(identity.sni, originalPort, CloudflareEdgeSource.DNS_SNI),
            Triple(identity.host, originalPort, CloudflareEdgeSource.DNS_HOST),
        )
        dnsNames.forEach { (rawHost, port, source) ->
            val host = normalizeDiscoveryHostname(rawHost) ?: return@forEach
            resolveHost(host).forEach { address ->
                mergeCandidate(builders, address.canonical, port, address, source, false, 0, null)
            }
        }

        val decision = evaluateCloudflareSuitability(
            identity = identity,
            port = originalPort,
            candidates = builders.values,
            ranges = ranges.ranges,
            trustedProfile = profile.usesAdvancedSettingsIdentity(),
        )
        if (shouldSampleOfficialCloudflareRanges(decision)) {
            val usableRanges = ranges.ranges.filter { cidr ->
                if (cidr.isIpv4) networkContext.fingerprint.hasIpv4 || !networkContext.fingerprint.hasIpv6
                else networkContext.fingerprint.hasIpv6 && !validated.ipv4Only
            }
            if (usableRanges.isNotEmpty()) {
                val seed = discoveryHash(
                    listOf(profileSignature, networkContext.fingerprint.exactStorageKey(), ranges.etag, DISCOVERY_SCHEMA),
                )
                var ordinal = 0
                var attempts = 0
                val attemptLimit = maxCandidates * 12
                while (builders.size < maxCandidates && attempts < attemptLimit) {
                    val cidr = usableRanges[ordinal % usableRanges.size]
                    val round = ordinal / usableRanges.size
                    val sampled = cidr.sample(seed, round)
                    mergeCandidate(
                        builders,
                        sampled.canonical,
                        originalPort,
                        sampled,
                        CloudflareEdgeSource.OFFICIAL_CIDR,
                        reserved = false,
                        historyScore = 0,
                        sourceCidr = cidr.canonical,
                    )
                    ordinal++
                    attempts++
                }
            }
        }

        val bounded = boundCandidates(builders.values.map(MutableCandidate::freeze), maxCandidates)
        onProgress(
            CloudflareDiscoveryProgress(
                phase = CloudflareDiscoveryPhase.COLLECTING,
                completed = bounded.size,
                total = bounded.size,
                detail = "${bounded.size} unique edge candidates ready",
            ),
        )
        val tlsSpec = normalizeDiscoveryHostname(identity.sni)?.let { serverName ->
            CloudflareTlsProbeSpec(
                serverName = serverName,
                applicationProtocols = explicitDiscoveryAlpn(identity),
                strictCertificate = !identity.allowInsecure,
            )
        }
        val probed = preflightCandidates(network, bounded, tlsSpec, onProgress)
        val (primary, backups) = selectSubnetDiverseEdges(probed)
        return CloudflareEdgeDiscoveryResult(
            suitability = decision,
            rangeEtag = ranges.etag,
            primary = primary,
            backups = backups,
            candidates = probed,
        )
    }

    private suspend fun preflightCandidates(
        network: Network?,
        candidates: List<CloudflareEdgeCandidate>,
        tls: CloudflareTlsProbeSpec?,
        onProgress: suspend (CloudflareDiscoveryProgress) -> Unit,
    ): List<CloudflareEdgeCandidate> = coroutineScope {
        val slots = Semaphore(preflightWorkers.coerceIn(1, 8))
        val completed = AtomicInteger(0)
        val healthy = AtomicInteger(0)
        onProgress(
            CloudflareDiscoveryProgress(
                phase = CloudflareDiscoveryPhase.PREFLIGHT,
                total = candidates.size,
                detail = "Starting TCP/TLS edge preflight",
            ),
        )
        candidates.map { candidate ->
            async {
                slots.withPermit {
                    val result = if (candidate.ip == null) {
                        CloudflareEdgePreflightResult.unresolved("hostname could not be resolved on the selected network")
                    } else {
                        runCatching { preflight.probe(network, candidate, tls) }
                            .getOrElse { error ->
                                CloudflareEdgePreflightResult.unresolved(
                                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(MAX_DETAIL),
                                )
                            }
                    }
                    if (result.tcpSucceeded) healthy.incrementAndGet()
                    onProgress(
                        CloudflareDiscoveryProgress(
                            phase = CloudflareDiscoveryPhase.PREFLIGHT,
                            completed = completed.incrementAndGet(),
                            total = candidates.size,
                            healthy = healthy.get(),
                            currentTarget = "${candidate.address}:${candidate.port}",
                            detail = result.detail,
                        ),
                    )
                    candidate.copy(
                        preflight = result,
                        score = scoreCandidate(candidate, result),
                    )
                }
            }
        }.awaitAll()
    }

    companion object {
        const val MAX_CANDIDATES = 60
        const val PREFLIGHT_WORKERS = 4
        private const val MAX_HISTORY_EDGES = 16
        private const val MAX_DETAIL = 240
        private const val DISCOVERY_SCHEMA = "cf-edge-discovery-v1"
        private val SAVED_SOURCES = setOf(
            CloudflareEdgeSource.SAVED_EXACT,
            CloudflareEdgeSource.SAVED_ASN,
            CloudflareEdgeSource.SAVED_CARRIER,
        )

        fun create(
            context: Context,
            historySource: CloudflareEdgeHistorySource = CloudflareEdgeHistoryStore(context),
        ): CloudflareEdgeDiscovery = CloudflareEdgeDiscovery(
            rangeSource = AndroidCloudflareRangeSource(context.applicationContext),
            resolver = AndroidCloudflareHostResolver,
            preflight = AndroidCloudflareEdgePreflight,
            historySource = historySource,
        )
    }
}

internal fun evaluateCloudflareSuitability(
    identity: RuntimeProxyIdentity,
    port: Int,
    candidates: Collection<Any>,
    ranges: List<IpCidr>,
    trustedProfile: Boolean = false,
): CloudflareSuitabilityDecision {
    if (!identity.security.equals("tls", ignoreCase = true)) {
        return CloudflareSuitabilityDecision(CloudflareSuitability.INELIGIBLE, "profile security is not TLS")
    }
    if (normalizeDiscoveryHostname(identity.sni) == null) {
        return CloudflareSuitabilityDecision(CloudflareSuitability.INELIGIBLE, "a DNS TLS SNI is required")
    }
    val network = identity.network.lowercase(Locale.ROOT)
    val protocolCompatible = when (network) {
        "ws", "httpupgrade", "xhttp" -> port in CLOUDFLARE_HTTPS_PORTS
        "grpc" -> port == 443 && "h2" in effectiveDiscoveryAlpn(identity)
        "tcp" -> false
        else -> false
    }
    if (!protocolCompatible) {
        return CloudflareSuitabilityDecision(
            if (network == "tcp") CloudflareSuitability.UNKNOWN else CloudflareSuitability.INELIGIBLE,
            if (network == "tcp") "raw TCP requires prior Spectrum/Xray evidence" else "unsupported transport, port or ALPN",
        )
    }
    if (trustedProfile) {
        return CloudflareSuitabilityDecision(
            CloudflareSuitability.ELIGIBLE,
            "trusted built-in TLS transport",
        )
    }
    val evidenceSources = setOf(
        CloudflareEdgeSource.ORIGINAL,
        CloudflareEdgeSource.DNS_SERVER,
        CloudflareEdgeSource.DNS_SNI,
        CloudflareEdgeSource.DNS_HOST,
        CloudflareEdgeSource.SAVED_EXACT,
        CloudflareEdgeSource.SAVED_ASN,
        CloudflareEdgeSource.SAVED_CARRIER,
    )
    val evidence = candidates.asSequence()
        .mapNotNull { candidate ->
            when (candidate) {
                is CloudflareEdgeCandidate -> candidate.ip?.takeIf { candidate.sources.any(evidenceSources::contains) }
                is MutableCandidate -> candidate.ip?.takeIf { candidate.sources.any(evidenceSources::contains) }
                else -> null
            }
        }
        .any { address -> ranges.any { it.contains(address) } }
    return if (evidence) {
        CloudflareSuitabilityDecision(CloudflareSuitability.ELIGIBLE, "configured, saved or DNS edge is in an official range")
    } else {
        CloudflareSuitabilityDecision(CloudflareSuitability.UNKNOWN, "no Cloudflare range evidence on the selected network")
    }
}

internal fun shouldSampleOfficialCloudflareRanges(decision: CloudflareSuitabilityDecision): Boolean =
    decision.status == CloudflareSuitability.ELIGIBLE || decision.status == CloudflareSuitability.UNKNOWN

internal fun selectSubnetDiverseEdges(
    candidates: List<CloudflareEdgeCandidate>,
    primaryLimit: Int = 8,
    backupLimit: Int = 2,
): Pair<List<CloudflareEdgeCandidate>, List<CloudflareEdgeCandidate>> {
    val ranked = candidates
        .filter { candidate -> candidate.reserved || candidate.preflight?.tcpSucceeded == true }
        .sortedWith(candidateRanking())
    if (ranked.isEmpty()) return emptyList<CloudflareEdgeCandidate>() to emptyList()

    val primary = LinkedHashMap<String, CloudflareEdgeCandidate>()
    ranked.take(primaryLimit.coerceAtLeast(0)).forEach { candidate -> primary[candidate.key] = candidate }

    val championSubnet = primary.values.firstOrNull()?.subnetKey
    val backup = LinkedHashMap<String, CloudflareEdgeCandidate>()
    val backupSubnets = HashSet<String>()
    val remaining = ranked.filterNot { it.key in primary }
    remaining.forEach { candidate ->
        if (backup.size >= backupLimit) return@forEach
        if (candidate.subnetKey != championSubnet && candidate.subnetKey !in backupSubnets) {
            backup[candidate.key] = candidate
            backupSubnets += candidate.subnetKey
        }
    }
    remaining.forEach { candidate ->
        if (backup.size < backupLimit) backup.putIfAbsent(candidate.key, candidate)
    }
    return primary.values.toList() to backup.values.toList()
}

private fun candidateRanking(): Comparator<CloudflareEdgeCandidate> =
    compareByDescending<CloudflareEdgeCandidate> { it.score }
        .thenByDescending { it.reserved }
        .thenBy { it.preflight?.tcpLatencyMs ?: Long.MAX_VALUE }
        .thenBy { it.key }

private fun scoreCandidate(
    candidate: CloudflareEdgeCandidate,
    result: CloudflareEdgePreflightResult,
): Int {
    val sourceScore = candidate.sources.maxOfOrNull { source ->
        when (source) {
            CloudflareEdgeSource.SAVED_EXACT -> 90
            CloudflareEdgeSource.SAVED_ASN -> 80
            CloudflareEdgeSource.SAVED_CARRIER -> 70
            CloudflareEdgeSource.CURRENT -> 65
            CloudflareEdgeSource.ORIGINAL -> 60
            CloudflareEdgeSource.DNS_SNI -> 50
            CloudflareEdgeSource.DNS_HOST -> 45
            CloudflareEdgeSource.DNS_SERVER -> 40
            CloudflareEdgeSource.OFFICIAL_CIDR -> 20
        }
    } ?: 0
    val tcp = if (result.tcpSucceeded) 100 else if (candidate.reserved) 5 else -100
    val tls = if (result.tlsSucceeded) 35 else 0
    val latency = result.tcpLatencyMs?.let { (30L - (it / 50L)).coerceIn(0L, 30L).toInt() } ?: 0
    return sourceScore + candidate.historyScore.coerceIn(0, 100) + tcp + tls + latency
}

private fun boundCandidates(
    candidates: List<CloudflareEdgeCandidate>,
    limit: Int,
): List<CloudflareEdgeCandidate> = candidates
    .sortedWith(
        compareByDescending<CloudflareEdgeCandidate> { it.reserved }
            .thenByDescending { it.historyScore }
            .thenByDescending { candidate -> candidate.sources.maxOfOrNull(::sourcePriority) ?: 0 }
            .thenBy { it.key },
    )
    .take(limit.coerceAtLeast(1))

private fun sourcePriority(source: CloudflareEdgeSource): Int = when (source) {
    CloudflareEdgeSource.SAVED_EXACT -> 90
    CloudflareEdgeSource.SAVED_ASN -> 80
    CloudflareEdgeSource.SAVED_CARRIER -> 70
    CloudflareEdgeSource.CURRENT -> 65
    CloudflareEdgeSource.ORIGINAL -> 60
    CloudflareEdgeSource.DNS_SNI -> 50
    CloudflareEdgeSource.DNS_HOST -> 45
    CloudflareEdgeSource.DNS_SERVER -> 40
    CloudflareEdgeSource.OFFICIAL_CIDR -> 20
}

private data class MutableCandidate(
    val key: String,
    val address: String,
    val port: Int,
    val ip: IpAddress?,
    val sources: LinkedHashSet<CloudflareEdgeSource> = linkedSetOf(),
    var reserved: Boolean,
    var historyScore: Int,
    var sourceCidr: String?,
) {
    fun freeze() = CloudflareEdgeCandidate(
        key = key,
        address = address,
        port = port,
        ip = ip,
        sources = sources.toSet(),
        reserved = reserved,
        historyScore = historyScore,
        sourceCidr = sourceCidr,
    )
}

private fun mergeCandidate(
    candidates: LinkedHashMap<String, MutableCandidate>,
    address: String,
    port: Int,
    ip: IpAddress?,
    source: CloudflareEdgeSource,
    reserved: Boolean,
    historyScore: Int,
    sourceCidr: String?,
) {
    val key = canonicalEndpointKey(address, port)
    val existing = candidates[key]
    if (existing == null) {
        candidates[key] = MutableCandidate(
            key = key,
            address = ip?.canonical ?: address,
            port = port,
            ip = ip,
            sources = linkedSetOf(source),
            reserved = reserved,
            historyScore = historyScore,
            sourceCidr = sourceCidr,
        )
    } else {
        existing.sources += source
        existing.reserved = existing.reserved || reserved
        existing.historyScore = maxOf(existing.historyScore, historyScore)
        if (existing.sourceCidr == null) existing.sourceCidr = sourceCidr
    }
}

internal fun effectiveDiscoveryAlpn(identity: RuntimeProxyIdentity): List<String> {
    val explicit = explicitDiscoveryAlpn(identity)
    return explicit.ifEmpty { listOf(if (identity.network.equals("grpc", true) || identity.network.equals("xhttp", true)) "h2" else "http/1.1") }
}

private fun explicitDiscoveryAlpn(identity: RuntimeProxyIdentity): List<String> =
    TlsAlpnResolver.resolveForTransport(
        identity.network,
        TlsAlpnResolver.canonicalString(identity.alpn, identity.network),
    )

internal fun normalizeDiscoveryHostname(raw: String): String? {
    var value = raw.trim().trimEnd('.')
    if (value.isBlank() || value.contains('*')) return null
    if (value.startsWith('[')) {
        value = value.substringAfter('[').substringBefore(']')
    } else if (value.count { it == ':' } == 1 && value.substringAfterLast(':').toIntOrNull() != null) {
        value = value.substringBeforeLast(':')
    }
    if (IpAddress.parse(value) != null) return null
    val ascii = runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
        ?.lowercase(Locale.ROOT)
        ?: return null
    return ascii.takeIf { it.isNotBlank() && it.length <= 253 && it.contains('.') }
}

private object AndroidCloudflareHostResolver : CloudflareHostResolver {
    override suspend fun resolve(network: Network?, hostname: String): List<IpAddress> =
        runInterruptible(Dispatchers.IO) {
            val addresses = network?.getAllByName(hostname) ?: InetAddress.getAllByName(hostname)
            addresses.mapNotNull(IpAddress::from).distinct()
        }
}

private object AndroidCloudflareEdgePreflight : CloudflareEdgePreflight {
    override suspend fun probe(
        network: Network?,
        candidate: CloudflareEdgeCandidate,
        tls: CloudflareTlsProbeSpec?,
    ): CloudflareEdgePreflightResult = withTimeoutOrNull(PREFLIGHT_TOTAL_TIMEOUT_MS) {
        runInterruptible(Dispatchers.IO) { probeBlocking(network, candidate, tls) }
    } ?: CloudflareEdgePreflightResult.unresolved("preflight timed out after ${PREFLIGHT_TOTAL_TIMEOUT_MS}ms")

    private fun probeBlocking(
        network: Network?,
        candidate: CloudflareEdgeCandidate,
        tls: CloudflareTlsProbeSpec?,
    ): CloudflareEdgePreflightResult {
        val address = candidate.ip ?: return CloudflareEdgePreflightResult.unresolved("numeric address required")
        val raw = network?.socketFactory?.createSocket() ?: Socket()
        return try {
            raw.soTimeout = TLS_TIMEOUT_MS
            val tcpStarted = System.nanoTime()
            raw.connect(InetSocketAddress(InetAddress.getByAddress(address.bytes()), candidate.port), TCP_TIMEOUT_MS)
            val tcpMs = elapsedMs(tcpStarted)
            if (tls == null) {
                CloudflareEdgePreflightResult(
                    tcpSucceeded = true,
                    tlsAttempted = false,
                    tlsSucceeded = false,
                    tcpLatencyMs = tcpMs,
                    detail = "TCP passed; TLS SNI unavailable",
                )
            } else {
                val tlsStarted = System.nanoTime()
                try {
                    val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, tls.serverName, candidate.port, true) as SSLSocket
                    socket.use { ssl ->
                        ssl.useClientMode = true
                        ssl.soTimeout = TLS_TIMEOUT_MS
                        val parameters = ssl.sslParameters
                        parameters.serverNames = listOf(SNIHostName(tls.serverName))
                        if (tls.strictCertificate) parameters.endpointIdentificationAlgorithm = "HTTPS"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && tls.applicationProtocols.isNotEmpty()) {
                            parameters.applicationProtocols = tls.applicationProtocols.toTypedArray()
                        }
                        ssl.sslParameters = parameters
                        ssl.startHandshake()
                        val negotiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ssl.applicationProtocol.takeIf(String::isNotBlank)
                        } else {
                            null
                        }
                        CloudflareEdgePreflightResult(
                            tcpSucceeded = true,
                            tlsAttempted = true,
                            tlsSucceeded = true,
                            tcpLatencyMs = tcpMs,
                            tlsLatencyMs = elapsedMs(tlsStarted),
                            negotiatedAlpn = negotiated,
                            detail = "TCP and advisory TLS passed",
                        )
                    }
                } catch (error: Exception) {
                    CloudflareEdgePreflightResult(
                        tcpSucceeded = true,
                        tlsAttempted = true,
                        tlsSucceeded = false,
                        tcpLatencyMs = tcpMs,
                        tlsLatencyMs = elapsedMs(tlsStarted),
                        detail = "TCP passed; advisory TLS ${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(240),
                    )
                }
            }
        } catch (error: Exception) {
            CloudflareEdgePreflightResult(
                tcpSucceeded = false,
                tlsAttempted = false,
                tlsSucceeded = false,
                detail = "TCP ${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(240),
            )
        } finally {
            runCatching { raw.close() }
        }
    }

    private fun elapsedMs(startedNs: Long): Long = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)

    private const val TCP_TIMEOUT_MS = 1_500
    private const val TLS_TIMEOUT_MS = 2_500
    private const val PREFLIGHT_TOTAL_TIMEOUT_MS = 4_250L
}

private class AndroidCloudflareRangeSource(
    context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CloudflareRangeSource {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(network: Network?): CloudflareRangeSnapshot {
        val now = nowMs()
        val cachedRaw = prefs.getString(KEY_JSON, null)
        val cachedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        val cached = cachedRaw?.let { parseCloudflareRangeResponse(it, cachedAt, "cache") }
        if (cached != null && now - cachedAt in 0 until CACHE_TTL_MS) return cached

        val fetched = withTimeoutOrNull(FETCH_TOTAL_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) { fetch(network, now) }
        }
        if (fetched != null) {
            prefs.edit()
                .putString(KEY_JSON, fetched.first)
                .putLong(KEY_FETCHED_AT, now)
                .apply()
            return fetched.second
        }
        return cached ?: bundledCloudflareRanges(now)
    }

    private fun fetch(network: Network?, fetchedAtMs: Long): Pair<String, CloudflareRangeSnapshot>? {
        val url = URL(API_URL)
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        return try {
            connection.connectTimeout = FETCH_SOCKET_TIMEOUT_MS
            connection.readTimeout = FETCH_SOCKET_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode !in 200..299) return null
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4_096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    output.append(buffer, 0, read)
                    check(output.length <= MAX_RESPONSE_CHARS) { "Cloudflare range response is too large" }
                }
                output.toString()
            }
            val parsed = parseCloudflareRangeResponse(raw, fetchedAtMs, "api") ?: return null
            raw to parsed
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PREFS_NAME = "cloudflare_edge_ranges_v1"
        private const val KEY_JSON = "response_json"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val API_URL = "https://api.cloudflare.com/client/v4/ips"
        private const val FETCH_SOCKET_TIMEOUT_MS = 1_800
        private const val FETCH_TOTAL_TIMEOUT_MS = 4_000L
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_RESPONSE_CHARS = 65_536
    }
}

internal fun parseCloudflareRangeResponse(
    raw: String,
    fetchedAtMs: Long = 0L,
    source: String = "test",
): CloudflareRangeSnapshot? = runCatching {
    val root = JSONObject(raw)
    check(root.optBoolean("success", false)) { "Cloudflare range response was unsuccessful" }
    val result = root.getJSONObject("result")
    val values = buildList {
        listOf("ipv4_cidrs", "ipv6_cidrs").forEach { key ->
            val array = result.optJSONArray(key) ?: return@forEach
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }.distinct()
    check(values.isNotEmpty() && values.size <= 128) { "Invalid Cloudflare range count" }
    val ranges = values.map { value ->
        IpCidr.parse(value) ?: error("Invalid Cloudflare CIDR: $value")
    }.distinctBy(IpCidr::canonical)
    CloudflareRangeSnapshot(
        etag = result.optString("etag").ifBlank { discoveryHash(ranges.map(IpCidr::canonical)).take(24) },
        ranges = ranges,
        fetchedAtMs = fetchedAtMs,
        source = source,
    )
}.getOrNull()

internal fun bundledCloudflareRanges(nowMs: Long = 0L): CloudflareRangeSnapshot = CloudflareRangeSnapshot(
    etag = "bundled-cf-2026-08",
    ranges = (BUNDLED_IPV4_RANGES + BUNDLED_IPV6_RANGES).mapNotNull(IpCidr::parse),
    fetchedAtMs = nowMs,
    source = "bundled",
)

private fun discoveryHash(values: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(values.joinToString("|").toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

private val CLOUDFLARE_HTTPS_PORTS = setOf(443, 2053, 2083, 2087, 2096, 8443)

private val BUNDLED_IPV4_RANGES = listOf(
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
)

private val BUNDLED_IPV6_RANGES = listOf(
    "2400:cb00::/32",
    "2606:4700::/32",
    "2803:f800::/32",
    "2405:b500::/32",
    "2405:8100::/32",
    "2a06:98c0::/29",
    "2c0f:f248::/32",
)
