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

    val LADDER: List<PowPsiphonStrategy> = listOf(
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
    )

    fun signature(): String = LADDER.joinToString(",") { it.name }

    fun buildConfig(
        socksPort: Int,
        dataDirectory: String,
        preferredRegion: String?,
        strategy: PowPsiphonStrategy,
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
            put("ConnectionWorkerPoolSize", 16)
            put("EmitDiagnosticNotices", true)
            put("DNSResolverPreferredAlternateServers", JSONArray(ALTERNATE_DNS))
            put("DNSResolverPreferAlternateServerProbability", 0.35)
            put("DNSResolverAttemptsPerPreferredServer", 1)
            put("UpstreamProxyURL", "socks5://127.0.0.1:${PowCoreConfig.CHAIN_SOCKS_PORT}")
            put("InproxyEnabled", false)
            put("InproxyAllowClient", false)
        }
        strategy.configure(config)
        config.put("EstablishTunnelTimeoutSeconds", strategy.timeoutSeconds)
        if (!preferredRegion.isNullOrBlank()) {
            config.put("EgressRegion", preferredRegion)
            config.put("EstablishTunnelTimeoutSeconds", REGION_PHASE_TIMEOUT_SECONDS)
            config.remove("InitialLimitTunnelProtocols")
            config.remove("InitialLimitTunnelProtocolsCandidateCount")
            config.remove("LimitTunnelProtocols")
        }
        config.put("LimitTunnelProtocols", JSONArray(CHAINABLE))
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
