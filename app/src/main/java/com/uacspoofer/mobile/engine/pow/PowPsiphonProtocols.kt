package com.uacspoofer.mobile.engine.pow

import org.json.JSONArray
import org.json.JSONObject

internal data class PowPsiphonStrategy(
    val name: String,
    val label: String,
    val timeoutSeconds: Int,
    val preferredProtocols: List<String>,
    val configure: (JSONObject) -> Unit,
)

internal object PowPsiphonProtocols {
    val FRONTED = listOf(
        "FRONTED-MEEK-OSSH",
        "FRONTED-MEEK-HTTP-OSSH",
        "FRONTED-MEEK-QUIC-OSSH",
    )

    val DIRECT = listOf(
        "TLS-OSSH",
        "OSSH",
        "SSH",
        "SHADOWSOCKS-OSSH",
        "UNFRONTED-MEEK-HTTPS-OSSH",
        "UNFRONTED-MEEK-OSSH",
        "QUIC-OSSH",
        "CONJURE-OSSH",
    )

    val FAST_DIRECT = listOf(
        "TLS-OSSH",
        "OSSH",
        "SSH",
        "SHADOWSOCKS-OSSH",
    )

    val CHAINABLE = listOf(
        "FRONTED-MEEK-OSSH",
        "FRONTED-MEEK-HTTP-OSSH",
        "TLS-OSSH",
        "UNFRONTED-MEEK-HTTPS-OSSH",
        "UNFRONTED-MEEK-OSSH",
        "SHADOWSOCKS-OSSH",
        "OSSH",
        "SSH",
    )

    val ALTERNATE_DNS = listOf(
        "208.67.222.222:5353",
        "9.9.9.9:9953",
        "208.67.220.220:5353",
    )

    const val REGION_PHASE_TIMEOUT_SECONDS = 25

    // Automatic exit: a short first try in a nearby country, then any country.
    const val NEARBY_PHASE_TIMEOUT_SECONDS = 12

    // The first light attempt behind WARP. TLS-OSSH/OSSH/SSH either finish
    // through the WARP SOCKS hop quickly or not at all.
    const val CHAINED_LIGHT_TIMEOUT_SECONDS = 20

    // Head start for the last server that worked (Psiphon default is 1s,
    // scaled by NetworkLatencyMultiplier). WARP adds a hop, so give it more.
    const val SERVER_AFFINITY_GRACE_MS = 1_500

    // "Two tunnels at once" runs a second, independent Psiphon (PowPsiphonSidecar)
    // instead of TunnelPoolSize=2: the library spreads port forwards round-robin
    // over pooled tunnels to different servers, so one service saw two exit IPs.
    const val SIDECAR_ESTABLISH_TIMEOUT_SECONDS = 90
    const val SIDECAR_WORKER_POOL_SIZE = 6

    /** Tunnel 2 config: tunnel 1's winning config with its own data root and SOCKS port. */
    fun sidecarConfig(primaryConfig: String, dataRoot: String, socksPort: Int): String {
        val config = JSONObject(primaryConfig)
        config.put("DataRootDirectory", dataRoot)
        config.put("LocalSocksProxyPort", socksPort)
        config.put("EstablishTunnelTimeoutSeconds", SIDECAR_ESTABLISH_TIMEOUT_SECONDS)
        config.put(
            "ConnectionWorkerPoolSize",
            minOf(config.optInt("ConnectionWorkerPoolSize", SIDECAR_WORKER_POOL_SIZE), SIDECAR_WORKER_POOL_SIZE),
        )
        config.remove("TunnelPoolSize")
        return config.toString()
    }

    // Destinations Psiphon itself may leave untunneled (direct mode only:
    // there the untunneled dial goes out the phone's own network).
    val SPLIT_TUNNEL_REGIONS = listOf("IR")

    val LADDER: List<PowPsiphonStrategy> = listOf(
        PowPsiphonStrategy(
            name = "A",
            label = "domain-fronted (CDN)",
            timeoutSeconds = 40,
            preferredProtocols = FRONTED,
        ) { config ->
            config.put("InitialLimitTunnelProtocols", JSONArray(FRONTED))
            config.put("InitialLimitTunnelProtocolsCandidateCount", 24)
            config.put("LimitTunnelProtocols", JSONArray(FRONTED))
            config.put("ConnectionWorkerPoolSize", 12)
            config.put("NetworkLatencyMultiplier", 1.5)
        },
        PowPsiphonStrategy(
            name = "F",
            label = "direct through WARP",
            timeoutSeconds = 28,
            preferredProtocols = FAST_DIRECT,
        ) { config ->
            config.put("InitialLimitTunnelProtocols", JSONArray(FAST_DIRECT))
            config.put("InitialLimitTunnelProtocolsCandidateCount", 16)
            config.put("ConnectionWorkerPoolSize", 16)
            config.put("NetworkLatencyMultiplier", 1.0)
        },
    )

    /**
     * Strategy order for this leg. Direct Psiphon keeps the original order
     * (fronted meek first). Behind WARP the light TLS-OSSH/OSSH/SSH/SS set goes
     * first with a shorter timeout, and fronted meek is the fallback.
     */
    fun ladder(direct: Boolean): List<PowPsiphonStrategy> {
        if (direct) return LADDER
        val light = LADDER.first { it.name == "F" }.copy(timeoutSeconds = CHAINED_LIGHT_TIMEOUT_SECONDS)
        val heavy = LADDER.first { it.name == "A" }
        return listOf(light, heavy)
    }

    fun signature(): String = LADDER.joinToString(",") { it.name }

    fun signature(direct: Boolean): String = ladder(direct).joinToString(",") { it.name }

    /**
     * First strategy to try. When this network has RTTs saved for two or more
     * strategies, the one with the lowest RTT wins; otherwise the remembered
     * winner; otherwise the head of the ladder.
     */
    fun startIndex(
        ladder: List<PowPsiphonStrategy>,
        remembered: Int?,
        rttByName: Map<String, Long>,
    ): Int {
        val measured = ladder.indices.filter { (rttByName[ladder[it].name] ?: 0L) > 0L }
        if (measured.size >= 2) {
            return measured.minByOrNull { rttByName.getValue(ladder[it].name) } ?: 0
        }
        return remembered?.takeIf { it in ladder.indices } ?: 0
    }

    fun buildConfig(
        socksPort: Int,
        dataDirectory: String,
        preferredRegion: String?,
        strategy: PowPsiphonStrategy,
        optimizedMode: Boolean = true,
        direct: Boolean = false,
        regionBias: Boolean = false,
    ): String {
        val config = JSONObject().apply {
            put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
            put("SponsorId", "1111111111111111")
            put("EgressRegion", "")
            put("EstablishTunnelTimeoutSeconds", strategy.timeoutSeconds)
            put("DataDirectory", dataDirectory)
            put("ClientVersion", "1")
            put("TunnelProtocol", "")
            put("RemoteServerListURL", "")
            put("LocalSocksProxyPort", socksPort)
            put(
                "RemoteServerListSignaturePublicKey",
                "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=",
            )
            put("ServerEntrySignaturePublicKey", "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=")
            put("ExchangeObfuscationKey", "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU=")
            put("EmitBytesTransferred", true)
            put("DeviceRegion", "IR")
            put("ConnectionWorkerPoolSize", if (optimizedMode) 12 else 16)
            put("EmitDiagnosticNotices", true)
            put("DNSResolverPreferredAlternateServers", JSONArray(ALTERNATE_DNS))
            put("DNSResolverPreferAlternateServerProbability", if (optimizedMode) 1.0 else 0.35)
            put("DNSResolverAttemptsPerPreferredServer", if (optimizedMode) 2 else 1)
            if (!direct) {
                put("UpstreamProxyURL", "socks5://127.0.0.1:${PowCoreConfig.CHAIN_SOCKS_PORT}")
            }
            put("InproxyEnabled", false)
            put("InproxyAllowClient", false)
            put("EstablishTunnelServerAffinityGracePeriodMilliseconds", SERVER_AFFINITY_GRACE_MS)
            if (direct) {
                // Untunneled dials use UpstreamProxyURL when one is set, so
                // behind WARP this would turn into a WARP-only exit. Only the
                // direct leg, where the dial leaves on the phone's network
                // (the :uacpow process is outside the VPN and protects its
                // sockets), lets Psiphon send Iranian destinations direct.
                put("SplitTunnelRegions", JSONArray(SPLIT_TUNNEL_REGIONS))
            }
        }
        strategy.configure(config)
        config.put("EstablishTunnelTimeoutSeconds", strategy.timeoutSeconds)
        if (!preferredRegion.isNullOrBlank()) {
            config.put("EgressRegion", preferredRegion)
            if (regionBias) {
                // Nearby-country head start for Automatic: keep the
                // strategy's protocol preference, give it a short window.
                config.put("EstablishTunnelTimeoutSeconds", NEARBY_PHASE_TIMEOUT_SECONDS)
            } else {
                config.put("EstablishTunnelTimeoutSeconds", REGION_PHASE_TIMEOUT_SECONDS)
                config.remove("InitialLimitTunnelProtocols")
                config.remove("InitialLimitTunnelProtocolsCandidateCount")
                config.remove("LimitTunnelProtocols")
            }
        }
        if (direct) {
            // The WARP outer hop did not come up. Plain OSSH and SSH sit on
            // ports this carrier drops, and if they share the worker pool they
            // burn the whole timeout before a fronted meek dial starts. The
            // fronted strategy is meek only. The following strategy is TLS and
            // Shadowsocks, which can finish a handshake on 443. Name lookup
            // stays on the network resolver: 5353 and 9953 are dropped.
            val meekOnly = strategy.preferredProtocols.all { it.startsWith("FRONTED-") }
            val protocols = if (meekOnly) {
                listOf("FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH")
            } else {
                listOf("TLS-OSSH", "SHADOWSOCKS-OSSH")
            }
            val selected = JSONArray(protocols)
            config.put("LimitTunnelProtocols", selected)
            config.put("InitialLimitTunnelProtocols", selected)
            config.put("InitialLimitTunnelProtocolsCandidateCount", if (meekOnly) 16 else 8)
            config.put("EstablishTunnelTimeoutSeconds", if (meekOnly) 90 else 35)
            config.put("ConnectionWorkerPoolSize", if (meekOnly) 8 else 6)
            config.put("DNSResolverPreferAlternateServerProbability", 0)
            config.remove("DNSResolverPreferredAlternateServers")
            if (meekOnly) {
                // The Cloudflare front answers a normal ClientHello that names
                // cdnjs.cloudflare.com and ignores one with no server name.
                // The signed server entries disable SNI. Ask the tunnel core
                // to put that name back on the handshake.
                config.put("MeekTransformedHostName", "cdnjs.cloudflare.com")
                config.put("MeekSNIServerName", "cdnjs.cloudflare.com")
            }
        } else {
            config.put("LimitTunnelProtocols", JSONArray(CHAINABLE))
        }
        val preference = config.optJSONArray("InitialLimitTunnelProtocols")
        if (preference != null) {
            val chainable = (0 until preference.length())
                .map(preference::getString)
                .filter(CHAINABLE::contains)
            if (chainable.isEmpty()) {
                config.remove("InitialLimitTunnelProtocols")
                config.remove("InitialLimitTunnelProtocolsCandidateCount")
            } else {
                config.put("InitialLimitTunnelProtocols", JSONArray(chainable))
            }
        }
        return config.toString()
    }
}
