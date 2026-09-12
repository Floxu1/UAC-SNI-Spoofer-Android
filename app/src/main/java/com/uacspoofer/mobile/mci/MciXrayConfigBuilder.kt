package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.ai.AiRoutePlan
import com.uacspoofer.mobile.profiles.ProfileNetworks
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.profiles.RuntimeProxyIdentity
import com.uacspoofer.mobile.profiles.TlsAlpnResolver
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers

data class MciXrayRuntimeOptions(
    val identityOverride: RuntimeProxyIdentity? = null,
    val finalmaskEnabled: Boolean = true,
    val preserveEmptyAlpn: Boolean = false,
    val preserveTransportFields: Boolean = false,
    val quietLogging: Boolean = false,
    val muxEnabledOverride: Boolean? = null,
) {
    companion object {
        val DEFAULT = MciXrayRuntimeOptions()
    }
}

data class MciXrayBatchRoute(
    val tag: String,
    val edge: MciEdge,
    val settings: AdvancedSettingsData,
    val runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
)

internal object MciXrayConfigBuilder {
    fun build(
        edge: MciEdge,
        settings: AdvancedSettingsData,
        profile: ProxyProfile,
        nativeTun: Boolean,
        runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
        aiRoute: AiRoutePlan? = null,
    ): String {
        val s = settings.validated()
        val identity = runtimeOptions.identityOverride ?: profile.runtimeIdentity(s)
        validateIdentity(identity, runtimeOptions)
        // Built first so an unusable AI exit drops the outbound and its routing rule
        // together, leaving a config byte-identical to one without this feature.
        val aiOutbound = aiRoute?.takeIf(AiRoutePlan::isUsable)?.let { plan ->
            runCatching {
                val aiIdentity = plan.profile.runtimeIdentity(s)
                validateIdentity(aiIdentity, runtimeOptions)
                proxyOutbound("ai-out", aiIdentity, plan.edge, s, nativeTun, runtimeOptions, false)
            }.getOrNull()
        }
        val cleanHop = aiRoute?.takeIf { aiOutbound != null }

        val outbounds = buildList {
            add(
                proxyOutbound(
                    "proxy",
                    identity,
                    edge,
                    s,
                    nativeTun,
                    runtimeOptions,
                    runtimeOptions.muxEnabledOverride ?: s.muxEnabled,
                ),
            )
            add(proxyOutbound("probe-proxy", identity, edge, s, nativeTun, runtimeOptions, false))
            aiOutbound?.let(::add)
            add("""{"tag":"dns-out","protocol":"dns","settings":{"rewriteNetwork":"tcp","rules":[{"action":"hijack"}]}}""")
            add("""{"tag":"block","protocol":"blackhole","settings":{}}""")
        }.joinToString(",\n")

        val acceptedInbounds = "[\"socks-in\"]"
        val dnsInbounds = if (nativeTun) "[\"socks-in\",\"tun-in\"]" else acceptedInbounds
        val routingRules = buildList {
            add("""{"type":"field","inboundTag":$dnsInbounds,"network":"tcp,udp","port":"53","outboundTag":"dns-out"}""")
            add("""{"type":"field","inboundTag":["dns-query"],"outboundTag":"proxy"}""")
            if (s.ipv4Only) {
                add("""{"type":"field","inboundTag":$acceptedInbounds,"network":"tcp","ip":["::/0"],"outboundTag":"block"}""")
            }
            cleanHop?.let { plan ->
                val domains = plan.domains.joinToString(",") { "\"${q(it)}\"" }
                add("""{"type":"field","inboundTag":$dnsInbounds,"domain":[$domains],"outboundTag":"ai-out"}""")
            }
            add("""{"type":"field","inboundTag":["socks-in"],"network":"tcp,udp","outboundTag":"probe-proxy"}""")
        }.joinToString(",\n")

        val rootExtras = if (nativeTun) {
            """"stats":{},"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5,"bufferSize":64}},"system":{"statsInboundUplink":true,"statsInboundDownlink":true,"statsOutboundUplink":true,"statsOutboundDownlink":true}},"""
        } else {
            ""
        }
        val sniffing = """"sniffing":{"enabled":true,"destOverride":["http","tls","fakedns"],"metadataOnly":false}"""
        val inbounds = buildList {
            add("""{"tag":"socks-in","listen":"${q(s.socksAddress)}","port":${s.socksPort},"protocol":"socks","settings":{"auth":"noauth","udp":${s.socksUdp},"ip":"${q(s.socksAddress)}","userLevel":8},$sniffing}""")
            if (nativeTun) {
                add("""{"tag":"tun-in","protocol":"tun","settings":{"name":"xray0","MTU":${s.tunMtu},"userLevel":8},$sniffing}""")
            }
        }.joinToString(",\n")

        val resolverOrder = AdaptiveDnsResolvers.ordered(s.dnsResolverUrl)
        val resolverHosts = resolverOrder
            .flatMap { it.bootstrapHosts.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.flatten().distinct() }
            .entries
            .joinToString(",") { (host, addresses) ->
                val encoded = addresses.joinToString(",") { "\"${q(it)}\"" }
                "\"${q(host)}\":[$encoded]"
            }
        val resolverServers = resolverOrder.joinToString(",") { resolver ->
            """{"address":"${q(resolver.url)}","queryStrategy":"UseIPv4","timeoutMs":3000,"skipFallback":false}"""
        }
        val dns = """"dns":{"hosts":{$resolverHosts},"servers":[$resolverServers],"queryStrategy":"UseIPv4","disableCache":false,"serveStale":true,"serveExpiredTTL":3600,"enableParallelQuery":false,"tag":"dns-query"},"""

        val logLevel = if (runtimeOptions.quietLogging) "warning" else "debug"
        val dnsLog = !runtimeOptions.quietLogging
        return """
            {
              "log":{"loglevel":"$logLevel","dnsLog":$dnsLog,"maskAddress":"quarter"},
              $rootExtras
              $dns
              "inbounds":[$inbounds],
              "outbounds":[$outbounds],
              "routing":{"domainStrategy":"${q(s.routingDomainStrategy)}","rules":[$routingRules]}
            }
        """.trimIndent()
    }

    fun buildBatch(
        routes: List<MciXrayBatchRoute>,
        profile: ProxyProfile,
    ): String {
        require(routes.isNotEmpty()) { "Batch route list is empty" }
        require(routes.size <= 32) { "Batch route count exceeds 32" }
        val normalized = routes.map { route -> route.copy(settings = route.settings.validated()) }
        require(normalized.map { it.settings.socksPort }.distinct().size == normalized.size) {
            "Batch SOCKS ports must be unique"
        }
        require(normalized.map { it.tag }.distinct().size == normalized.size) {
            "Batch route tags must be unique"
        }
        require(normalized.map { it.settings.routingDomainStrategy }.distinct().size == 1) {
            "Batch routes must use one routing domain strategy"
        }

        val sniffing = """"sniffing":{"enabled":true,"destOverride":["http","tls"],"metadataOnly":false}"""
        val inbounds = normalized.joinToString(",\n") { route ->
            val s = route.settings
            """{"tag":"in-${q(route.tag)}","listen":"${q(s.socksAddress)}","port":${s.socksPort},"protocol":"socks","settings":{"auth":"noauth","udp":false,"ip":"${q(s.socksAddress)}","userLevel":8},$sniffing}"""
        }
        val outbounds = buildList {
            normalized.forEach { route ->
                val identity = route.runtimeOptions.identityOverride ?: profile.runtimeIdentity(route.settings)
                validateIdentity(identity, route.runtimeOptions)
                add(
                    proxyOutbound(
                        tag = "out-${route.tag}",
                        identity = identity,
                        edge = route.edge,
                        settings = route.settings,
                        nativeTun = false,
                        runtimeOptions = route.runtimeOptions.copy(quietLogging = true),
                        muxEnabled = route.runtimeOptions.muxEnabledOverride ?: false,
                    ),
                )
            }
            add("""{"tag":"block","protocol":"blackhole","settings":{}}""")
        }.joinToString(",\n")
        val rules = buildList {
            normalized.forEach { route ->
                val inbound = "[\"in-${q(route.tag)}\"]"
                if (route.settings.ipv4Only) {
                    add("""{"type":"field","inboundTag":$inbound,"network":"tcp","ip":["::/0"],"outboundTag":"block"}""")
                }
                add("""{"type":"field","inboundTag":$inbound,"network":"tcp,udp","outboundTag":"out-${q(route.tag)}"}""")
            }
        }.joinToString(",\n")
        val routingStrategy = normalized.first().settings.routingDomainStrategy
        return """
            {
              "log":{"loglevel":"warning","dnsLog":false,"maskAddress":"quarter"},
              "inbounds":[$inbounds],
              "outbounds":[$outbounds],
              "routing":{"domainStrategy":"${q(routingStrategy)}","rules":[$rules]}
            }
        """.trimIndent()
    }

    private fun proxyOutbound(
        tag: String,
        identity: RuntimeProxyIdentity,
        edge: MciEdge,
        settings: AdvancedSettingsData,
        nativeTun: Boolean,
        runtimeOptions: MciXrayRuntimeOptions,
        muxEnabled: Boolean,
    ): String {
        val protocolSettings = when (identity.protocol) {
            ProxyProtocol.TROJAN ->
                """{"servers":[{"address":"${q(edge.address)}","port":${edge.port},"password":"${q(identity.credential)}"}]}"""
            ProxyProtocol.VLESS -> vnextSettings(
                address = edge.address,
                port = edge.port,
                userJson = buildString {
                    append("\"id\":\"${q(identity.credential)}\",\"encryption\":\"${q(identity.encryption)}\"")
                    identity.flow.takeIf { it.isNotBlank() }?.let { append(",\"flow\":\"${q(it)}\"") }
                },
                packetEncoding = identity.packetEncoding,
            )
            ProxyProtocol.VMESS -> vnextSettings(
                address = edge.address,
                port = edge.port,
                userJson = "\"id\":\"${q(identity.credential)}\",\"alterId\":${identity.alterId},\"security\":\"${q(identity.encryption.ifBlank { "auto" })}\"",
                packetEncoding = identity.packetEncoding,
            )
        }
        val stream = streamSettings(
            identity,
            settings,
            edge.finalmaskMaxSplit,
            nativeTun,
            runtimeOptions,
        )
        val enableMux = muxEnabled && !ProfileNetworks.isXhttp(identity.network)
        return """{"tag":"${q(tag)}","protocol":"${identity.protocol.wireName}","settings":$protocolSettings,"streamSettings":$stream,"mux":{"enabled":$enableMux,"concurrency":${settings.muxConcurrency}}}"""
    }

    private fun streamSettings(
        identity: RuntimeProxyIdentity,
        settings: AdvancedSettingsData,
        maxSplit: Int,
        nativeTun: Boolean,
        runtimeOptions: MciXrayRuntimeOptions,
    ): String {
        val alpn = TlsAlpnResolver.resolveForXray(
            network = identity.network,
            rawAlpn = identity.alpn,
            preserveEmptyAlpn = runtimeOptions.preserveEmptyAlpn,
        )
        val alpnField = alpn.takeIf(List<String>::isNotEmpty)
            ?.joinToString(",", prefix = ",\"alpn\":[", postfix = "]") { "\"${q(it)}\"" }
            .orEmpty()
        val fingerprint = identity.fingerprint.takeIf(String::isNotBlank)
            ?.let { ",\"fingerprint\":\"${q(it)}\"" }.orEmpty()
        val serverName = identity.sni.takeIf(String::isNotBlank)
            ?.let { "\"serverName\":\"${q(it)}\"," }.orEmpty()
        // A plain config has no TLS layer, so emitting tlsSettings would make Xray negotiate a
        // handshake the server never offers.
        val tls = if (identity.usesTls) {
            """"tlsSettings":{$serverName"allowInsecure":${identity.allowInsecure}$alpnField$fingerprint}"""
        } else {
            ""
        }
        val transport = when (identity.network) {
            "ws" -> if (runtimeOptions.preserveTransportFields) {
                val fields = buildList {
                    identity.path.takeIf(String::isNotBlank)?.let { add("\"path\":\"${q(it)}\"") }
                    identity.host.takeIf(String::isNotBlank)?.let {
                        add("\"host\":\"${q(it)}\"")
                        add("\"headers\":{\"Host\":\"${q(it)}\"}")
                    }
                }.joinToString(",")
                "\"wsSettings\":{$fields}"
            } else {
                """"wsSettings":{"path":"${q(identity.path)}","host":"${q(identity.host)}","headers":{"Host":"${q(identity.host)}"}}"""
            }
            "httpupgrade" -> if (runtimeOptions.preserveTransportFields) {
                val fields = buildList {
                    identity.path.takeIf(String::isNotBlank)?.let { add("\"path\":\"${q(it)}\"") }
                    identity.host.takeIf(String::isNotBlank)?.let { add("\"host\":\"${q(it)}\"") }
                }.joinToString(",")
                "\"httpupgradeSettings\":{$fields}"
            } else {
                """"httpupgradeSettings":{"path":"${q(identity.path)}","host":"${q(identity.host)}"}"""
            }
            "grpc" -> {
                val authority = identity.authority.takeIf(String::isNotBlank)
                    ?.let { ",\"authority\":\"${q(it)}\"" }.orEmpty()
                """"grpcSettings":{"serviceName":"${q(identity.serviceName)}"$authority}"""
            }
            "xhttp" -> {
                val fields = buildList {
                    if (runtimeOptions.preserveTransportFields) {
                        identity.path.takeIf(String::isNotBlank)?.let { add("\"path\":\"${q(it)}\"") }
                        identity.host.takeIf(String::isNotBlank)?.let { add("\"host\":\"${q(it)}\"") }
                        identity.xhttpMode.takeIf(String::isNotBlank)?.let { add("\"mode\":\"${q(it)}\"") }
                    } else {
                        add("\"path\":\"${q(identity.path)}\"")
                        add("\"host\":\"${q(identity.host)}\"")
                        add("\"mode\":\"${q(identity.xhttpMode.ifBlank { "auto" })}\"")
                    }
                    identity.xhttpExtra.takeIf(String::isNotBlank)?.let { add("\"extra\":$it") }
                }.joinToString(",")
                "\"xhttpSettings\":{$fields}"
            }
            "tcp" -> if (identity.headerType == "http") httpObfsHeader(identity) else ""
            else -> error("Unsupported transport ${identity.network}")
        }
        val compatibilityArrays = if (nativeTun) {
            ",\"lengths\":[\"${settings.finalmaskLength}\"],\"delays\":[\"${settings.finalmaskDelayMs}\"]"
        } else {
            ""
        }
        // Fragmentation splits the TLS ClientHello. A plain config has none, so the fragmenter
        // would find nothing to cut and only add a code path that was never exercised.
        val finalmask = if (runtimeOptions.finalmaskEnabled && identity.usesTls) {
            """"finalmask":{"tcp":[{"type":"fragment","settings":{"packets":"${q(settings.finalmaskPacket)}","length":"${settings.finalmaskLength}","delay":"${settings.finalmaskDelayMs}"$compatibilityArrays,"maxSplit":"$maxSplit"}}]}"""
        } else {
            ""
        }
        val sockopt = """"sockopt":{"domainStrategy":"${q(settings.domainStrategy)}","tcpKeepAliveInterval":${settings.keepAliveIntervalSeconds},"tcpKeepAliveIdle":${settings.keepAliveIdleSeconds}}"""
        return listOf(
            "\"network\":\"${q(identity.network)}\"",
            "\"security\":\"${q(identity.security)}\"",
            tls,
            transport,
            finalmask,
            sockopt,
        ).filter(String::isNotBlank).joinToString(",", prefix = "{", postfix = "}")
    }

    private fun validateIdentity(identity: RuntimeProxyIdentity, runtimeOptions: MciXrayRuntimeOptions) {
        require(identity.credential.isNotBlank()) { "Selected profile credential is missing" }
        require(identity.security == "tls" || identity.security == "none") {
            "Selected profile must use TLS or no security"
        }
        // SNI only exists in a TLS handshake; demanding it would reject every plain config.
        if (!runtimeOptions.preserveTransportFields && identity.usesTls) {
            require(identity.sni.isNotBlank()) { "Selected profile SNI is missing" }
        }
        require(identity.network in ProfileNetworks.SUPPORTED) {
            "Unsupported selected profile transport"
        }
        if (identity.network == "grpc") require(identity.serviceName.isNotBlank()) { "gRPC serviceName is missing" }
    }

    /**
     * The `http` disguise the server expects on a plain TCP stream. The request has to look
     * like an ordinary GET to the fronted host, so the Host header carries the config's host
     * rather than the address actually dialled.
     */
    private fun httpObfsHeader(identity: RuntimeProxyIdentity): String {
        val host = identity.host.takeIf(String::isNotBlank) ?: identity.sni
        val path = identity.path.takeIf(String::isNotBlank) ?: "/"
        val headers = buildList {
            if (host.isNotBlank()) add("\"Host\":[\"${q(host)}\"]")
            add("\"User-Agent\":[\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\"]")
            add("\"Accept-Encoding\":[\"gzip, deflate\"]")
            add("\"Connection\":[\"keep-alive\"]")
            add("\"Pragma\":\"no-cache\"")
        }.joinToString(",")
        return """"tcpSettings":{"header":{"type":"http","request":{"version":"1.1","method":"GET",""" +
            """"path":["${q(path)}"],"headers":{$headers}}}}"""
    }

    private fun vnextSettings(
        address: String,
        port: Int,
        userJson: String,
        packetEncoding: String,
    ): String {
        val encoding = packetEncoding.takeIf { it.isNotBlank() }
            ?.let { ",\"packetEncoding\":\"${q(it)}\"" }
            .orEmpty()
        return """{"vnext":[{"address":"${q(address)}","port":$port,"users":[{$userJson}]}]$encoding}"""
    }

    private fun q(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
