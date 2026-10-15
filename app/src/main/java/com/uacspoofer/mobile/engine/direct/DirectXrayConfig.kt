package com.uacspoofer.mobile.engine.direct

import com.uacspoofer.mobile.mci.IranDirectRules

/**
 * Builds the Xray JSON for engine 4. No proxy outbound exists: every outbound is `freedom`
 * (direct), shaped with finalmask fragment / noise so SNI-based DPI cannot read the ClientHello.
 * The bundled core ships no geosite/geoip files, so Iranian and private targets use the app's
 * built-in literal lists instead of geoip:ir.
 */
internal object DirectXrayConfig {
    const val TAG_TLS = "direct-tls"
    const val TAG_TCP = "direct-tcp-frag"
    const val TAG_PLAIN = "direct-plain"
    const val TAG_UDP = "direct-udp"
    const val TAG_NOISE = "direct-udp-noise"

    fun build(
        raw: DirectEngineSettings,
        socksAddress: String,
        socksPort: Int,
        tunMtu: Int,
        nativeTun: Boolean,
        noiseAllowed: Boolean = true,
    ): String {
        val s = raw.validated()
        val ipStrategy = if (s.ipv6) "ForceIP" else "ForceIPv4"
        val udpStrategy = if (s.ipv6) "ForceIPv6v4" else "ForceIPv4"
        val eyeballs = if (s.ipv6) ""","happyEyeballs":{"tryDelayMs":300,"prioritizeIPv6":true,"interleave":4,"maxConcurrentTry":20}""" else ""
        val sockopt = """"sockopt":{"domainStrategy":"$ipStrategy"$eyeballs}"""

        val helloStages = buildList {
            add(fragment("tlshello", s.helloLengths, s.helloDelays, s.helloMaxSplit))
            if (s.secondStageEnabled) add(fragment("1-1", s.secondLengths, s.secondDelays, s.secondMaxSplit))
        }.joinToString(",")
        val useNoise = s.udpNoise && noiseAllowed

        val outbounds = buildList {
            add("""{"tag":"block","protocol":"blackhole","settings":{}}""")
            add("""{"tag":"dns-out","protocol":"dns","settings":{"rewriteNetwork":"tcp","rules":[{"action":"hijack"}]}}""")
            add("""{"tag":"$TAG_PLAIN","protocol":"freedom","settings":{},"streamSettings":{$sockopt}}""")
            add("""{"tag":"$TAG_UDP","protocol":"freedom","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"$udpStrategy"}}}""")
            add("""{"tag":"$TAG_TLS","protocol":"freedom","settings":{},"streamSettings":{"finalmask":{"tcp":[$helloStages]},$sockopt}}""")
            if (s.tcpFragmentEnabled) {
                add("""{"tag":"$TAG_TCP","protocol":"freedom","settings":{},"streamSettings":{"finalmask":{"tcp":[${fragment("1-1", s.tcpLengths, s.tcpDelays, s.tcpMaxSplit)}]},$sockopt}}""")
            }
            if (useNoise) {
                val noise = List(24) { """{"rand":"1200-1230","delay":"10"}""" }.joinToString(",")
                add("""{"tag":"$TAG_NOISE","protocol":"freedom","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"$udpStrategy"},"finalmask":{"udp":[{"type":"noise","settings":{"reset":"28","noise":[$noise]}}]}}}""")
            }
        }.joinToString(",\n")

        val inboundTags = if (nativeTun) "[\"socks-in\",\"tun-in\"]" else "[\"socks-in\"]"
        val iranDomains = IranDirectRules.domainsJson()
        val privateIps = IranDirectRules.ipsJson()
        val tcpOther = if (s.tcpFragmentEnabled) TAG_TCP else TAG_PLAIN
        val rules = buildList {
            add("""{"type":"field","inboundTag":["dns-unfiltered"],"outboundTag":"$TAG_TLS"}""")
            add("""{"type":"field","inboundTag":["dns-domestic"],"outboundTag":"$TAG_PLAIN"}""")
            add("""{"type":"field","inboundTag":$inboundTags,"port":"53","outboundTag":"dns-out"}""")
            if (s.iranDirect) {
                add("""{"type":"field","network":"tcp","domain":[$iranDomains],"outboundTag":"$TAG_PLAIN"}""")
                add("""{"type":"field","network":"udp","domain":[$iranDomains],"outboundTag":"$TAG_UDP"}""")
            }
            add("""{"type":"field","network":"tcp","ip":[$privateIps],"outboundTag":"$TAG_PLAIN"}""")
            add("""{"type":"field","network":"udp","ip":[$privateIps],"outboundTag":"$TAG_UDP"}""")
            if (!s.ipv6) add("""{"type":"field","ip":["::/0"],"outboundTag":"block"}""")
            if (s.blockQuic) {
                add("""{"type":"field","network":"udp","protocol":["quic"],"outboundTag":"block"}""")
                add("""{"type":"field","network":"udp","port":"443","outboundTag":"block"}""")
            }
            add("""{"type":"field","network":"udp","outboundTag":"${if (useNoise) TAG_NOISE else TAG_UDP}"}""")
            add("""{"type":"field","network":"tcp","protocol":["tls"],"outboundTag":"$TAG_TLS"}""")
            add("""{"type":"field","network":"tcp","port":"443","outboundTag":"$TAG_TLS"}""")
            add("""{"type":"field","network":"tcp","outboundTag":"$tcpOther"}""")
        }.joinToString(",\n")

        val sniffing = """"sniffing":{"enabled":true,"destOverride":["http","tls","quic"],"routeOnly":true}"""
        val inbounds = buildList {
            add("""{"tag":"socks-in","listen":"${q(socksAddress)}","port":$socksPort,"protocol":"socks","settings":{"auth":"noauth","udp":true,"ip":"${q(socksAddress)}","userLevel":8},$sniffing}""")
            if (nativeTun) add("""{"tag":"tun-in","protocol":"tun","settings":{"name":"xray0","MTU":$tunMtu,"userLevel":8},$sniffing}""")
        }.joinToString(",\n")

        val domesticDomains = if (s.iranDirect) ""","domains":[$iranDomains]""" else ""
        val dns = """"dns":{"hosts":{"cloudflare-dns.com":["104.16.248.249","104.16.249.249"],"dns.google":["8.8.8.8","8.8.4.4"]},"servers":[""" +
            """{"tag":"dns-domestic","address":"${q(s.domesticDns)}"$domesticDomains,"skipFallback":true},""" +
            """{"tag":"dns-unfiltered","address":"${q(s.unfilteredDns)}","timeoutMs":12000}""" +
            """],"queryStrategy":"${if (s.ipv6) "UseIP" else "UseIPv4"}","serveStale":true,"tag":"dns-query"},"""
        val stats = if (nativeTun) {
            """"stats":{},"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}},"system":{"statsInboundUplink":true,"statsInboundDownlink":true,"statsOutboundUplink":true,"statsOutboundDownlink":true}},"""
        } else {
            """"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}}},"""
        }
        return """
            {
              "log":{"loglevel":"warning","dnsLog":false},
              $stats
              $dns
              "inbounds":[$inbounds],
              "outbounds":[$outbounds],
              "routing":{"domainStrategy":"IPIfNonMatch","rules":[$rules]}
            }
        """.trimIndent()
    }

    private fun fragment(packets: String, lengths: String, delays: String, maxSplit: Int): String {
        fun arr(v: String) = v.split(',').joinToString(",") { "\"${q(it.trim())}\"" }
        return """{"type":"fragment","settings":{"packets":"$packets","lengths":[${arr(lengths)}],"delays":[${arr(delays)}],"maxSplit":"$maxSplit"}}"""
    }

    private fun q(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
}
