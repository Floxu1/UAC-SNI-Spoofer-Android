package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.DirectCompatProfile
import com.uacspoofer.mobile.profiles.LocalForwardProfile
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class NetworkFingerprint(
    val key: String,
    val networkHandle: Long,
    val transport: String,
    val carrier: String,
    val carrierClass: String,
    val networkAsn: String,
    val networkProvider: String,
    val dataSubscriptionId: Int,
    val metered: Boolean,
    val roaming: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val mtu: Int,
    val hasIpv4: Boolean,
    val hasIpv6: Boolean,
    val dnsCount: Int,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
) {
    fun summary(): String =
        "id=$key transport=$transport carrier=$carrier class=$carrierClass asn=$networkAsn provider=$networkProvider " +
            "dataSub=$dataSubscriptionId metered=$metered " +
            "roaming=$roaming validated=$validated captive=$captivePortal mtu=$mtu " +
            "ip4=$hasIpv4 ip6=$hasIpv6 dns=$dnsCount downKbps=$downstreamKbps upKbps=$upstreamKbps"

    fun isSameUnderlyingNetwork(other: NetworkFingerprint): Boolean = when {
        networkHandle >= 0L && other.networkHandle >= 0L -> networkHandle == other.networkHandle
        else -> transport == other.transport && key == other.key
    }

    fun learningKey(): String = sha256(
        if (networkAsn.isNotBlank() && networkAsn != "unknown") {
            "$transport|asn:$networkAsn|class:$carrierClass"
        } else {
            "$transport|class:$carrierClass|fallback:$key"
        },
    ).take(20)

    fun exactStorageKey(): String = sha256(
        "offline-network-v1|$transport|$key",
    ).take(20)
}

data class UnderlyingNetworkSnapshot(
    val fingerprint: NetworkFingerprint,
    val network: Network?,
)

class NetworkFingerprintResolver(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val telephony = context.applicationContext
        .getSystemService(TelephonyManager::class.java)
    @Volatile private var preferredNetworkHandle = -1L
    @Volatile private var identityCache: IdentityCache? = null
    @Volatile private var fingerprintKeyCache: FingerprintKeyCache? = null

    fun capture(): NetworkFingerprint = captureSelected(selectUnderlyingNetwork())

    private fun captureSelected(selected: Network?): NetworkFingerprint {
        val capabilities = selected?.let(connectivity::getNetworkCapabilities)
        val links = selected?.let(connectivity::getLinkProperties)
        val transport = transportName(capabilities)
        val cellular = transport == "cellular"
        val dataSubscriptionId = if (cellular) resolveActiveDataSubscriptionId() else -1
        val carrierTelephony = if (dataSubscriptionId >= 0) {
            runCatching { telephony?.createForSubscriptionId(dataSubscriptionId) }.getOrNull() ?: telephony
        } else {
            telephony
        }
        val operatorCode = if (cellular) {
            runCatching { carrierTelephony?.networkOperator.orEmpty() }.getOrDefault("")
        } else {
            ""
        }
        val operatorName = if (cellular) {
            runCatching { carrierTelephony?.networkOperatorName.orEmpty() }.getOrDefault("")
                .ifBlank { runCatching { carrierTelephony?.simOperatorName.orEmpty() }.getOrDefault("") }
                .trim()
                .take(40)
        } else {
            ""
        }
        val carrierClass = when {
            cellular -> classifyCarrier(operatorCode, operatorName)
            transport == "wifi" || transport == "ethernet" -> "fixed"
            else -> "unknown"
        }
        val descriptor = buildDescriptor(transport, operatorCode, carrierClass, capabilities, links)
        return NetworkFingerprint(
            key = sha256(descriptor).take(20),
            networkHandle = selected?.networkHandle ?: -1L,
            transport = transport,
            carrier = operatorName.ifBlank { operatorCode.ifBlank { carrierClass } },
            carrierClass = carrierClass,
            networkAsn = "unknown",
            networkProvider = operatorName.ifBlank { carrierClass },
            dataSubscriptionId = dataSubscriptionId,
            metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            roaming = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            mtu = links?.mtu ?: 0,
            hasIpv4 = links?.linkAddresses?.any { it.address.address.size == 4 } == true,
            hasIpv6 = links?.linkAddresses?.any { it.address.address.size == 16 } == true,
            dnsCount = links?.dnsServers?.size ?: 0,
            downstreamKbps = capabilities?.linkDownstreamBandwidthKbps ?: 0,
            upstreamKbps = capabilities?.linkUpstreamBandwidthKbps ?: 0,
        )
    }

    suspend fun captureAdaptive(): NetworkFingerprint = captureAdaptiveContext().fingerprint

    suspend fun captureAdaptiveContext(): UnderlyingNetworkSnapshot {
        val selected = selectUnderlyingNetwork()
        val base = captureSelected(selected)
        if (base.networkHandle < 0L) return UnderlyingNetworkSnapshot(base, selected)
        val offlineKey = pinFingerprintKey(base.networkHandle, base.key)
        val offline = base.copy(key = offlineKey)
        if (base.transport == "cellular" && base.carrierClass != "unknown") {
            return UnderlyingNetworkSnapshot(offline, selected)
        }
        val identity = resolveNetworkIdentity(base.networkHandle)
            ?: return UnderlyingNetworkSnapshot(offline, selected)
        val detectedClass = classifyProvider(identity.provider).takeIf { it != "unknown" } ?: base.carrierClass
        return UnderlyingNetworkSnapshot(
            offline.copy(
                carrier = identity.provider.ifBlank { base.carrier },
                carrierClass = detectedClass,
                networkAsn = identity.asn,
                networkProvider = identity.provider,
            ),
            selected,
        )
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun selectUnderlyingNetwork(): Network? {
        val active = connectivity.activeNetwork
        val activeCapabilities = active?.let(connectivity::getNetworkCapabilities)
        if (active != null && activeCapabilities != null &&
            !activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            preferredNetworkHandle = active.networkHandle
            return active
        }
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val score = when {
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> 3
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> 2
                else -> 1
            }
            Triple(network, capabilities, score)
        }
        val topScore = candidates.maxOfOrNull { it.third } ?: 0
        val preferred = candidates.firstOrNull {
            it.first.networkHandle == preferredNetworkHandle && it.third >= topScore
        }
        val selected = preferred ?: candidates.maxWithOrNull(
            compareBy<Triple<Network, NetworkCapabilities, Int>> { it.third }
                .thenBy { it.second.linkDownstreamBandwidthKbps },
        )
        selected?.first?.let { preferredNetworkHandle = it.networkHandle }
        return selected?.first
    }

    private fun transportName(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "unknown"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    private fun resolveActiveDataSubscriptionId(): Int {
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { SubscriptionManager.getActiveDataSubscriptionId() }.getOrDefault(-1)
        } else {
            -1
        }
        if (active >= 0) return active
        return runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
            .takeIf { it >= 0 }
            ?: -1
    }

    private fun classifyCarrier(code: String, name: String): String {
        val normalized = name.lowercase(Locale.ROOT)
        return when {
            code == "43211" || normalized.contains("mci") || normalized.contains("ir-mci") ||
                normalized.contains("hamrah") -> "mci"
            code == "43235" || normalized.contains("irancell") || normalized.contains("mtn") -> "irancell"
            else -> "unknown"
        }
    }

    private fun classifyProvider(provider: String): String {
        val normalized = provider.lowercase(Locale.ROOT)
        return when {
            listOf("mci", "ir-mci", "hamrah", "mobile communication company of iran")
                .any(normalized::contains) -> "mci"
            listOf("irancell", "iran cell", "mtn irancell").any(normalized::contains) -> "irancell"
            normalized.contains("rightel") || normalized.contains("right tel") -> "rightel"
            normalized.contains("mobinnet") -> "mobinnet"
            normalized.contains("shatel") -> "shatel"
            normalized.contains("pishgaman") -> "pishgaman"
            normalized.contains("asiatech") -> "asiatech"
            normalized.contains("hiweb") || normalized.contains("hi web") -> "hiweb"
            normalized.contains("pars online") || normalized.contains("parsonline") -> "parsonline"
            normalized.contains("telecommunication company of iran") || normalized.contains("tci") -> "tci"
            else -> "unknown"
        }
    }

    private suspend fun resolveNetworkIdentity(networkHandle: Long): NetworkIdentity? =
        withTimeoutOrNull(IDENTITY_TOTAL_TIMEOUT_MS) {
            identityCache?.takeIf {
                it.networkHandle == networkHandle && System.currentTimeMillis() - it.updatedAtMs < IDENTITY_CACHE_TTL_MS
            }?.let { return@withTimeoutOrNull it.identity }
            val network = connectivity.allNetworks.firstOrNull { it.networkHandle == networkHandle }
                ?: return@withTimeoutOrNull null
            for (endpoint in IDENTITY_ENDPOINTS) {
                val identity = runCatching {
                    runInterruptible(Dispatchers.IO) { fetchNetworkIdentity(network, endpoint) }
                }.getOrNull()
                if (identity != null && (identity.asn.isNotBlank() || identity.provider.isNotBlank())) {
                    identityCache = IdentityCache(networkHandle, identity, System.currentTimeMillis())
                    return@withTimeoutOrNull identity
                }
            }
            null
        }

    private fun fetchNetworkIdentity(network: Network, endpoint: String): NetworkIdentity {
        val connection = network.openConnection(URL(endpoint)) as HttpsURLConnection
        try {
            connection.connectTimeout = IDENTITY_SOCKET_TIMEOUT_MS
            connection.readTimeout = IDENTITY_SOCKET_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/${BuildConfig.VERSION_NAME}")
            check(connection.responseCode in 200..299) { "identity HTTP ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText().take(32_768) })
            val nested = json.optJSONObject("connection")
            val asn = nested?.optString("asn").orEmpty()
                .ifBlank { json.optString("asn") }
                .removePrefix("AS")
                .trim()
            val provider = listOf(
                nested?.optString("isp").orEmpty(),
                nested?.optString("org").orEmpty(),
                json.optString("org"),
                json.optString("isp"),
            ).firstOrNull { it.isNotBlank() }.orEmpty().trim().take(80)
            return NetworkIdentity(asn = asn.ifBlank { "unknown" }, provider = provider.ifBlank { "unknown" })
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    private fun pinFingerprintKey(networkHandle: Long, proposedKey: String): String {
        fingerprintKeyCache?.takeIf { it.networkHandle == networkHandle }?.let { return it.key }
        fingerprintKeyCache = FingerprintKeyCache(networkHandle, proposedKey)
        return proposedKey
    }

    private fun buildDescriptor(
        transport: String,
        operatorCode: String,
        carrierClass: String,
        capabilities: NetworkCapabilities?,
        links: LinkProperties?,
    ): String {
        val families = links?.linkAddresses.orEmpty()
            .map { "${it.address.address.size}:${it.prefixLength}" }
            .sorted()
            .joinToString(",")
        val dns = links?.dnsServers.orEmpty()
            .map { sha256(it.hostAddress.orEmpty()).take(8) }
            .sorted()
            .joinToString(",")
        return listOf(
            transport,
            operatorCode,
            carrierClass,
            links?.mtu ?: 0,
            families,
            dns,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == true,
        ).joinToString("|")
    }

    private data class NetworkIdentity(val asn: String, val provider: String)
    private data class IdentityCache(
        val networkHandle: Long,
        val identity: NetworkIdentity,
        val updatedAtMs: Long,
    )
    private data class FingerprintKeyCache(val networkHandle: Long, val key: String)

    companion object {
        private const val IDENTITY_SOCKET_TIMEOUT_MS = 1_500
        private const val IDENTITY_TOTAL_TIMEOUT_MS = 3_200L
        private const val IDENTITY_CACHE_TTL_MS = 2L * 60L * 1_000L
        private val IDENTITY_ENDPOINTS = listOf(
            "https://ipwho.is/?fields=success,connection",
            "https://ipapi.co/json/",
        )
    }
}

data class AdaptiveCandidate(
    val id: String,
    val label: String,
    val edge: MciEdge,
    val settings: AdvancedSettingsData,
    val runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
    val learned: Boolean = false,
) {
    fun summary(): String {
        val fragment = if (runtimeOptions.finalmaskEnabled) {
            "${settings.finalmaskPacket}/${settings.finalmaskLength}/${settings.finalmaskDelayMs}ms"
        } else {
            "disabled"
        }
        return "id=$id mode=${settings.connectionMode} edge=${edge.role}@${edge.address}:${edge.port} split=${edge.finalmaskMaxSplit} " +
            "fragment=$fragment directCompat=${runtimeOptions.identityOverride != null} " +
            "mtu=${settings.tunMtu} dnsTrap=${settings.nativeDns} " +
            "resolver=${AdaptiveDnsResolvers.idFor(settings.dnsResolverUrl)} " +
            "udp443Blocked=${settings.blockUdp443} learned=$learned"
    }
}

data class AdaptiveProbeReport(
    val accepted: Boolean,
    val acceptanceMode: String,
    val score: Int,
    val http: ProbeResult,
    val dns: DnsProbeResult,
    val tun: ProbeResult?,
    val durationMs: Long,
) {
    fun detail(): String =
        "accepted=$accepted mode=$acceptanceMode score=$score duration=${durationMs}ms http=${http.succeededTargets}/${http.attemptedTargets} " +
            "bytes=${http.totalBytes} latency=${http.latencyMs ?: -1}ms dns=${dns.success} " +
            "dnsLatency=${dns.latencyMs ?: -1}ms answers=${dns.answerCount} tun=${tun?.success ?: true} " +
            "tunPayload=${tun?.hasSuccessfulPayload() ?: true} " +
            "httpDetail=[${http.detail}] dnsDetail=[${dns.detail}] tunDetail=[${tun?.detail ?: "proxy-mode"}]"
}

class AdaptiveConnectionProbe(
    private val connectivityProbe: VpnConnectivityProbe,
    private val tunConnectivityProbe: VpnConnectivityProbe,
    private val dnsProbe: SocksDnsProbe,
) {
    suspend fun verify(candidate: AdaptiveCandidate): AdaptiveProbeReport {
        return verifyInternal(candidate)
    }

    suspend fun verifyForSniMaker(
        candidate: AdaptiveCandidate,
        remainingBudgetMs: Long,
    ): AdaptiveProbeReport {
        val httpBudgetMs = remainingBudgetMs.coerceIn(1_000L, SNI_MAKER_HTTP_TIMEOUT_MS)
        return verifyInternal(
            candidate = candidate,
            httpTimeoutMs = httpBudgetMs,
            httpReadBytesPerTarget = SNI_MAKER_READ_BYTES_PER_TARGET,
            dnsTimeoutMs = SNI_MAKER_DNS_TIMEOUT_MS.coerceAtMost(remainingBudgetMs),
            dnsSocketTimeoutMs = SNI_MAKER_DNS_SOCKET_TIMEOUT_MS,
        )
    }

    suspend fun verifyForRouteSpeed(candidate: AdaptiveCandidate): AdaptiveProbeReport =
        verifyInternal(
            candidate = candidate,
            httpTimeoutMs = ROUTE_SPEED_HTTP_TIMEOUT_MS,
            httpReadBytesPerTarget = ROUTE_SPEED_READ_BYTES_PER_TARGET,
            dnsTimeoutMs = ROUTE_SPEED_DNS_TIMEOUT_MS,
            dnsSocketTimeoutMs = ROUTE_SPEED_DNS_SOCKET_TIMEOUT_MS,
        )

    private suspend fun verifyInternal(
        candidate: AdaptiveCandidate,
        httpTimeoutMs: Long? = null,
        httpReadBytesPerTarget: Int? = null,
        dnsTimeoutMs: Long? = null,
        dnsSocketTimeoutMs: Int? = null,
    ): AdaptiveProbeReport {
        val started = System.nanoTime()
        val http = if (httpTimeoutMs != null && httpReadBytesPerTarget != null) {
            connectivityProbe.verifyCandidate(candidate.settings, httpTimeoutMs, httpReadBytesPerTarget)
        } else {
            connectivityProbe.verifyCandidate(candidate.settings)
        }
        val dns = if (http.success) {
            if (dnsTimeoutMs != null && dnsSocketTimeoutMs != null) {
                dnsProbe.verify(candidate.settings, dnsTimeoutMs, dnsSocketTimeoutMs)
            } else {
                dnsProbe.verify(candidate.settings)
            }
        } else {
            DnsProbeResult(
                success = false,
                server = candidate.settings.nativeDns,
                detail = "skipped because HTTPS egress failed",
            )
        }
        val tun = if (candidate.settings.connectionMode == "tunnel" && http.success) {
            tunConnectivityProbe.verifyTunCandidate()
        } else {
            null
        }
        val score = score(http, dns, tun)
        val gate = decideAdaptiveGate(http, dns, tun, score)
        return AdaptiveProbeReport(
            accepted = gate.accepted,
            acceptanceMode = gate.mode,
            score = score,
            http = http,
            dns = dns,
            tun = tun,
            durationMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
        )
    }

    private fun score(http: ProbeResult, dns: DnsProbeResult, tun: ProbeResult?): Int {
        var score = http.succeededTargets.coerceAtMost(2) * 25
        if (http.succeededTargets >= 2) score += 10
        if (dns.success) score += 20
        if (tun?.success == true) score += 15
        val latency = listOfNotNull(http.latencyMs, dns.latencyMs).minOrNull()
        score += when {
            latency == null -> 0
            latency <= 500L -> 10
            latency <= 1_200L -> 8
            latency <= 2_500L -> 5
            else -> 2
        }
        score += when {
            http.totalBytes >= 32_768 -> 10
            http.totalBytes >= 8_192 -> 7
            http.totalBytes >= 1_024 -> 4
            else -> 0
        }
        return score.coerceIn(0, 100)
    }

    companion object {
        private const val SNI_MAKER_HTTP_TIMEOUT_MS = 4_000L
        private const val SNI_MAKER_DNS_TIMEOUT_MS = 2_000L
        private const val SNI_MAKER_DNS_SOCKET_TIMEOUT_MS = 1_750
        private const val SNI_MAKER_READ_BYTES_PER_TARGET = 4_096
        private const val ROUTE_SPEED_HTTP_TIMEOUT_MS = 8_000L
        private const val ROUTE_SPEED_DNS_TIMEOUT_MS = 2_500L
        private const val ROUTE_SPEED_DNS_SOCKET_TIMEOUT_MS = 2_200
        private const val ROUTE_SPEED_READ_BYTES_PER_TARGET = 64 * 1_024
    }
}

internal fun prioritizeAdaptiveCandidates(
    raw: List<AdaptiveCandidate>,
    savedRoute: AdaptiveCandidate?,
    savedBackupRoute: AdaptiveCandidate?,
    learnedId: String?,
    maxAdaptiveCandidates: Int,
    connectChampion: AdaptiveCandidate? = null,
): List<AdaptiveCandidate> {
    val rawPool = raw.take(maxAdaptiveCandidates.coerceAtLeast(0))
    val diagnostic = rawPool.firstOrNull { it.id == AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID }
    val lastGood = connectChampion?.takeIf { candidate ->
        val endpoint = canonicalEndpointKey(candidate.edge.address, candidate.edge.port)
        savedRoute == null || canonicalEndpointKey(savedRoute.edge.address, savedRoute.edge.port) != endpoint
    }
    val reservedEndpoints = buildSet {
        lastGood?.let { add(canonicalEndpointKey(it.edge.address, it.edge.port)) }
        diagnostic?.let { add(canonicalEndpointKey(it.edge.address, it.edge.port)) }
    }
    val learned = learnedId?.let { id ->
        rawPool.firstOrNull { candidate ->
            candidate.id == id &&
                candidate.id != AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID &&
                canonicalEndpointKey(candidate.edge.address, candidate.edge.port) !in reservedEndpoints
        }?.copy(learned = true)
    }
    return buildList {
        if (savedRoute != null) add(savedRoute)
        if (savedBackupRoute != null) add(savedBackupRoute)
        if (lastGood != null) add(lastGood.copy(learned = true))
        if (diagnostic != null) {
            add(diagnostic.copy(learned = learnedId == AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID))
        }
        if (learned != null && learned.id != savedRoute?.id && learned.id != lastGood?.id) add(learned)
        addAll(
            rawPool.filterNot { candidate ->
                candidate.id == learnedId ||
                    candidate.id == AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID ||
                    candidate.id == savedRoute?.id ||
                    candidate.id == savedBackupRoute?.id ||
                    candidate.id == lastGood?.id ||
                    canonicalEndpointKey(candidate.edge.address, candidate.edge.port) in reservedEndpoints
            },
        )
    }.distinctBy(AdaptiveCandidate::id)
}

internal fun AdaptiveCandidate.isDirectCompatRoute(profile: ProxyProfile): Boolean {
    if (profile.usesAdvancedSettingsIdentity() || LocalForwardProfile.isLocalForward(profile)) return false
    val direct = DirectCompatProfileParser.parse(profile)
    val originalAddress = direct?.address ?: profile.serverHost
    val originalPort = direct?.port ?: profile.serverPort
    return canonicalEndpointKey(edge.address, edge.port) == canonicalEndpointKey(originalAddress, originalPort) &&
        !runtimeOptions.finalmaskEnabled &&
        runtimeOptions.identityOverride != null &&
        runtimeOptions.preserveEmptyAlpn &&
        runtimeOptions.preserveTransportFields
}

data class AdaptiveSavedRoute(
    val id: String,
    val label: String,
    val address: String,
    val port: Int,
    val role: String,
    val maxSplit: Int,
    val resolverUrl: String,
    val finalmaskPacket: String,
    val finalmaskLength: Int,
    val finalmaskDelayMs: Int,
    val tunMtu: Int,
    val finalmaskEnabled: Boolean,
    val directCompat: Boolean,
    val score: Int = 0,
    val pingMs: Long? = null,
    val jitterMs: Long? = null,
    val uploadKbps: Long = 0L,
    val downloadKbps: Long = 0L,
    val confidence: Int = 0,
    val mtuValidated: Boolean = false,
    val savedAtMs: Long = 0L,
    val muxEnabledOverride: Boolean? = null,
    val tlsCompatible: Boolean = true,
)

data class AdaptiveRouteMetrics(
    val score: Int,
    val pingMs: Long? = null,
    val jitterMs: Long? = null,
    val uploadKbps: Long = 0L,
    val downloadKbps: Long = 0L,
    val confidence: Int = 0,
    val mtuValidated: Boolean = false,
    val tlsCompatible: Boolean = true,
)

class AdaptiveProfileStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("adaptive_connection_profiles_v1", Context.MODE_PRIVATE)
    private val edgeHistoryStore = CloudflareEdgeHistoryStore(context.applicationContext)

    fun winner(network: NetworkFingerprint, profile: ProxyProfile, signature: String): String? {
        val key = key("winner", network.exactStorageKey(), profile.id, signature)
        val updated = prefs.getLong("$key:updated", 0L)
        if (updated <= 0L || System.currentTimeMillis() - updated > WINNER_TTL_MS) return null
        return prefs.getString("$key:id", null)
    }

    fun recordWinner(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidate: AdaptiveCandidate,
        score: Int,
    ) {
        val key = key("winner", network.exactStorageKey(), profile.id, signature)
        val failureKey = failureKey(network, profile, signature, candidate.id)
        prefs.edit()
            .putString("$key:id", candidate.id)
            .putInt("$key:score", score)
            .putLong("$key:updated", System.currentTimeMillis())
            .remove(failureKey)
            .remove("$failureKey:count")
            .apply()
    }

    fun savedRoute(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
    ): AdaptiveSavedRoute? {
        val key = key("route", network.exactStorageKey(), profile.id, signature)
        val updated = prefs.getLong("$key:updated", 0L)
        if (updated <= 0L || System.currentTimeMillis() - updated > WINNER_TTL_MS) return null
        val id = prefs.getString("$key:id", null).orEmpty()
        val address = prefs.getString("$key:address", null).orEmpty()
        val port = prefs.getInt("$key:port", 0)
        if (id.isBlank() || address.isBlank() || port !in 1..65_535) return null
        return AdaptiveSavedRoute(
            id = id,
            label = prefs.getString("$key:label", null).orEmpty().ifBlank { "Saved full-matrix route" },
            address = address,
            port = port,
            role = prefs.getString("$key:role", null).orEmpty().ifBlank { "full-matrix" },
            maxSplit = prefs.getInt("$key:maxSplit", 2).coerceIn(1, 10_000),
            resolverUrl = prefs.getString("$key:resolver", null).orEmpty(),
            finalmaskPacket = prefs.getString("$key:packet", null).orEmpty().ifBlank { "tlshello" },
            finalmaskLength = prefs.getInt("$key:length", 5).coerceIn(1, 65_535),
            finalmaskDelayMs = prefs.getInt("$key:delay", 0).coerceIn(0, 60_000),
            tunMtu = prefs.getInt("$key:mtu", 1_280).coerceIn(576, 9_000),
            finalmaskEnabled = prefs.getBoolean("$key:fragment", true),
            directCompat = prefs.getBoolean("$key:direct", false),
            score = prefs.getInt("$key:score", 0),
            pingMs = prefs.getLong("$key:ping", -1L).takeIf { it >= 0L },
            jitterMs = prefs.getLong("$key:jitter", -1L).takeIf { it >= 0L },
            uploadKbps = prefs.getLong("$key:upload", 0L),
            downloadKbps = prefs.getLong("$key:download", 0L),
            confidence = prefs.getInt("$key:confidence", 0),
            mtuValidated = prefs.getBoolean("$key:mtuValidated", false),
            savedAtMs = updated,
            muxEnabledOverride = prefs.getInt("$key:muxOverride", -1).let { value ->
                when (value) { 0 -> false; 1 -> true; else -> null }
            },
            tlsCompatible = prefs.getBoolean("$key:tlsCompatible", true),
        )
    }

    fun savedBackupRoute(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
    ): AdaptiveSavedRoute? {
        val key = key("route-backup", network.exactStorageKey(), profile.id, signature)
        val updated = prefs.getLong("$key:updated", 0L)
        if (updated <= 0L || System.currentTimeMillis() - updated > WINNER_TTL_MS) return null
        val id = prefs.getString("$key:id", null).orEmpty()
        val address = prefs.getString("$key:address", null).orEmpty()
        val port = prefs.getInt("$key:port", 0)
        if (id.isBlank() || address.isBlank() || port !in 1..65_535) return null
        return AdaptiveSavedRoute(
            id = id,
            label = prefs.getString("$key:label", null).orEmpty().ifBlank { "Route Tournament backup" },
            address = address,
            port = port,
            role = prefs.getString("$key:role", null).orEmpty().ifBlank { "route-backup" },
            maxSplit = prefs.getInt("$key:maxSplit", 2).coerceIn(1, 10_000),
            resolverUrl = prefs.getString("$key:resolver", null).orEmpty(),
            finalmaskPacket = prefs.getString("$key:packet", null).orEmpty().ifBlank { "tlshello" },
            finalmaskLength = prefs.getInt("$key:length", 5).coerceIn(1, 65_535),
            finalmaskDelayMs = prefs.getInt("$key:delay", 0).coerceIn(0, 60_000),
            tunMtu = prefs.getInt("$key:mtu", 1_280).coerceIn(576, 9_000),
            finalmaskEnabled = prefs.getBoolean("$key:fragment", true),
            directCompat = prefs.getBoolean("$key:direct", false),
            score = prefs.getInt("$key:score", 0),
            pingMs = prefs.getLong("$key:ping", -1L).takeIf { it >= 0L },
            jitterMs = prefs.getLong("$key:jitter", -1L).takeIf { it >= 0L },
            uploadKbps = prefs.getLong("$key:upload", 0L),
            downloadKbps = prefs.getLong("$key:download", 0L),
            confidence = prefs.getInt("$key:confidence", 0),
            mtuValidated = prefs.getBoolean("$key:mtuValidated", false),
            savedAtMs = updated,
            muxEnabledOverride = prefs.getInt("$key:muxOverride", -1).let { value ->
                when (value) { 0 -> false; 1 -> true; else -> null }
            },
            tlsCompatible = prefs.getBoolean("$key:tlsCompatible", true),
        )
    }

    fun recordSavedRoute(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidate: AdaptiveCandidate,
        score: Int,
        metrics: AdaptiveRouteMetrics = AdaptiveRouteMetrics(score),
    ) {
        recordWinner(network, profile, signature, candidate, score)
        val key = key("route", network.exactStorageKey(), profile.id, signature)
        val directEndpoint = candidate.isDirectCompatRoute(profile)
        prefs.edit()
            .putString("$key:id", candidate.id)
            .putString("$key:label", candidate.label)
            .putString("$key:address", candidate.edge.address)
            .putInt("$key:port", candidate.edge.port)
            .putString("$key:role", candidate.edge.role)
            .putInt("$key:maxSplit", candidate.edge.finalmaskMaxSplit)
            .putString("$key:resolver", candidate.settings.dnsResolverUrl)
            .putString("$key:packet", candidate.settings.finalmaskPacket)
            .putInt("$key:length", candidate.settings.finalmaskLength)
            .putInt("$key:delay", candidate.settings.finalmaskDelayMs)
            .putInt("$key:mtu", candidate.settings.tunMtu)
            .putBoolean("$key:fragment", candidate.runtimeOptions.finalmaskEnabled)
            .putBoolean("$key:direct", directEndpoint)
            .putInt("$key:score", metrics.score)
            .putLong("$key:ping", metrics.pingMs ?: -1L)
            .putLong("$key:jitter", metrics.jitterMs ?: -1L)
            .putLong("$key:upload", metrics.uploadKbps)
            .putLong("$key:download", metrics.downloadKbps)
            .putInt("$key:confidence", metrics.confidence)
            .putBoolean("$key:mtuValidated", metrics.mtuValidated)
            .putInt("$key:muxOverride", candidate.runtimeOptions.muxEnabledOverride?.let { if (it) 1 else 0 } ?: -1)
            .putBoolean("$key:tlsCompatible", metrics.tlsCompatible)
            .putLong("$key:updated", System.currentTimeMillis())
            .apply()
        edgeHistoryStore.record(signature, network, candidate, metrics.score)
    }

    fun recordSavedBackupRoute(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidate: AdaptiveCandidate,
        score: Int,
        metrics: AdaptiveRouteMetrics = AdaptiveRouteMetrics(score),
    ) {
        val key = key("route-backup", network.exactStorageKey(), profile.id, signature)
        val candidateFailureKey = failureKey(network, profile, signature, candidate.id)
        val directEndpoint = candidate.isDirectCompatRoute(profile)
        prefs.edit()
            .putString("$key:id", candidate.id)
            .putString("$key:label", candidate.label)
            .putString("$key:address", candidate.edge.address)
            .putInt("$key:port", candidate.edge.port)
            .putString("$key:role", candidate.edge.role)
            .putInt("$key:maxSplit", candidate.edge.finalmaskMaxSplit)
            .putString("$key:resolver", candidate.settings.dnsResolverUrl)
            .putString("$key:packet", candidate.settings.finalmaskPacket)
            .putInt("$key:length", candidate.settings.finalmaskLength)
            .putInt("$key:delay", candidate.settings.finalmaskDelayMs)
            .putInt("$key:mtu", candidate.settings.tunMtu)
            .putBoolean("$key:fragment", candidate.runtimeOptions.finalmaskEnabled)
            .putBoolean("$key:direct", directEndpoint)
            .putInt("$key:score", metrics.score)
            .putLong("$key:ping", metrics.pingMs ?: -1L)
            .putLong("$key:jitter", metrics.jitterMs ?: -1L)
            .putLong("$key:upload", metrics.uploadKbps)
            .putLong("$key:download", metrics.downloadKbps)
            .putInt("$key:confidence", metrics.confidence)
            .putBoolean("$key:mtuValidated", metrics.mtuValidated)
            .putInt("$key:muxOverride", candidate.runtimeOptions.muxEnabledOverride?.let { if (it) 1 else 0 } ?: -1)
            .putBoolean("$key:tlsCompatible", metrics.tlsCompatible)
            .putLong("$key:updated", System.currentTimeMillis())
            .remove(candidateFailureKey)
            .remove("$candidateFailureKey:count")
            .apply()
        edgeHistoryStore.record(signature, network, candidate, metrics.score)
    }

    fun clearSavedBackupRoute(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
    ) {
        val key = key("route-backup", network.exactStorageKey(), profile.id, signature)
        val candidateId = prefs.getString("$key:id", null)
        val editor = prefs.edit()
        SAVED_ROUTE_SUFFIXES.forEach { suffix -> editor.remove("$key:$suffix") }
        candidateId?.takeIf(String::isNotBlank)?.let { id ->
            val candidateFailureKey = failureKey(network, profile, signature, id)
            editor.remove(candidateFailureKey)
            editor.remove("$candidateFailureKey:count")
        }
        editor.apply()
    }

    fun recordFailure(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidateId: String,
    ) {
        val key = failureKey(network, profile, signature, candidateId)
        val now = System.currentTimeMillis()
        val previous = prefs.getLong(key, 0L)
        val previousCount = prefs.getInt("$key:count", 0)
        val count = nextFailureCount(previous, previousCount, now, FAILURE_STREAK_WINDOW_MS)
        prefs.edit()
            .putLong(key, now)
            .putInt("$key:count", count)
            .apply()
    }

    fun isCoolingDown(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidateId: String,
    ): Boolean {
        val key = failureKey(network, profile, signature, candidateId)
        val failedAt = prefs.getLong(key, 0L)
        val failureCount = prefs.getInt("$key:count", 0)
        return isFailureCoolingDown(
            failedAtMs = failedAt,
            failureCount = failureCount,
            nowMs = System.currentTimeMillis(),
            cooldownMs = FAILURE_COOLDOWN_MS,
            threshold = FAILURE_STREAK_THRESHOLD,
        )
    }

    private fun failureKey(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidateId: String,
    ): String = key("failure", network.exactStorageKey(), profile.id, signature, candidateId)

    private fun key(vararg values: String): String = sha256(values.joinToString("|"))

    companion object {
        private const val WINNER_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val FAILURE_COOLDOWN_MS = 2L * 60L * 1_000L
        private const val FAILURE_STREAK_WINDOW_MS = 10L * 60L * 1_000L
        private const val FAILURE_STREAK_THRESHOLD = 2
        private val SAVED_ROUTE_SUFFIXES = listOf(
            "id", "label", "address", "port", "role", "maxSplit", "resolver", "packet", "length",
            "delay", "mtu", "fragment", "direct", "score", "ping", "jitter", "upload", "download",
            "confidence", "mtuValidated", "muxOverride", "tlsCompatible", "updated",
        )
    }
}

class AdaptiveCandidatePlanner(
    private val store: AdaptiveProfileStore,
    private val edgePoolStore: ConnectEdgePoolStore? = null,
) {
    fun signature(settings: AdvancedSettingsData, profile: ProxyProfile): String =
        signatureFor(settings, profile)

    fun candidates(
        base: AdvancedSettingsData,
        network: NetworkFingerprint,
        profile: ProxyProfile,
        poolOverride: List<MciEdge>? = null,
        includeSavedRoutes: Boolean = true,
    ): List<AdaptiveCandidate> = connectPlan(
        base = base,
        network = network,
        profile = profile,
        poolOverride = poolOverride,
        includeSavedRoutes = includeSavedRoutes,
    ).candidates

    fun connectPlan(
        base: AdvancedSettingsData,
        network: NetworkFingerprint,
        profile: ProxyProfile,
        poolOverride: List<MciEdge>? = null,
        includeSavedRoutes: Boolean = true,
    ): AdaptiveConnectPlan {
        val settings = base.validated()
        val signature = signature(settings, profile)
        val primary = MciEdge(settings.primaryAddress, settings.primaryPort, "primary", settings.primaryMaxSplit)
        val irancell = MciEdge(settings.irancellAddress, settings.irancellPort, "irancell", settings.irancellMaxSplit)
        val fallback = MciEdge(settings.fallbackAddress, settings.fallbackPort, "fallback", settings.fallbackMaxSplit)
        val cdnRescueA = MciEdge(settings.telegramFallbackAddress, settings.telegramPort, "cdn-rescue-a", 2)
        val cdnRescueB = MciEdge(settings.telegramAddress, settings.telegramPort, "cdn-rescue-b", 100)
        val directCompat = if (!profile.usesAdvancedSettingsIdentity()) {
            DirectCompatProfileParser.parse(profile)
        } else {
            null
        }
        val savedRoute = store.savedRoute(network, profile, signature)
            ?.toCandidate(settings, profile)
            ?.copy(learned = true)
            ?.takeIf { includeSavedRoutes }
        val savedBackupRoute = store.savedBackupRoute(network, profile, signature)
            ?.takeIf { includeSavedRoutes && it.id != savedRoute?.id }
            ?.toCandidate(settings, profile)
            ?.copy(learned = false)
        val directCandidate = directCompat?.let { direct ->
            AdaptiveCandidate(
                id = MCI_DIRECT_COMPAT_ID,
                label = "Direct profile compatibility",
                edge = MciEdge(
                    address = direct.address,
                    port = direct.port,
                    role = MCI_DIRECT_COMPAT_ID,
                    finalmaskMaxSplit = 1,
                ),
                settings = settings,
                runtimeOptions = MciXrayRuntimeOptions(
                    identityOverride = direct.identity,
                    finalmaskEnabled = false,
                    preserveEmptyAlpn = true,
                    preserveTransportFields = true,
                ),
            )
        }
        val raw = when (network.carrierClass) {
            "mci" -> buildList {
                directCandidate?.let(::add)
                add(candidate("uac-primary-google", "UAC SNI primary + Google DNS", primary, settings, AdaptiveDnsResolvers.GOOGLE))
                add(candidate("uac-primary-cloudflare-fast", "UAC SNI low delay + Cloudflare DNS", primary, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.CLOUDFLARE))
                MCI_EDGE_POOL_ADDRESSES.forEachIndexed { index, address ->
                    val edge = MciEdge(
                        address = address,
                        port = MCI_EDGE_POOL_PORT,
                        role = "uac-pool-${index + 1}",
                        finalmaskMaxSplit = settings.primaryMaxSplit,
                    )
                    val resolver = MCI_EDGE_POOL_RESOLVERS[index % MCI_EDGE_POOL_RESOLVERS.size]
                    add(
                        candidate(
                            id = "uac-edge-pool-${address.replace('.', '-')}",
                            label = "UAC SNI edge $address",
                            edge = edge,
                            settings = settings,
                            resolver = resolver,
                        ),
                    )
                }
                add(candidate("uac-fallback-quad9", "UAC SNI fallback + Quad9 DNS", fallback, settings, AdaptiveDnsResolvers.QUAD9))
                add(candidate("uac-cdn-a-adguard", "UAC SNI CDN A + AdGuard DNS", cdnRescueA, settings.copy(finalmaskDelayMs = 15), AdaptiveDnsResolvers.ADGUARD))
                add(candidate("uac-cdn-b-opendns", "UAC SNI CDN B + OpenDNS", cdnRescueB, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.OPENDNS))
                add(candidate("uac-primary-deep-google", "UAC SNI deep-fragment rescue", primary.copy(finalmaskMaxSplit = 100), settings.copy(finalmaskDelayMs = 5), AdaptiveDnsResolvers.GOOGLE))
            }
            "irancell" -> buildList {
                directCandidate?.let(::add)
                add(candidate("irancell-deep-cloudflare", "Irancell deep + Cloudflare DNS", irancell, settings, AdaptiveDnsResolvers.CLOUDFLARE))
                add(candidate("irancell-primary-google-fast", "Irancell low delay + Google DNS", primary, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.GOOGLE))
                add(candidate("irancell-fallback-quad9", "Irancell fallback + Quad9 DNS", fallback, settings, AdaptiveDnsResolvers.QUAD9))
                add(candidate("irancell-cdn-a-adguard", "Irancell CDN A + AdGuard DNS", cdnRescueA.copy(finalmaskMaxSplit = 100), settings.copy(finalmaskDelayMs = 15), AdaptiveDnsResolvers.ADGUARD))
                add(candidate("irancell-cdn-b-opendns", "Irancell CDN B + OpenDNS", cdnRescueB, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.OPENDNS))
                add(candidate("irancell-primary-cloudflare", "Irancell standard rescue", primary, settings, AdaptiveDnsResolvers.CLOUDFLARE))
            }
            else -> buildList {
                directCandidate?.let(::add)
                add(candidate("fixed-primary-cloudflare", "Primary + Cloudflare DNS", primary, settings, AdaptiveDnsResolvers.CLOUDFLARE))
                add(candidate("fixed-primary-google-fast", "Low delay + Google DNS", primary, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.GOOGLE))
                add(candidate("fixed-fallback-quad9", "Fallback + Quad9 DNS", fallback, settings, AdaptiveDnsResolvers.QUAD9))
                add(candidate("fixed-cdn-a-adguard", "CDN A + AdGuard DNS", cdnRescueA, settings.copy(finalmaskDelayMs = 15), AdaptiveDnsResolvers.ADGUARD))
                add(candidate("fixed-cdn-b-opendns", "CDN B + OpenDNS", cdnRescueB, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.OPENDNS))
                add(candidate("fixed-primary-deep-google", "Deep-fragment rescue", primary.copy(finalmaskMaxSplit = 100), settings.copy(finalmaskDelayMs = 5), AdaptiveDnsResolvers.GOOGLE))
            }
        }
        val savedChampionEdge = if (includeSavedRoutes) {
            edgePoolStore?.champion(network, profile.id)
        } else {
            null
        }
        val pool = when {
            poolOverride != null -> ConnectPoolSelection(
                poolWithChampionFirst(poolOverride, savedChampionEdge),
                ConnectPoolSelection.SOURCE_RESCUE,
            )
            else -> {
                val selection = resolveConnectPool(
                    thisPool = edgePoolStore?.pool(network, profile.id),
                    lastPool = edgePoolStore?.lastPool(profile.id),
                    lastPoolKey = edgePoolStore?.lastPoolKey(profile.id),
                    thisKey = connectPoolScopeKey(network.learningKey(), profile.id),
                )
                selection.copy(edges = poolWithChampionFirst(selection.edges, savedChampionEdge))
            }
        }
        val pooled = applyConnectEdgePool(raw, pool.edges)
        val template = pooled.firstOrNull { it.id != MCI_DIRECT_COMPAT_ID }
        val connectChampion = savedChampionEdge?.takeIf(::persistableConnectEdge)?.let { edge ->
            AdaptiveCandidate(
                id = CONNECT_LAST_GOOD_ID,
                label = "Last good connect ${edge.address}:${edge.port}",
                edge = edge.copy(role = "connect-last-good"),
                settings = template?.settings ?: settings,
                runtimeOptions = template?.runtimeOptions ?: MciXrayRuntimeOptions.DEFAULT,
                learned = true,
            )
        }?.takeIf { includeSavedRoutes }
        val learnedId = store.winner(network, profile, signature).takeIf { includeSavedRoutes }
        val ordered = prioritizeAdaptiveCandidates(
            raw = pooled,
            savedRoute = savedRoute,
            savedBackupRoute = savedBackupRoute,
            learnedId = learnedId,
            maxAdaptiveCandidates = MAX_CANDIDATES,
            connectChampion = connectChampion,
        )
        val (ready, coolingDown) = ordered.partition {
            !store.isCoolingDown(network, profile, signature, it.id)
        }
        return AdaptiveConnectPlan(
            candidates = (ready + coolingDown).distinctBy(AdaptiveCandidate::id),
            pool = pool,
        )
    }

    fun routeSpeedCandidates(
        base: AdvancedSettingsData,
        network: NetworkFingerprint,
        profile: ProxyProfile,
        discoveredEdges: List<MciEdge>? = null,
        cloudflareEligible: Boolean = true,
    ): List<AdaptiveCandidate> {
        val settings = base.validated()
        val fallbackEdges = buildList {
            add(MciEdge(settings.primaryAddress, settings.primaryPort, "primary", settings.primaryMaxSplit))
            add(MciEdge(settings.irancellAddress, settings.irancellPort, "alternate", settings.irancellMaxSplit))
            add(MciEdge(settings.fallbackAddress, settings.fallbackPort, "fallback", settings.fallbackMaxSplit))
            add(MciEdge(settings.telegramFallbackAddress, settings.telegramPort, "cdn-a", 2))
            add(MciEdge(settings.telegramAddress, settings.telegramPort, "cdn-b", 100))
            MCI_EDGE_POOL_ADDRESSES.forEachIndexed { index, address ->
                add(MciEdge(address, MCI_EDGE_POOL_PORT, "edge-${index + 1}", settings.primaryMaxSplit))
            }
        }
        val edges = discoveredEdges.orEmpty()
            .ifEmpty { fallbackEdges }
            .distinctBy { canonicalEndpointKey(it.address, it.port) }
            .take(RouteTestArchitecture.EDGE_LIMIT)
        val mtuValues = RouteTestArchitecture.mtuValues(settings.tunMtu)
        val tuningProfiles = RouteTestArchitecture.tuningProfiles(settings)
        val localForward = LocalForwardProfile.isLocalForward(profile)
        val directProfile = if (!profile.usesAdvancedSettingsIdentity() && !localForward) {
            DirectCompatProfileParser.parse(profile) ?: DirectCompatProfile(
                address = profile.serverHost,
                port = profile.serverPort,
                identity = profile.runtimeIdentity(settings),
            )
        } else {
            null
        }
        val localForwardIdentity = if (localForward) {
            LocalForwardProfile.routingIdentity(profile, settings)
        } else {
            null
        }
        val matrixRuntimeBase = when {
            directProfile != null -> MciXrayRuntimeOptions(
                identityOverride = directProfile.identity,
                finalmaskEnabled = false,
                preserveEmptyAlpn = true,
                preserveTransportFields = true,
            )
            localForwardIdentity != null -> MciXrayRuntimeOptions(
                identityOverride = localForwardIdentity,
                finalmaskEnabled = false,
                preserveEmptyAlpn = true,
                preserveTransportFields = true,
            )
            else -> MciXrayRuntimeOptions.DEFAULT
        }

        if (!cloudflareEligible && directProfile != null) {
            val directEdge = MciEdge(directProfile.address, directProfile.port, "direct", 1)
            val directCandidates = buildList {
                for (resolver in AdaptiveDnsResolvers.all) {
                    for (mtu in mtuValues) {
                        add(
                            AdaptiveCandidate(
                                id = "matrix-direct-${resolver.id}-$mtu",
                                label = "Direct profile • ${resolver.label} • MTU $mtu",
                                edge = directEdge,
                                settings = settings.copy(
                                    dnsResolverUrl = resolver.url,
                                    tunMtu = mtu,
                                ).validated(),
                                runtimeOptions = MciXrayRuntimeOptions(
                                    identityOverride = directProfile.identity,
                                    finalmaskEnabled = false,
                                    preserveEmptyAlpn = true,
                                    preserveTransportFields = true,
                                ),
                            ),
                        )
                    }
                }
            }
            val savedId = store.savedRoute(network, profile, signature(settings, profile))?.id
            return directCandidates.map { candidate ->
                if (candidate.id == savedId) candidate.copy(learned = true) else candidate
            }
        }
        val matrix = buildList {
            for (edge in edges) {
                for (resolver in AdaptiveDnsResolvers.all) {
                    for (tuning in tuningProfiles) {
                        for (mtu in mtuValues) {
                            val id = listOf(
                                "matrix",
                                canonicalEndpointKey(edge.address, edge.port)
                                    .replace(':', '-').replace('.', '-'),
                                edge.port,
                                resolver.id,
                                tuning.id,
                                mtu,
                            ).joinToString("-")
                            add(
                                AdaptiveCandidate(
                                    id = id,
                                    label = "${edge.role} • ${resolver.label} • ${tuning.label} • MTU $mtu",
                                    edge = edge.copy(finalmaskMaxSplit = tuning.maxSplit),
                                    settings = settings.copy(
                                        dnsResolverUrl = resolver.url,
                                        finalmaskDelayMs = tuning.delayMs,
                                        tunMtu = mtu,
                                    ).validated(),
                                    runtimeOptions = RouteTestArchitecture.runtimeOptions(
                                        matrixRuntimeBase,
                                        tuning,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
        val direct = if (localForward) {
            null
        } else {
            directProfile?.let { parsed ->
                AdaptiveCandidate(
                    id = "matrix-direct-compat",
                    label = "Direct profile • original transport",
                    edge = MciEdge(parsed.address, parsed.port, "direct", 1),
                    settings = settings,
                    runtimeOptions = MciXrayRuntimeOptions(
                        identityOverride = parsed.identity,
                        finalmaskEnabled = false,
                        preserveEmptyAlpn = true,
                        preserveTransportFields = true,
                    ),
                )
            }
        }
        val savedId = store.savedRoute(network, profile, signature(settings, profile))?.id
        return buildList {
            if (direct != null) add(direct.copy(learned = direct.id == savedId))
            addAll(matrix.map { if (it.id == savedId) it.copy(learned = true) else it })
        }.distinctBy(AdaptiveCandidate::id)
    }

    fun candidateFromSavedRoute(
        saved: AdaptiveSavedRoute,
        base: AdvancedSettingsData,
        profile: ProxyProfile,
    ): AdaptiveCandidate? = saved.toCandidate(base, profile)

    private fun AdaptiveSavedRoute.toCandidate(
        base: AdvancedSettingsData,
        profile: ProxyProfile,
    ): AdaptiveCandidate? {
        val runtimeOptions = if (directCompat) {
            val parsed = DirectCompatProfileParser.parse(profile) ?: DirectCompatProfile(
                address = profile.serverHost,
                port = profile.serverPort,
                identity = profile.runtimeIdentity(base),
            )
            MciXrayRuntimeOptions(
                identityOverride = parsed.identity,
                finalmaskEnabled = false,
                preserveEmptyAlpn = true,
                preserveTransportFields = true,
            )
        } else {
            val parsedIdentity = if (profile.usesAdvancedSettingsIdentity()) {
                null
            } else if (LocalForwardProfile.isLocalForward(profile)) {
                LocalForwardProfile.routingIdentity(profile, base)
            } else {
                DirectCompatProfileParser.parse(profile)?.identity ?: profile.runtimeIdentity(base)
            }
            MciXrayRuntimeOptions(
                identityOverride = parsedIdentity,
                finalmaskEnabled = finalmaskEnabled,
                preserveEmptyAlpn = !profile.usesAdvancedSettingsIdentity(),
                preserveTransportFields = !profile.usesAdvancedSettingsIdentity(),
                muxEnabledOverride = muxEnabledOverride,
            )
        }
        return AdaptiveCandidate(
            id = id,
            label = label,
            edge = MciEdge(address, port, role, maxSplit),
            settings = base.copy(
                dnsResolverUrl = resolverUrl.ifBlank { base.dnsResolverUrl },
                finalmaskPacket = finalmaskPacket,
                finalmaskLength = finalmaskLength,
                finalmaskDelayMs = finalmaskDelayMs,
                tunMtu = tunMtu,
            ).validated(),
            runtimeOptions = runtimeOptions,
            learned = true,
        )
    }

    private fun candidate(
        id: String,
        label: String,
        edge: MciEdge,
        settings: AdvancedSettingsData,
        resolver: AdaptiveDnsResolver,
    ) = AdaptiveCandidate(id, label, edge, settings.copy(dnsResolverUrl = resolver.url).validated())

    companion object {
        const val MAX_CANDIDATES = 11
        private const val STRATEGY_VERSION = "adaptive-v9-offline-fingerprint-direct-signature"
        const val MCI_DIRECT_COMPAT_ID = "uac-direct-compat"
        const val CONNECT_LAST_GOOD_ID = "connect-last-good"
        private const val MCI_EDGE_POOL_PORT = 443
        private val MCI_EDGE_POOL_ADDRESSES = listOf(
            "104.26.14.85",
            "188.114.97.6",
            "104.21.71.238",
            "104.17.148.22",
        )
        private val MCI_EDGE_POOL_RESOLVERS = listOf(
            AdaptiveDnsResolvers.GOOGLE,
            AdaptiveDnsResolvers.CLOUDFLARE,
            AdaptiveDnsResolvers.QUAD9,
            AdaptiveDnsResolvers.ADGUARD,
        )

        internal fun signatureFor(settings: AdvancedSettingsData, profile: ProxyProfile): String {
            val direct = DirectCompatProfileParser.parse(profile)
            val endpointAddress = when {
                LocalForwardProfile.isLocalForward(profile) -> LocalForwardProfile.routingEndpointKey(profile)
                direct != null -> canonicalEndpointKey(direct.address, direct.port)
                else -> canonicalEndpointKey(profile.serverHost, profile.serverPort)
            }
            val runtimeIdentity = when {
                LocalForwardProfile.isLocalForward(profile) ->
                    LocalForwardProfile.routingIdentity(profile, settings)
                direct != null -> direct.identity
                else -> profile.runtimeIdentity(settings)
            }
            return sha256(
                listOf(
                    STRATEGY_VERSION,
                    profile.id,
                    endpointAddress,
                    runtimeIdentity,
                    settings,
                ).joinToString("|"),
            ).take(24)
        }
    }

}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
