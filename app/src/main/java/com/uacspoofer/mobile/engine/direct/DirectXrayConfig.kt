package com.uacspoofer.mobile.engine.direct

import com.uacspoofer.mobile.mci.IranDirectRules
import com.uacspoofer.mobile.vpn.VpnPauseStore
import com.uacspoofer.mobile.settings.DirectDomainWhitelist

/**
 * Builds the Xray JSON for engine 4.
 *
 * When [DirectEngineSettings.googleFronting] or [DirectEngineSettings.echEnabled] is on, the
 * config follows the MITM domain-fronting pattern (tunnel decrypt + freedom TLS re-pack), adapted
 * from patterniha/MITM-DomainFronting: Google hosts are re-dialed with SNI www.google.com; CF hosts
 * are re-dialed with ECH (outer SNI cloudflare-ech.com). Iranian / private destinations use geosite:category-ir and geoip:ir via GeoAssets.
 */
internal object DirectXrayConfig {
    const val TAG_TLS = "direct-tls"
    const val TAG_TCP = "direct-tcp-frag"
    const val TAG_PLAIN = "direct-plain"
    const val TAG_UDP = "direct-udp"
    const val TAG_NOISE = "direct-udp-noise"
    const val TAG_GOOGLE = "tls-repack-google"
    const val TAG_ECH = "tls-repack-ech"
    const val TAG_DNS_REPACK = "tls-repack-dns"

    private val GOOGLE_DOMAINS = listOf(
        "domain:google.com", "domain:googleapis.com", "domain:gstatic.com", "domain:googleusercontent.com",
        "domain:googlevideo.com", "domain:youtube.com", "domain:ytimg.com", "domain:ggpht.com",
        "domain:youtu.be", "domain:youtube-nocookie.com", "domain:withgoogle.com", "domain:google.com.",
        "domain:googleadservices.com", "domain:googlesyndication.com", "full:dns.google",
    )
    /** CF-fronted names that benefit from ECH on MCI (SNI filter). Keep tight — IP-blocked hosts still fail. */
    private val ECH_DOMAINS = listOf(
        "domain:x.com", "domain:twitter.com", "domain:twimg.com", "domain:t.co",
        "domain:discord.com", "domain:discordapp.com", "domain:discord.gg",
        "domain:cloudflare.com", "domain:cloudflare-ech.com", "domain:cloudflare-dns.com",
        "domain:medium.com", "domain:cdnjs.cloudflare.com",
    )

    fun build(
        raw: DirectEngineSettings,
        socksAddress: String,
        socksPort: Int,
        tunMtu: Int,
        nativeTun: Boolean,
        ca: DirectCa.Material?,
        noiseAllowed: Boolean = true,
        mitmH11Port: Int = 0,
        mitmH211Port: Int = 0,
    ): String {
        val s = raw.validated()
        val mitm = (s.googleFronting || s.echEnabled) && ca != null
        return if (mitm) buildMitm(s, socksAddress, socksPort, tunMtu, nativeTun, ca!!, mitmH11Port, mitmH211Port)
        else buildPlain(s, socksAddress, socksPort, tunMtu, nativeTun, noiseAllowed)
    }

    private fun buildMitm(
        s: DirectEngineSettings,
        socksAddress: String,
        socksPort: Int,
        tunMtu: Int,
        nativeTun: Boolean,
        ca: DirectCa.Material,
        mitmH11Port: Int,
        mitmH211Port: Int,
    ): String {
        require(mitmH11Port in 1..65535 && mitmH211Port in 1..65535 && mitmH11Port != mitmH211Port) {
            "MITM decrypt ports must be two distinct free TCP ports"
        }
        val certJson = ca.certificatePemLines.joinToString(",") { "\"${q(it)}\"" }
        val keyJson = ca.keyPemLines.joinToString(",") { "\"${q(it)}\"" }
        val certBlock = """{"usage":"issue","certificate":[$certJson],"key":[$keyJson]}"""
        val googleDomains = GOOGLE_DOMAINS.joinToString(",") { "\"$it\"" }
        val echDomains = ECH_DOMAINS.joinToString(",") { "\"$it\"" }
        val iranDomains = IranDirectRules.domainsJson()
        val privateIps = IranDirectRules.ipsJson()
        val irIps = IranDirectRules.irIpsJson()
        val ipStrategy = if (s.ipv6) "ForceIP" else "ForceIPv4"

        val inbounds = buildList {
            add("""{"tag":"socks-in","listen":"${q(socksAddress)}","port":$socksPort,"protocol":"socks","settings":{"auth":"noauth","udp":true,"ip":"${q(socksAddress)}","userLevel":8},"sniffing":{"enabled":true,"destOverride":["tls","http"],"routeOnly":false}}""")
            if (nativeTun) add("""{"tag":"tun-in","protocol":"tun","settings":{"name":"xray0","MTU":$tunMtu,"userLevel":8},"sniffing":{"enabled":true,"destOverride":["tls","http"],"routeOnly":false}}""")
            add("""{"tag":"tls-decrypt-h11","listen":"127.0.0.1","port":$mitmH11Port,"protocol":"tunnel","settings":{"network":"tcp","port":443,"followRedirect":true},"streamSettings":{"security":"tls","tlsSettings":{"alpn":["http/1.1"],"certificates":[$certBlock]}}}""")
            add("""{"tag":"tls-decrypt-h211","listen":"127.0.0.1","port":$mitmH211Port,"protocol":"tunnel","settings":{"network":"tcp","port":443,"followRedirect":true},"streamSettings":{"security":"tls","tlsSettings":{"alpn":["h2","http/1.1"],"certificates":[$certBlock]}}}""")
        }.joinToString(",\n")

        val outbounds = buildList {
            add("""{"tag":"block","protocol":"blackhole"}""")
            add("""{"tag":"dns-out","protocol":"dns","settings":{"rewriteNetwork":"tcp"}}""")
            add("""{"tag":"$TAG_PLAIN","protocol":"freedom","streamSettings":{"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            add("""{"tag":"$TAG_UDP","protocol":"freedom","streamSettings":{"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            add("""{"tag":"redirect-out-h11","protocol":"freedom","settings":{"redirect":"127.0.0.1:$mitmH11Port"}}""")
            add("""{"tag":"redirect-out-h211","protocol":"freedom","settings":{"redirect":"127.0.0.1:$mitmH211Port"}}""")
            if (s.googleFronting) {
                add("""{"tag":"$TAG_GOOGLE","protocol":"freedom","streamSettings":{"security":"tls","tlsSettings":{"serverName":"www.google.com","verifyPeerCertByName":"FromMitM,www.google.com,dns.google,www.youtube.com,www.googlevideo.com","alpn":["FromMitM"],"fingerprint":"chrome"},"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            }
            if (s.echEnabled) {
                add("""{"tag":"$TAG_ECH","protocol":"freedom","streamSettings":{"security":"tls","tlsSettings":{"serverName":"FromMitM","echConfigList":"${DirectEngineSettings.CLOUDFLARE_ECH_CONFIG_LIST}","verifyPeerCertByName":"FromMitM","alpn":["FromMitM"],"fingerprint":"chrome"},"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            }
            // Fronted DoH: talk to 1.1.1.1 with an allowed outer SNI (microsoft), verify as CF DNS.
            add("""{"tag":"$TAG_DNS_REPACK","protocol":"freedom","settings":{"redirect":"1.1.1.1:443"},"streamSettings":{"security":"tls","tlsSettings":{"serverName":"www.microsoft.com","verifyPeerCertByName":"FromMitM,www.microsoft.com,cloudflare-dns.com,one.one.one.one,dns.google","alpn":["FromMitM"],"fingerprint":"chrome"},"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            if (s.fragmentEnabled) {
                val stages = buildList {
                    add(fragment("tlshello", s.helloLengths, s.helloDelays, s.helloMaxSplit))
                    if (s.secondStageEnabled) add(fragment("1-1", s.secondLengths, s.secondDelays, s.secondMaxSplit))
                }.joinToString(",")
                add("""{"tag":"$TAG_TLS","protocol":"freedom","streamSettings":{"finalmask":{"tcp":[$stages]},"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            }
        }.joinToString(",\n")

        val accepted = if (nativeTun) """["socks-in","tun-in"]""" else """["socks-in"]"""
        val rules = buildList {

            if (VpnPauseStore.isPaused()) {
                add("""{"type":"field","inboundTag":["dns-unfiltered"],"outboundTag":"$TAG_PLAIN"}""")
            } else {
                add("""{"type":"field","inboundTag":["dns-unfiltered"],"outboundTag":"$TAG_DNS_REPACK"}""")
            }
            add("""{"type":"field","inboundTag":$accepted,"port":"53","outboundTag":"dns-out"}""")
            if (VpnPauseStore.isPaused()) {
                add("""{"type":"field","network":"tcp,udp","outboundTag":"$TAG_PLAIN"}""")
            }
            val wlJson = DirectDomainWhitelist.xrayDomainsJson(DirectDomainWhitelist.snapshotOrEmpty())
            if (wlJson.isNotEmpty()) {
                add("""{"type":"field","network":"tcp,udp","domain":[$wlJson],"outboundTag":"$TAG_PLAIN"}""")
            }
            if (s.iranDirect) {
                add("""{"type":"field","network":"tcp","domain":[$iranDomains],"outboundTag":"$TAG_PLAIN"}""")
                add("""{"type":"field","network":"udp","domain":[$iranDomains],"outboundTag":"$TAG_UDP"}""")
                add("""{"type":"field","network":"tcp","ip":[$irIps],"outboundTag":"$TAG_PLAIN"}""")
                add("""{"type":"field","network":"udp","ip":[$irIps],"outboundTag":"$TAG_UDP"}""")
            }
            add("""{"type":"field","network":"tcp","ip":[$privateIps],"outboundTag":"$TAG_PLAIN"}""")
            add("""{"type":"field","network":"udp","ip":[$privateIps],"outboundTag":"$TAG_UDP"}""")
            if (!s.ipv6) add("""{"type":"field","ip":["::/0"],"outboundTag":"block"}""")
            // After MITM decrypt:
            if (s.googleFronting) {
                add("""{"type":"field","domain":["domain:googlevideo.com"],"inboundTag":["tls-decrypt-h11"],"outboundTag":"$TAG_GOOGLE"}""")
                add("""{"type":"field","domain":[$googleDomains],"inboundTag":["tls-decrypt-h211"],"outboundTag":"$TAG_GOOGLE"}""")
            }
            if (s.echEnabled) {
                add("""{"type":"field","domain":[$echDomains],"inboundTag":["tls-decrypt-h211"],"outboundTag":"$TAG_ECH"}""")
            }
            add("""{"type":"field","inboundTag":["tls-decrypt-h11","tls-decrypt-h211"],"outboundTag":"block"}""")
            // Before MITM: redirect 443 into decrypt tunnels
            if (s.googleFronting) {
                add("""{"type":"field","network":"tcp","port":"443","domain":["domain:googlevideo.com"],"outboundTag":"redirect-out-h11"}""")
                add("""{"type":"field","network":"tcp","port":"443","domain":[$googleDomains],"outboundTag":"redirect-out-h211"}""")
            }
            if (s.echEnabled) {
                add("""{"type":"field","network":"tcp","port":"443","domain":[$echDomains],"outboundTag":"redirect-out-h211"}""")
            }
            add("""{"type":"field","ip":["10.10.34.0/24"],"outboundTag":"block"}""")
            if (s.blockQuic) {
                add("""{"type":"field","network":"udp","protocol":["quic"],"outboundTag":"block"}""")
                add("""{"type":"field","network":"udp","port":"443","outboundTag":"block"}""")
            }
            add("""{"type":"field","network":"udp","outboundTag":"$TAG_UDP"}""")
            if (s.fragmentEnabled) {
                add("""{"type":"field","network":"tcp","protocol":["tls"],"outboundTag":"$TAG_TLS"}""")
                add("""{"type":"field","network":"tcp","port":"443","outboundTag":"$TAG_TLS"}""")
            }
            add("""{"type":"field","network":"tcp","outboundTag":"$TAG_PLAIN"}""")
        }.joinToString(",\n")

        val googleHints = """"www.google.com":["142.251.153.4","142.250.180.68"],"dns.google":["8.8.8.8","8.8.4.4"],"cloudflare-dns.com":["104.16.248.249","104.16.249.249"],"1.1.1.1":["1.1.1.1"]"""
        val domestic = if (s.iranDirect) ""","domains":[$iranDomains]""" else ""
        val dns = """"dns":{"hosts":{$googleHints},"servers":[{"tag":"dns-domestic","address":"${q(s.domesticDns)}"$domestic,"skipFallback":true},{"tag":"dns-unfiltered","address":"${q(s.unfilteredDns)}","timeoutMs":12000,"finalQuery":true}],"queryStrategy":"${if (s.ipv6) "UseIP" else "UseIPv4"}","serveStale":true},"""
        val stats = if (nativeTun) {
            """"stats":{},"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}},"system":{"statsInboundUplink":true,"statsInboundDownlink":true,"statsOutboundUplink":true,"statsOutboundDownlink":true}},"""
        } else """"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}}},"""
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

    private fun buildPlain(
        s: DirectEngineSettings,
        socksAddress: String,
        socksPort: Int,
        tunMtu: Int,
        nativeTun: Boolean,
        noiseAllowed: Boolean,
    ): String {
        // Legacy serverless fragment path (kept for networks that do not drop fragmented hellos).
        val ipStrategy = if (s.ipv6) "ForceIP" else "ForceIPv4"
        val sockopt = """"sockopt":{"domainStrategy":"$ipStrategy","happyEyeballs":{"tryDelayMs":300,"prioritizeIPv6":${s.ipv6},"interleave":4,"maxConcurrentTry":20}}"""
        val useFrag = s.fragmentEnabled
        val useNoise = s.udpNoise && noiseAllowed
        val helloStages = if (useFrag) buildList {
            add(fragment("tlshello", s.helloLengths, s.helloDelays, s.helloMaxSplit))
            if (s.secondStageEnabled) add(fragment("1-1", s.secondLengths, s.secondDelays, s.secondMaxSplit))
        }.joinToString(",") else ""
        val outbounds = buildList {
            add("""{"tag":"block","protocol":"blackhole","settings":{}}""")
            add("""{"tag":"dns-out","protocol":"dns","settings":{"rewriteNetwork":"tcp","rules":[{"action":"hijack"}]}}""")
            add("""{"tag":"$TAG_PLAIN","protocol":"freedom","settings":{},"streamSettings":{$sockopt}}""")
            add("""{"tag":"$TAG_UDP","protocol":"freedom","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"$ipStrategy"}}}""")
            if (useFrag) add("""{"tag":"$TAG_TLS","protocol":"freedom","settings":{},"streamSettings":{"finalmask":{"tcp":[$helloStages]},$sockopt}}""")
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
        val tcpOther = if (s.tcpFragmentEnabled) TAG_TCP else TAG_PLAIN
        val rules = buildList {

            if (VpnPauseStore.isPaused()) {
                add("""{"type":"field","inboundTag":["dns-unfiltered"],"outboundTag":"$TAG_PLAIN"}""")
            } else {
                add("""{"type":"field","inboundTag":["dns-unfiltered"],"outboundTag":"$tlsTag"}""")
            }
            add("""{"type":"field","inboundTag":["dns-domestic","dns-fallback"],"outboundTag":"$TAG_PLAIN"}""")
            add("""{"type":"field","inboundTag":$inboundTags,"port":"53","outboundTag":"dns-out"}""")
            if (VpnPauseStore.isPaused()) {
                add("""{"type":"field","network":"tcp,udp","outboundTag":"$TAG_PLAIN"}""")
            }
            val wlJson = DirectDomainWhitelist.xrayDomainsJson(DirectDomainWhitelist.snapshotOrEmpty())
            if (wlJson.isNotEmpty()) {
                add("""{"type":"field","network":"tcp,udp","domain":[$wlJson],"outboundTag":"$TAG_PLAIN"}""")
            }
            if (s.iranDirect) {
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
            add("""{"type":"field","network":"udp","outboundTag":"${if (useNoise) TAG_NOISE else TAG_UDP}"}""")
            add("""{"type":"field","network":"tcp","protocol":["tls"],"outboundTag":"$tlsTag"}""")
            add("""{"type":"field","network":"tcp","port":"443","outboundTag":"$tlsTag"}""")
            add("""{"type":"field","network":"tcp","outboundTag":"$tcpOther"}""")
        }.joinToString(",\n")
        val sniffing = """"sniffing":{"enabled":true,"destOverride":["tls","http","quic"],"routeOnly":false}"""
        val inbounds = buildList {
            add("""{"tag":"socks-in","listen":"${q(socksAddress)}","port":$socksPort,"protocol":"socks","settings":{"auth":"noauth","udp":true,"ip":"${q(socksAddress)}","userLevel":8},$sniffing}""")
            if (nativeTun) add("""{"tag":"tun-in","protocol":"tun","settings":{"name":"xray0","MTU":$tunMtu,"userLevel":8},$sniffing}""")
        }.joinToString(",\n")
        // challenges.cloudflare.com is the DoH dial target: resolve it locally or DoH deadlocks on itself.
        val domesticDomains = if (s.iranDirect) ""","domains":[$iranDomains,"full:challenges.cloudflare.com"]""" else ""","domains":["full:challenges.cloudflare.com"]"""
        // patterniha: DoH by name (SNI cloudflare-dns.com) dialed at challenges.cloudflare.com; h2c-to-IP is MITM-only.
        // If DoH fails, fall back to the system/operator resolver instead of resolving nothing.
        val doh = if (s.unfilteredDns.startsWith("h2c://")) PLAIN_DOH else s.unfilteredDns
        val domesticFinal = ""","skipFallback":true,"finalQuery":true"""
        val dns = """"dns":{"hosts":{"cloudflare-dns.com":"challenges.cloudflare.com"},"servers":[{"tag":"dns-domestic","address":"${q(s.domesticDns)}"$domesticDomains,"timeoutMs":12000$domesticFinal},{"tag":"dns-unfiltered","address":"${q(doh)}","timeoutMs":12000},{"tag":"dns-fallback","address":"${q(s.domesticDns)}","timeoutMs":8000}],"queryStrategy":"${if (s.ipv6) "UseIP" else "UseIPv4"}","serveStale":true,"tag":"dns-query"},"""
        val stats = if (nativeTun) {
            """"stats":{},"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}},"system":{"statsInboundUplink":true,"statsInboundDownlink":true,"statsOutboundUplink":true,"statsOutboundDownlink":true}},"""
        } else """"policy":{"levels":{"8":{"handshake":8,"connIdle":300,"uplinkOnly":2,"downlinkOnly":5}}},"""
        return """{"log":{"loglevel":"warning","dnsLog":false},$stats$dns"inbounds":[$inbounds],"outbounds":[$outbounds],"routing":{"domainStrategy":"IPOnDemand","rules":[$rules]}}"""
    }

    const val PLAIN_DOH = "https://cloudflare-dns.com/dns-query"

    private fun fragment(packets: String, lengths: String, delays: String, maxSplit: Int): String {
        fun arr(v: String) = v.split(',').joinToString(",") { "\"${q(it.trim())}\"" }
        return """{"type":"fragment","settings":{"packets":"$packets","lengths":[${arr(lengths)}],"delays":[${arr(delays)}],"maxSplit":"$maxSplit"}}"""
    }

    private fun q(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
}
