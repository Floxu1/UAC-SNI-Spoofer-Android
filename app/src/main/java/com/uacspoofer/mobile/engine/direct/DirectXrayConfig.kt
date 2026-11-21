package com.uacspoofer.mobile.engine.direct

import com.uacspoofer.mobile.vpn.AdaptiveDnsResolver

import com.uacspoofer.mobile.mci.IranDirectRules
import com.uacspoofer.mobile.settings.DirectDomainWhitelist
import com.uacspoofer.mobile.vpn.VpnPauseStore

internal object DirectXrayConfig {
    const val TAG_TLS = "direct-tls"
    const val TAG_TCP = "direct-tcp-frag"
    const val TAG_PLAIN = "direct-plain"
    const val TAG_UDP = "direct-udp"
    const val TAG_NOISE = "direct-udp-noise"
    const val TAG_WIREGUARD = "direct-wireguard"
    const val ADAPTIVE_PREFIX = "direct-hello-"
    const val PROBE_URL = "https://www.youtube.com/generate_204"
    private const val BALANCER = "direct-clienthello"
    private const val RECORD_SNI = "record-sni"
    private val youtubeDomains = listOf(
        "domain:youtube.com", "domain:youtu.be", "domain:youtube-nocookie.com",
        "domain:googlevideo.com", "domain:ytimg.com", "full:yt3.ggpht.com",
        "full:youtubei.googleapis.com", "full:youtube.googleapis.com",
    ).joinToString(",") { "\"$it\"" }
    val localMasks = mapOf(
        "oob" to listOf("--oob", "0"),
        RECORD_SNI to listOf("--tlsrec", "2+s"),
    )

    private val adaptiveMasks = mapOf(
        "record-small" to fragment("tlshello", "6,98,48-96", "0", 7),
        "record-medium" to fragment("tlshello", "48-128", "0", 6),
        "tcp-small" to fragment("1-1", "32-64", "1-2", 6),
        "tcp-medium" to fragment("1-1", "80-160", "1-2", 5),
    )
    val trafficTags = listOf(TAG_TLS, TAG_TCP, TAG_PLAIN, TAG_UDP, TAG_NOISE, TAG_WIREGUARD) +
        (adaptiveMasks.keys + localMasks.keys).map { "$ADAPTIVE_PREFIX$it" }

    fun build(
        raw: DirectEngineSettings,
        socksAddress: String,
        socksPort: Int,
        tunMtu: Int,
        nativeTun: Boolean,
        noiseAllowed: Boolean = true,
        maskPorts: Map<String, Int> = emptyMap(),
        wireGuardSocksPort: Int? = null,
        countryExit: Boolean = false,
        sessionDns: AdaptiveDnsResolver? = null,
    ): String {
        val s = raw.validated()
        val paused = VpnPauseStore.isPaused()
        val chained = wireGuardSocksPort != null && !paused
        val adaptive = s.adaptiveClientHello && !paused && !chained
        val ipStrategy = if (s.ipv6) "ForceIP" else "ForceIPv4"
        val sockopt = """"sockopt":{"domainStrategy":"$ipStrategy","happyEyeballs":{"tryDelayMs":300,"prioritizeIPv6":${s.ipv6},"interleave":4,"maxConcurrentTry":20}}"""
        val useFrag = !s.adaptiveClientHello && s.fragmentEnabled && !paused
        val useNoise = s.udpNoise && noiseAllowed
        val helloStages = if (useFrag) buildList {
            add(fragment("tlshello", s.helloLengths, s.helloDelays, s.helloMaxSplit))
            if (s.secondStageEnabled) add(fragment("1-1", s.secondLengths, s.secondDelays, s.secondMaxSplit))
        }.joinToString(",") else ""
        val outbounds = buildList {
            add("""{"tag":"block","protocol":"blackhole","settings":{}}""")
            add("""{"tag":"dns-out","protocol":"dns","settings":{"rewriteNetwork":"tcp","rules":[{"qType":"1,28","action":"hijack"},{"action":"return","rCode":0}]}}""")
            add("""{"tag":"$TAG_PLAIN","protocol":"freedom","settings":{},"streamSettings":{$sockopt}}""")
            add("""{"tag":"$TAG_UDP","protocol":"freedom","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            if (chained) add("""{"tag":"$TAG_WIREGUARD","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":$wireGuardSocksPort}]}}""")
            if (useFrag) add("""{"tag":"$TAG_TLS","protocol":"freedom","settings":{},"streamSettings":{"finalmask":{"tcp":[$helloStages]},$sockopt}}""")
            if (adaptive) {
                for ((name, mask) in adaptiveMasks) {
                    add("""{"tag":"$ADAPTIVE_PREFIX$name","protocol":"freedom","settings":{},"streamSettings":{"finalmask":{"tcp":[$mask]},$sockopt}}""")
                }
                for ((name, port) in maskPorts) {
                    val local = "direct-local-$name"
                    add("""{"tag":"$local","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":$port}]}}""")
                    // Observatory dials skip outbound targetStrategy. Resolve in the dialer before chaining.
                    add("""{"tag":"$ADAPTIVE_PREFIX$name","protocol":"freedom","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"$ipStrategy","dialerProxy":"$local"}}}""")
                }
            }
            if (s.tcpFragmentEnabled) add("""{"tag":"$TAG_TCP","protocol":"freedom","settings":{},"streamSettings":{"finalmask":{"tcp":[${fragment("1-1", s.tcpLengths, s.tcpDelays, s.tcpMaxSplit)}]},$sockopt}}""")
            if (useNoise) {
                val noise = List(24) { """{"rand":"1200-1230","delay":"10"}""" }.joinToString(",")
                add("""{"tag":"$TAG_NOISE","protocol":"freedom","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"$ipStrategy"},"finalmask":{"udp":[{"type":"noise","settings":{"reset":"28","noise":[$noise]}}]}}}""")
            }
        }.joinToString(",\n")
        val inboundTags = if (nativeTun) "[\"socks-in\",\"tun-in\"]" else "[\"socks-in\"]"
        val iranDomains = IranDirectRules.domainsJson()
        val privateIps = IranDirectRules.ipsJson()
        val irIps = IranDirectRules.irIpsJson()
        val tlsTag = if (useFrag) TAG_TLS else TAG_PLAIN
        val dnsRecordTag = if (adaptive && RECORD_SNI in maskPorts) "$ADAPTIVE_PREFIX$RECORD_SNI" else tlsTag
        val dnsOobTag = if (adaptive && "oob" in maskPorts) "${ADAPTIVE_PREFIX}oob" else dnsRecordTag
        val tlsRoute = if (adaptive) """"balancerTag":"$BALANCER"""" else """"outboundTag":"$tlsTag""""
        val tcpOther = if (s.tcpFragmentEnabled) TAG_TCP else TAG_PLAIN
        val rules = buildList {
            add("""{"type":"field","inboundTag":$inboundTags,"port":"53","outboundTag":"dns-out"}""")
            if (chained) {
                add("""{"type":"field","inboundTag":["dns-unfiltered","dns-cloudflare","dns-google"],"outboundTag":"$TAG_WIREGUARD"}""")
            } else {
                // Use static DoH routes so the TLS observer cannot depend on itself.
                add("""{"type":"field","inboundTag":["dns-unfiltered"],"outboundTag":"$tlsTag"}""")
                add("""{"type":"field","inboundTag":["dns-cloudflare"],"outboundTag":"$dnsRecordTag"}""")
                add("""{"type":"field","inboundTag":["dns-google"],"outboundTag":"$dnsOobTag"}""")
            }
            add("""{"type":"field","inboundTag":["dns-domestic","dns-fallback"],"outboundTag":"$TAG_PLAIN"}""")
            if (paused) {
                add("""{"type":"field","network":"tcp,udp","outboundTag":"$TAG_PLAIN"}""")
            }
            val wlJson = DirectDomainWhitelist.xrayDomainsJson(DirectDomainWhitelist.snapshotOrEmpty())
            if (wlJson.isNotEmpty()) {
                add("""{"type":"field","network":"tcp,udp","domain":[$wlJson],"outboundTag":"$TAG_PLAIN"}""")
            }
            if (s.iranDirect) {
                // YouTube CDN addresses can be classified as domestic even when the site is blocked.
                if (chained) add("""{"type":"field","network":"tcp","domain":[$youtubeDomains],"outboundTag":"$TAG_WIREGUARD"}""")
                add("""{"type":"field","network":"tcp","domain":[$iranDomains],"outboundTag":"$TAG_PLAIN"}""")
                add("""{"type":"field","network":"udp","domain":[$iranDomains],"outboundTag":"$TAG_UDP"}""")
                add("""{"type":"field","network":"tcp","ip":[$irIps],"outboundTag":"$TAG_PLAIN"}""")
                add("""{"type":"field","network":"udp","ip":[$irIps],"outboundTag":"$TAG_UDP"}""")
            }
            add("""{"type":"field","ip":["10.10.34.0/24"],"outboundTag":"block"}""")
            add("""{"type":"field","network":"tcp","ip":[$privateIps],"outboundTag":"$TAG_PLAIN"}""")
            add("""{"type":"field","network":"udp","ip":[$privateIps],"outboundTag":"$TAG_UDP"}""")
            if (!s.ipv6) add("""{"type":"field","ip":["::/0"],"outboundTag":"block"}""")
            if (s.blockQuic) {
                add("""{"type":"field","network":"udp","protocol":["quic"],"outboundTag":"block"}""")
                add("""{"type":"field","network":"udp","port":"443","outboundTag":"block"}""")
            }
            if (chained) {
                if (countryExit) add("""{"type":"field","network":"udp","outboundTag":"block"}""")
                add("""{"type":"field","network":"tcp,udp","outboundTag":"$TAG_WIREGUARD"}""")
            }
            add("""{"type":"field","network":"udp","outboundTag":"${if (useNoise) TAG_NOISE else TAG_UDP}"}""")
            add("""{"type":"field","network":"tcp","protocol":["tls"],$tlsRoute}""")
            add("""{"type":"field","network":"tcp","port":"443",$tlsRoute}""")
            add("""{"type":"field","network":"tcp","outboundTag":"$tcpOther"}""")
        }.joinToString(",\n")
        // ECH exposes a public name in outer SNI. Keep the application's original destination.
        val sniffing = """"sniffing":{"enabled":true,"destOverride":["tls","http","quic"],"routeOnly":true}"""
        val inbounds = buildList {
            add("""{"tag":"socks-in","listen":"${q(socksAddress)}","port":$socksPort,"protocol":"socks","settings":{"auth":"noauth","udp":true,"ip":"${q(socksAddress)}","userLevel":8},$sniffing}""")
            if (nativeTun) add("""{"tag":"tun-in","protocol":"tun","settings":{"name":"xray0","MTU":$tunMtu,"userLevel":8},$sniffing}""")
        }.joinToString(",\n")
        val domestic = if (s.iranDirect) listOf(
            """{"tag":"dns-domestic","address":"${q(s.domesticDns)}","domains":[$iranDomains],"timeoutMs":3000,"skipFallback":true,"finalQuery":true}""",
        ) else emptyList()
        val preferredDns = if (paused) null else sessionDns
        val resolverAddress = preferredDns?.url ?: s.unfilteredDns
        val defaultDns = preferredDns == null && s.unfilteredDns == DirectEngineSettings.DEFAULT_UNFILTERED_DNS
        val foreignDns = if (!chained && preferredDns != null && preferredDns.bootstrapHosts.isEmpty()) {
            resolverAddress.replaceFirst("https://", "https+local://")
        } else if (chained && !resolverAddress.contains("://")) {
            val host = if (resolverAddress.contains(':')) "[${resolverAddress.trim('[', ']')}]" else resolverAddress
            "tcp://$host"
        } else resolverAddress
        val customDns = if (defaultDns) emptyList() else listOf(
            """{"tag":"dns-unfiltered","address":"${q(foreignDns)}","timeoutMs":2000}""",
        )
        val youtubeDns = if (chained && s.iranDirect) listOf(
            """{"tag":"dns-unfiltered","address":"${q(foreignDns)}","domains":[$youtubeDomains],"timeoutMs":2500,"skipFallback":true}""",
        ) else emptyList()
        val fallbackDns = if (chained) listOf(
            """{"tag":"dns-unfiltered","address":"https://1.1.1.1/dns-query","timeoutMs":2500}""",
        ) else listOf(
            """{"tag":"dns-unfiltered","address":"https+local://1.1.1.1/dns-query","timeoutMs":1800}""",
            """{"tag":"dns-fallback","address":"${q(s.domesticDns)}","timeoutMs":1200,"unexpectedIPs":["10.10.34.0/24"]}""",
        )
        val dnsServers = (youtubeDns + domestic + customDns + listOf(
            """{"tag":"dns-cloudflare","address":"https://cloudflare-dns.com/dns-query","timeoutMs":1800}""",
            """{"tag":"dns-google","address":"https://dns.google/dns-query","timeoutMs":1800}""",
            """{"tag":"dns-cloudflare","address":"https://mozilla.cloudflare-dns.com/dns-query","timeoutMs":1800}""",
        ) + fallbackDns).joinToString(",")
        val dnsHosts = (mapOf(
            "cloudflare-dns.com" to listOf("104.16.248.249", "104.16.249.249"),
            "mozilla.cloudflare-dns.com" to listOf("172.64.41.4", "162.159.61.4"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
        ) + preferredDns?.bootstrapHosts.orEmpty()).entries.joinToString(",") { (host, addresses) ->
            val values = addresses.joinToString(",") { "\"${q(it)}\"" }
            "\"${q(host)}\":[$values]"
        }
        val dns = """"dns":{"hosts":{$dnsHosts},"servers":[$dnsServers],"queryStrategy":"${if (s.ipv6) "UseIP" else "UseIPv4"}","serveStale":true,"tag":"dns-query"},"""
        val stats = if (nativeTun) {
            """"stats":{},"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}},"system":{"statsInboundUplink":true,"statsInboundDownlink":true,"statsOutboundUplink":true,"statsOutboundDownlink":true}},"""
        } else """"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}}},"""
        val selector = """["$TAG_PLAIN","$ADAPTIVE_PREFIX"]"""
        val balancer = if (adaptive) ""","balancers":[{"tag":"$BALANCER","selector":$selector,"fallbackTag":"$dnsRecordTag","strategy":{"type":"leastLoad","settings":{"expected":1,"tolerance":0.2}}}]""" else ""
        val observer = if (adaptive) ""","burstObservatory":{"subjectSelector":$selector,"pingConfig":{"destination":"$PROBE_URL","interval":"10s","sampling":2,"timeout":"5s","httpMethod":"HEAD"}}""" else ""
        return """{"log":{"loglevel":"warning","dnsLog":false},$stats$dns"inbounds":[$inbounds],"outbounds":[$outbounds],"routing":{"domainStrategy":"IPOnDemand","rules":[$rules]$balancer}$observer}"""
    }

    private fun fragment(packets: String, lengths: String, delays: String, maxSplit: Int): String {
        fun arr(v: String) = v.split(',').joinToString(",") { "\"${q(it.trim())}\"" }
        return """{"type":"fragment","settings":{"packets":"$packets","lengths":[${arr(lengths)}],"delays":[${arr(delays)}],"maxSplit":"$maxSplit"}}"""
    }

    private fun q(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
}
