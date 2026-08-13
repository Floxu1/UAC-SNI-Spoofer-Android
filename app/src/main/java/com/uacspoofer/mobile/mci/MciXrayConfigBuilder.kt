package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.profiles.RuntimeProxyIdentity
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers

data class MciXrayRuntimeOptions(
    val identityOverride: RuntimeProxyIdentity? = null,
    val finalmaskEnabled: Boolean = true,
    val preserveEmptyAlpn: Boolean = false,
    val preserveTransportFields: Boolean = false,
) {
    companion object {
        val DEFAULT = MciXrayRuntimeOptions()
    }
}

internal object MciXrayConfigBuilder {
    fun build(
        edge: MciEdge,
        settings: AdvancedSettingsData,
        profile: ProxyProfile,
        nativeTun: Boolean,
        runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
    ): String {
        val s = settings.validated()
        val identity = runtimeOptions.identityOverride ?: profile.runtimeIdentity(s)
        validateIdentity(identity, runtimeOptions)

        fun outbound(tag: String, address: String, port: Int, maxSplit: Int, muxEnabled: Boolean): String {
            val protocolSettings = when (identity.protocol) {
                ProxyProtocol.TROJAN ->
                    """{"servers":[{"address":"${q(address)}","port":$port,"password":"${q(identity.credential)}"}]}"""
                ProxyProtocol.VLESS -> {
                    val flow = identity.flow.takeIf { it.isNotBlank() }
                        ?.let { ",\"flow\":\"${q(it)}\"" }.orEmpty()
                    """{"vnext":[{"address":"${q(address)}","port":$port,"users":[{"id":"${q(identity.credential)}","encryption":"${q(identity.encryption)}"$flow}]}]}"""
                }
                ProxyProtocol.VMESS ->
                    """{"vnext":[{"address":"${q(address)}","port":$port,"users":[{"id":"${q(identity.credential)}","alterId":${identity.alterId},"security":"${q(identity.encryption.ifBlank { "auto" })}"}]}]}"""
            }
            val stream = streamSettings(identity, s, maxSplit, nativeTun, runtimeOptions)
            return """{"tag":"${q(tag)}","protocol":"${identity.protocol.wireName}","settings":$protocolSettings,"streamSettings":$stream,"mux":{"enabled":$muxEnabled,"concurrency":${s.muxConcurrency}}}"""
        }

        val outbounds = buildList {
            add(outbound("proxy", edge.address, edge.port, edge.finalmaskMaxSplit, s.muxEnabled))
            add(outbound("probe-proxy", edge.address, edge.port, edge.finalmaskMaxSplit, false))
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
            if (s.blockUdp443) {
                add("""{"type":"field","network":"udp","port":"443","outboundTag":"block"}""")
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

        return """
            {
              "log":{"loglevel":"debug","dnsLog":true,"maskAddress":"quarter"},
              $rootExtras
              $dns
              "inbounds":[$inbounds],
              "outbounds":[$outbounds],
              "routing":{"domainStrategy":"${q(s.routingDomainStrategy)}","rules":[$routingRules]}
            }
        """.trimIndent()
    }

    private fun streamSettings(
        identity: RuntimeProxyIdentity,
        settings: AdvancedSettingsData,
        maxSplit: Int,
        nativeTun: Boolean,
        runtimeOptions: MciXrayRuntimeOptions,
    ): String {
        val requestedAlpn = identity.alpn.split(',').map(String::trim).filter(String::isNotBlank)
        val alpn = when {
            requestedAlpn.isNotEmpty() -> requestedAlpn
            runtimeOptions.preserveEmptyAlpn -> emptyList()
            else -> listOf(if (identity.network == "grpc") "h2" else "http/1.1")
        }
        val alpnField = alpn.takeIf(List<String>::isNotEmpty)
            ?.joinToString(",", prefix = ",\"alpn\":[", postfix = "]") { "\"${q(it)}\"" }
            .orEmpty()
        val fingerprint = identity.fingerprint.takeIf(String::isNotBlank)
            ?.let { ",\"fingerprint\":\"${q(it)}\"" }.orEmpty()
        val serverName = identity.sni.takeIf(String::isNotBlank)
            ?.let { "\"serverName\":\"${q(it)}\"," }.orEmpty()
        val tls = """"tlsSettings":{$serverName"allowInsecure":${identity.allowInsecure}$alpnField$fingerprint}"""
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
            "tcp" -> ""
            else -> error("Unsupported transport ${identity.network}")
        }
        val compatibilityArrays = if (nativeTun) {
            ",\"lengths\":[\"${settings.finalmaskLength}\"],\"delays\":[\"${settings.finalmaskDelayMs}\"]"
        } else {
            ""
        }
        val finalmask = if (runtimeOptions.finalmaskEnabled) {
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
        require(identity.security == "tls") { "Selected profile must use TLS" }
        if (!runtimeOptions.preserveTransportFields) {
            require(identity.sni.isNotBlank()) { "Selected profile SNI is missing" }
        }
        require(identity.network in setOf("ws", "tcp", "httpupgrade", "grpc")) {
            "Unsupported selected profile transport"
        }
        if (identity.network == "grpc") require(identity.serviceName.isNotBlank()) { "gRPC serviceName is missing" }
    }

    private fun q(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
