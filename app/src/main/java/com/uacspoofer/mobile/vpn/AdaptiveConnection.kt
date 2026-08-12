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
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class NetworkFingerprint(
    val key: String,
    val networkHandle: Long,
    val transport: String,
    val carrier: String,
    val carrierClass: String,
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
        "id=$key transport=$transport carrier=$carrier class=$carrierClass dataSub=$dataSubscriptionId metered=$metered " +
            "roaming=$roaming validated=$validated captive=$captivePortal mtu=$mtu " +
            "ip4=$hasIpv4 ip6=$hasIpv6 dns=$dnsCount downKbps=$downstreamKbps upKbps=$upstreamKbps"
}

class NetworkFingerprintResolver(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val telephony = context.applicationContext
        .getSystemService(TelephonyManager::class.java)
    @Volatile private var preferredNetworkHandle = -1L

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

    private fun buildDescriptor(
        transport: String,
        operatorCode: String,
        carrierClass: String,
        capabilities: NetworkCapabilities?,
        links: LinkProperties?,
    ): String {
        val families = links?.linkAddresses.orEmpty()
            .map { "${it.address.address.size}:${it.prefixLength}:${networkPrefixToken(it.address.address, it.prefixLength)}" }
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

    private fun networkPrefixToken(address: ByteArray, prefixLength: Int): String {
        val masked = address.copyOf()
        var remaining = prefixLength.coerceIn(0, address.size * 8)
        for (index in masked.indices) {
            val bits = remaining.coerceIn(0, 8)
            val mask = if (bits == 0) 0 else 0xff shl (8 - bits)
            masked[index] = (masked[index].toInt() and mask).toByte()
            remaining -= bits
        }
        return sha256(masked.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }).take(8)
    }
}

data class AdaptiveCandidate(
    val id: String,
    val label: String,
    val edge: MciEdge,
    val settings: AdvancedSettingsData,
    val learned: Boolean = false,
) {
    fun summary(): String =
        "id=$id edge=${edge.role}@${edge.address}:${edge.port} split=${edge.finalmaskMaxSplit} " +
            "fragment=${settings.finalmaskPacket}/${settings.finalmaskLength}/${settings.finalmaskDelayMs}ms " +
            "mtu=${settings.tunMtu} dnsTrap=${settings.nativeDns} resolver=fakedns-v4 " +
            "udp443Blocked=${settings.blockUdp443} learned=$learned"
}

data class AdaptiveProbeReport(
    val accepted: Boolean,
    val acceptanceMode: String,
    val score: Int,
    val http: ProbeResult,
    val dns: DnsProbeResult,
    val durationMs: Long,
) {
    fun detail(): String =
        "accepted=$accepted mode=$acceptanceMode score=$score duration=${durationMs}ms http=${http.succeededTargets}/${http.attemptedTargets} " +
            "bytes=${http.totalBytes} latency=${http.latencyMs ?: -1}ms dns=${dns.success} " +
            "dnsLatency=${dns.latencyMs ?: -1}ms answers=${dns.answerCount} httpDetail=[${http.detail}] dnsDetail=[${dns.detail}]"
}

class AdaptiveConnectionProbe(
    private val connectivityProbe: VpnConnectivityProbe,
    private val dnsProbe: SocksDnsProbe,
) {
    suspend fun verify(candidate: AdaptiveCandidate): AdaptiveProbeReport {
        val started = System.nanoTime()
        val http = connectivityProbe.verifyCandidate(candidate.settings)
        val dns = if (http.success) {
            dnsProbe.verify(candidate.settings)
        } else {
            DnsProbeResult(
                success = false,
                server = candidate.settings.nativeDns,
                detail = "skipped because HTTPS egress failed",
            )
        }
        val score = score(http, dns)
        val strongHttp = http.succeededTargets >= 2
        val httpWithDns = http.succeededTargets >= 1 && dns.success
        val acceptanceMode = when {
            strongHttp && dns.success -> "dual-http+dns"
            httpWithDns -> "http+dns"
            else -> "rejected"
        }
        val accepted = when (acceptanceMode) {
            "dual-http+dns" -> score >= 65
            "http+dns" -> score >= 45
            else -> false
        }
        return AdaptiveProbeReport(
            accepted = accepted,
            acceptanceMode = acceptanceMode,
            score = score,
            http = http,
            dns = dns,
            durationMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
        )
    }

    private fun score(http: ProbeResult, dns: DnsProbeResult): Int {
        var score = http.succeededTargets.coerceAtMost(2) * 25
        if (http.succeededTargets >= 2) score += 10
        if (dns.success) score += 20
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
}

class AdaptiveProfileStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("adaptive_connection_profiles_v1", Context.MODE_PRIVATE)

    fun winner(network: NetworkFingerprint, profile: ProxyProfile, signature: String): String? {
        val key = key("winner", network.key, profile.id, signature)
        val updated = prefs.getLong("$key:updated", 0L)
        if (updated <= 0L || System.currentTimeMillis() - updated > WINNER_TTL_MS) return null
        return prefs.getString("$key:id", null)
    }

    fun recordStable(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidate: AdaptiveCandidate,
        score: Int,
    ) {
        val key = key("winner", network.key, profile.id, signature)
        prefs.edit()
            .putString("$key:id", candidate.id)
            .putInt("$key:score", score)
            .putLong("$key:updated", System.currentTimeMillis())
            .remove(failureKey(network, profile, signature, candidate.id))
            .apply()
    }

    fun recordFailure(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidateId: String,
    ) {
        prefs.edit()
            .putLong(failureKey(network, profile, signature, candidateId), System.currentTimeMillis())
            .apply()
    }

    fun isCoolingDown(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidateId: String,
    ): Boolean {
        val failedAt = prefs.getLong(failureKey(network, profile, signature, candidateId), 0L)
        return failedAt > 0L && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS
    }

    private fun failureKey(
        network: NetworkFingerprint,
        profile: ProxyProfile,
        signature: String,
        candidateId: String,
    ): String = key("failure", network.key, profile.id, signature, candidateId)

    private fun key(vararg values: String): String = sha256(values.joinToString("|"))

    companion object {
        private const val WINNER_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val FAILURE_COOLDOWN_MS = 2L * 60L * 1_000L
    }
}

class AdaptiveCandidatePlanner(private val store: AdaptiveProfileStore) {
    fun signature(settings: AdvancedSettingsData, profile: ProxyProfile): String = sha256(
        listOf(
            profile.id,
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
        val raw = when (network.carrierClass) {
            "mci" -> listOf(
                candidate("baseline-primary", "MCI baseline", primary, settings),
                candidate("low-delay-primary", "MCI low delay", primary, settings.copy(finalmaskDelayMs = 0)),
                candidate("baseline-fallback", "alternate edge", fallback, settings),
                candidate("cdn-rescue-a", "MCI CDN rescue A", cdnRescueA, settings.copy(finalmaskDelayMs = 15)),
                candidate("cdn-rescue-b", "MCI CDN rescue B", cdnRescueB, settings.copy(finalmaskDelayMs = 0)),
                candidate("baseline-primary-retry", "MCI warm retry", primary, settings),
            )
            "irancell" -> listOf(
                candidate("deep-irancell", "Irancell deep fragmentation", irancell, settings),
                candidate("baseline-primary", "standard fragmentation", primary, settings),
                candidate("low-delay-primary", "low delay", primary, settings.copy(finalmaskDelayMs = 0)),
                candidate("baseline-fallback", "alternate edge", fallback, settings),
                candidate("cdn-rescue-a", "Irancell CDN rescue A", cdnRescueA.copy(finalmaskMaxSplit = 100), settings.copy(finalmaskDelayMs = 15)),
                candidate("cdn-rescue-b", "Irancell CDN rescue B", cdnRescueB, settings.copy(finalmaskDelayMs = 0)),
            )
            else -> listOf(
                candidate("baseline-primary", "standard path", primary, settings),
                candidate("low-delay-primary", "fixed-line low delay", primary, settings.copy(finalmaskDelayMs = 0)),
                candidate("baseline-fallback", "alternate edge", fallback, settings),
                candidate("cdn-rescue-a", "fixed-line CDN rescue A", cdnRescueA, settings.copy(finalmaskDelayMs = 15)),
                candidate("cdn-rescue-b", "fixed-line CDN rescue B", cdnRescueB, settings.copy(finalmaskDelayMs = 0)),
                candidate("baseline-primary-retry", "warm retry", primary, settings),
            )
        }
        val learnedId = store.winner(network, profile, signature)
        val learned = learnedId?.let { id -> raw.firstOrNull { it.id == id }?.copy(learned = true) }
        val ordered = buildList {
            if (learned != null) add(learned)
            addAll(raw.filterNot { it.id == learnedId })
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
    ) = AdaptiveCandidate(id, label, edge, settings.validated())

    companion object {
        const val MAX_CANDIDATES = 6
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
