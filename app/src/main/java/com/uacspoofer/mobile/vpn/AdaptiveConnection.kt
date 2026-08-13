package com.uacspoofer.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
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
}

class NetworkFingerprintResolver(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val telephony = context.applicationContext
        .getSystemService(TelephonyManager::class.java)
    @Volatile private var preferredNetworkHandle = -1L
    @Volatile private var identityCache: IdentityCache? = null
    @Volatile private var fingerprintKeyCache: FingerprintKeyCache? = null

    fun capture(): NetworkFingerprint {
        val selected = selectUnderlyingNetwork()
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

    suspend fun captureAdaptive(): NetworkFingerprint {
        val base = capture()
        if (base.networkHandle < 0L) return base
        if (base.transport == "cellular" && base.carrierClass != "unknown") {
            return base.copy(key = pinFingerprintKey(base.networkHandle, stableKey(base, "sim", base.carrierClass)))
        }
        val identity = resolveNetworkIdentity(base.networkHandle)
            ?: return base.copy(key = pinFingerprintKey(base.networkHandle, base.key))
        val detectedClass = classifyProvider(identity.provider).takeIf { it != "unknown" } ?: base.carrierClass
        return base.copy(
            key = pinFingerprintKey(base.networkHandle, stableKey(base, identity.asn, detectedClass)),
            carrier = identity.provider.ifBlank { base.carrier },
            carrierClass = detectedClass,
            networkAsn = identity.asn,
            networkProvider = identity.provider,
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
            connection.setRequestProperty("User-Agent", "UAC-SNI-Spoofer-Android/2.0.0")
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

    private fun stableKey(base: NetworkFingerprint, asn: String, carrierClass: String): String = sha256(
        listOf(
            base.transport,
            asn,
            carrierClass,
            base.mtu,
            base.hasIpv4,
            base.hasIpv6,
            base.metered,
        ).joinToString("|"),
    ).take(20)

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
    }
}

class AdaptiveProfileStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("adaptive_connection_profiles_v1", Context.MODE_PRIVATE)

    fun winner(network: NetworkFingerprint, profile: ProxyProfile, signature: String): String? {
        val key = key("winner", network.learningKey(), profile.id, signature)
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
        val key = key("winner", network.learningKey(), profile.id, signature)
        val failureKey = failureKey(network, profile, signature, candidate.id)
        prefs.edit()
            .putString("$key:id", candidate.id)
            .putInt("$key:score", score)
            .putLong("$key:updated", System.currentTimeMillis())
            .remove(failureKey)
            .remove("$failureKey:count")
            .apply()
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
    ): String = key("failure", network.learningKey(), profile.id, signature, candidateId)

    private fun key(vararg values: String): String = sha256(values.joinToString("|"))

    companion object {
        private const val WINNER_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val FAILURE_COOLDOWN_MS = 2L * 60L * 1_000L
        private const val FAILURE_STREAK_WINDOW_MS = 10L * 60L * 1_000L
        private const val FAILURE_STREAK_THRESHOLD = 2
    }
}

class AdaptiveCandidatePlanner(private val store: AdaptiveProfileStore) {
    fun signature(settings: AdvancedSettingsData, profile: ProxyProfile): String = sha256(
        listOf(
            STRATEGY_VERSION,
            profile.id,
            settings.connectionMode,
            settings.primaryAddress,
            settings.primaryPort,
            settings.primaryMaxSplit,
            settings.irancellAddress,
            settings.irancellPort,
            settings.irancellMaxSplit,
            settings.fallbackAddress,
            settings.fallbackPort,
            settings.fallbackMaxSplit,
            settings.telegramAddress,
            settings.telegramFallbackAddress,
            settings.telegramPort,
            settings.transportNetwork,
            settings.transportSecurity,
            settings.tlsSni,
            settings.wsHost,
            settings.wsPath,
            settings.finalmaskPacket,
            settings.finalmaskLength,
            settings.finalmaskDelayMs,
            settings.tunMtu,
            settings.nativeDns,
            settings.dnsResolverUrl,
        ).joinToString("|"),
    ).take(24)

    fun candidates(
        base: AdvancedSettingsData,
        network: NetworkFingerprint,
        profile: ProxyProfile,
    ): List<AdaptiveCandidate> {
        val settings = base.validated()
        val signature = signature(settings, profile)
        val primary = MciEdge(settings.primaryAddress, settings.primaryPort, "primary", settings.primaryMaxSplit)
        val irancell = MciEdge(settings.irancellAddress, settings.irancellPort, "irancell", settings.irancellMaxSplit)
        val fallback = MciEdge(settings.fallbackAddress, settings.fallbackPort, "fallback", settings.fallbackMaxSplit)
        val cdnRescueA = MciEdge(settings.telegramFallbackAddress, settings.telegramPort, "cdn-rescue-a", 2)
        val cdnRescueB = MciEdge(settings.telegramAddress, settings.telegramPort, "cdn-rescue-b", 100)
        val directCompat = if (network.carrierClass == "mci" && !profile.isBuiltIn) {
            DirectCompatProfileParser.parse(profile)
        } else {
            null
        }
        val raw = when (network.carrierClass) {
            "mci" -> buildList {
                directCompat?.let { direct ->
                    add(
                        AdaptiveCandidate(
                            id = MCI_DIRECT_COMPAT_ID,
                            label = "MCI direct profile compatibility",
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
                        ),
                    )
                }
                add(candidate("mci-primary-google", "MCI primary + Google DNS", primary, settings, AdaptiveDnsResolvers.GOOGLE))
                add(candidate("mci-primary-cloudflare-fast", "MCI low delay + Cloudflare DNS", primary, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.CLOUDFLARE))
                MCI_EDGE_POOL_ADDRESSES.forEachIndexed { index, address ->
                    val edge = MciEdge(
                        address = address,
                        port = MCI_EDGE_POOL_PORT,
                        role = "mci-pool-${index + 1}",
                        finalmaskMaxSplit = settings.primaryMaxSplit,
                    )
                    val resolver = MCI_EDGE_POOL_RESOLVERS[index % MCI_EDGE_POOL_RESOLVERS.size]
                    add(
                        candidate(
                            id = "mci-edge-pool-${address.replace('.', '-')}",
                            label = "MCI edge $address",
                            edge = edge,
                            settings = settings,
                            resolver = resolver,
                        ),
                    )
                }
                add(candidate("mci-fallback-quad9", "MCI fallback + Quad9 DNS", fallback, settings, AdaptiveDnsResolvers.QUAD9))
                add(candidate("mci-cdn-a-adguard", "MCI CDN A + AdGuard DNS", cdnRescueA, settings.copy(finalmaskDelayMs = 15), AdaptiveDnsResolvers.ADGUARD))
                add(candidate("mci-cdn-b-opendns", "MCI CDN B + OpenDNS", cdnRescueB, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.OPENDNS))
                add(candidate("mci-primary-deep-google", "MCI deep-fragment rescue", primary.copy(finalmaskMaxSplit = 100), settings.copy(finalmaskDelayMs = 5), AdaptiveDnsResolvers.GOOGLE))
            }
            "irancell" -> listOf(
                candidate("irancell-deep-cloudflare", "Irancell deep + Cloudflare DNS", irancell, settings, AdaptiveDnsResolvers.CLOUDFLARE),
                candidate("irancell-primary-google-fast", "Irancell low delay + Google DNS", primary, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.GOOGLE),
                candidate("irancell-fallback-quad9", "Irancell fallback + Quad9 DNS", fallback, settings, AdaptiveDnsResolvers.QUAD9),
                candidate("irancell-cdn-a-adguard", "Irancell CDN A + AdGuard DNS", cdnRescueA.copy(finalmaskMaxSplit = 100), settings.copy(finalmaskDelayMs = 15), AdaptiveDnsResolvers.ADGUARD),
                candidate("irancell-cdn-b-opendns", "Irancell CDN B + OpenDNS", cdnRescueB, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.OPENDNS),
                candidate("irancell-primary-cloudflare", "Irancell standard rescue", primary, settings, AdaptiveDnsResolvers.CLOUDFLARE),
            )
            else -> listOf(
                candidate("fixed-primary-cloudflare", "Primary + Cloudflare DNS", primary, settings, AdaptiveDnsResolvers.CLOUDFLARE),
                candidate("fixed-primary-google-fast", "Low delay + Google DNS", primary, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.GOOGLE),
                candidate("fixed-fallback-quad9", "Fallback + Quad9 DNS", fallback, settings, AdaptiveDnsResolvers.QUAD9),
                candidate("fixed-cdn-a-adguard", "CDN A + AdGuard DNS", cdnRescueA, settings.copy(finalmaskDelayMs = 15), AdaptiveDnsResolvers.ADGUARD),
                candidate("fixed-cdn-b-opendns", "CDN B + OpenDNS", cdnRescueB, settings.copy(finalmaskDelayMs = 0), AdaptiveDnsResolvers.OPENDNS),
                candidate("fixed-primary-deep-google", "Deep-fragment rescue", primary.copy(finalmaskMaxSplit = 100), settings.copy(finalmaskDelayMs = 5), AdaptiveDnsResolvers.GOOGLE),
            )
        }
        val learnedId = store.winner(network, profile, signature)
        val diagnostic = raw.firstOrNull { it.id == MCI_DIRECT_COMPAT_ID }
        val learned = learnedId?.let { id ->
            raw.firstOrNull { it.id == id && it.id != MCI_DIRECT_COMPAT_ID }?.copy(learned = true)
        }
        val ordered = buildList {
            if (diagnostic != null) add(diagnostic.copy(learned = learnedId == MCI_DIRECT_COMPAT_ID))
            if (learned != null) add(learned)
            addAll(raw.filterNot { it.id == learnedId || it.id == MCI_DIRECT_COMPAT_ID })
        }
        val (ready, coolingDown) = ordered.partition {
            !store.isCoolingDown(network, profile, signature, it.id)
        }
        return (ready + coolingDown)
            .distinctBy(AdaptiveCandidate::id)
            .take(MAX_CANDIDATES)
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
        private const val STRATEGY_VERSION = "adaptive-v5-mci-direct-compat"
        const val MCI_DIRECT_COMPAT_ID = "mci-direct-compat"
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
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
