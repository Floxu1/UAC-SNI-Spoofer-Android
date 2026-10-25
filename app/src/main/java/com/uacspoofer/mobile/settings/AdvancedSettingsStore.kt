package com.uacspoofer.mobile.settings
import android.content.Context
import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.mci.MciEdge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val PATTNG_PYTHON_CIPHER_SUITES =
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:" +
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:" +
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:" +
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:" +
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:" +
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:" +
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384:" +
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384:" +
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:" +
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

const val CONNECTION_MODE_TUNNEL = "tunnel"
const val CONNECTION_MODE_PROXY = "proxy"

data class AdvancedSettingsData(
 val connectionMode:String=CONNECTION_MODE_TUNNEL,
 val primaryAddress:String="104.26.13.100",val primaryPort:Int=443,val primaryMaxSplit:Int=2,
 val irancellAddress:String="104.26.14.40",val irancellPort:Int=443,val irancellMaxSplit:Int=100,
 val fallbackAddress:String="104.26.14.200",val fallbackPort:Int=443,val fallbackMaxSplit:Int=2,
 val telegramRouteEnabled:Boolean=false,val telegramAddress:String="104.18.9.83",val telegramFallbackAddress:String="104.18.8.83",val telegramPort:Int=443,val telegramMaxSplit:Int=100,val telegramFingerprint:String="unsafe",val telegramCipherSuites:String=PATTNG_PYTHON_CIPHER_SUITES,val telegramSessionResumption:Boolean=false,
 val outboundProtocol:String="vless",val transportNetwork:String="ws",val transportSecurity:String="tls",val trojanPassword:String="4e4f1f70-5e56-4a76-bb9d-db087f4690c4",val tlsSni:String="jp3.yohototo.top",val tlsAlpn:String="http/1.1",val tlsFingerprint:String="unsafe",val wsHost:String="jp3.yohototo.top",val wsPath:String="/tyxyws",
 val finalmaskPacket:String="tlshello",val finalmaskLength:Int=5,val finalmaskDelayMs:Int=0,val muxEnabled:Boolean=false,val muxConcurrency:Int=8,val iranDirect:Boolean=true,
 val domainStrategy:String="UseIPv4",val routingDomainStrategy:String="AsIs",val ipv4Only:Boolean=true,val keepAliveIdleSeconds:Int=11,val keepAliveIntervalSeconds:Int=15,val blockUdp443:Boolean=false,
 val socksAddress:String="127.0.0.1",val socksPort:Int=10808,val socksUdp:Boolean=true,val tunMtu:Int=1280,val tunAddress:String="198.18.0.1",val nativeDns:String="1.1.1.1",val dnsResolverUrl:String="https://cloudflare-dns.com/dns-query",val tunRoute:String="0.0.0.0/0",
 val dnsServeStale:Boolean=true,val tcpFastOpen:Boolean=true,val bigBuffers:Boolean=true,val prewarm:Boolean=true
) {
 fun validated()=copy(connectionMode=choice(connectionMode,CONNECTION_MODE_TUNNEL,setOf(CONNECTION_MODE_TUNNEL,CONNECTION_MODE_PROXY)),primaryAddress=t(primaryAddress,"104.26.13.100"),primaryPort=p(primaryPort),primaryMaxSplit=sp(primaryMaxSplit),irancellAddress=t(irancellAddress,"104.26.14.40"),irancellPort=p(irancellPort),irancellMaxSplit=sp(irancellMaxSplit),fallbackAddress=t(fallbackAddress,"104.26.14.200"),fallbackPort=p(fallbackPort),fallbackMaxSplit=sp(fallbackMaxSplit),telegramAddress=t(telegramAddress,"104.18.9.83"),telegramFallbackAddress=t(telegramFallbackAddress,"104.18.8.83"),telegramPort=p(telegramPort),telegramMaxSplit=sp(telegramMaxSplit),telegramFingerprint=t(telegramFingerprint,"unsafe"),telegramCipherSuites=t(telegramCipherSuites,PATTNG_PYTHON_CIPHER_SUITES).replace(',',':'),outboundProtocol=choice(outboundProtocol,"vless",setOf("trojan","vless")),transportNetwork=choice(transportNetwork,"ws",setOf("ws")),transportSecurity=choice(transportSecurity,"tls",setOf("tls")),trojanPassword=t(trojanPassword,"4e4f1f70-5e56-4a76-bb9d-db087f4690c4"),tlsSni=t(tlsSni,"jp3.yohototo.top"),tlsAlpn=t(tlsAlpn,"http/1.1"),tlsFingerprint=t(tlsFingerprint,"unsafe"),wsHost=t(wsHost,"jp3.yohototo.top"),wsPath=t(wsPath,"/tyxyws").let{if(it.startsWith('/'))it else "/$it"},finalmaskPacket=t(finalmaskPacket,"tlshello"),finalmaskLength=finalmaskLength.coerceIn(1,65535),finalmaskDelayMs=finalmaskDelayMs.coerceIn(0,60000),muxConcurrency=muxConcurrency.coerceIn(1,1024),keepAliveIdleSeconds=keepAliveIdleSeconds.coerceIn(1,7200),keepAliveIntervalSeconds=keepAliveIntervalSeconds.coerceIn(1,600),socksAddress=t(socksAddress,"127.0.0.1"),socksPort=p(socksPort),tunMtu=tunMtu.coerceIn(576,9000),tunAddress=t(tunAddress,"198.18.0.1"),nativeDns=t(nativeDns,"1.1.1.1"),dnsResolverUrl=doh(dnsResolverUrl),tunRoute=route(tunRoute))
 fun edges()=listOf(MciEdge(primaryAddress,primaryPort,"primary",primaryMaxSplit),MciEdge(irancellAddress,irancellPort,"irancell",irancellMaxSplit),MciEdge(fallbackAddress,fallbackPort,"fallback",fallbackMaxSplit))
 private fun t(v:String,d:String)=v.trim().take(512).ifBlank{d}
 private fun choice(v:String,d:String,allowed:Set<String>)=v.trim().lowercase().takeIf { it in allowed }?:d
 private fun route(v:String):String { val parts=v.trim().split('/',limit=2); val octets=parts.firstOrNull()?.split('.')?.mapNotNull{it.toIntOrNull()}.orEmpty(); val prefix=parts.getOrNull(1)?.toIntOrNull(); return if(octets.size==4&&octets.all{it in 0..255}&&prefix in 0..32) "${octets.joinToString(".")}/$prefix" else "0.0.0.0/0" }
 private fun doh(v:String)=v.trim().take(512).takeIf{it.startsWith("https://")&&it.contains("/dns-query")}?:"https://cloudflare-dns.com/dns-query"
 private fun p(v:Int)=v.coerceIn(1,65535);private fun sp(v:Int)=v.coerceIn(1,10000)
 companion object { val DEFAULT=AdvancedSettingsData() }
}

data class AdvancedSettings(val server:String="104.26.13.100",val port:String="443",val protocol:String="vless",val password:String="4e4f1f70-5e56-4a76-bb9d-db087f4690c4",val transport:String="ws",val security:String="tls",val sni:String="jp3.yohototo.top",val wsHost:String="jp3.yohototo.top",val wsPath:String="/tyxyws",val alpn:String="http/1.1",val fingerprint:String="unsafe",val packets:String="tlshello",val fragmentLength:String="5",val fragmentDelay:String="0",val maxSplit:String="2",val domainStrategy:String="UseIPv4",val keepAliveIdle:String="11",val keepAliveInterval:String="15",val mtu:String="1280",val dns:String="1.1.1.1",val route:String="0.0.0.0/0",val mux:Boolean=false,val blockUdp443:Boolean=false,val socksUdp:Boolean=true)

class AdvancedSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    init {
        if (!prefs.getBoolean(STREAMING_MTU1400_MIGRATION, false)) {
            prefs.edit()
                .putBoolean("telegramRouteEnabled", false)
                .putBoolean("telegramSessionResumption", false)
                .putBoolean("blockUdp443", true)
                .putInt("tunMtu", 1400)
                .putBoolean(STREAMING_MTU1400_MIGRATION, true)
                .apply()
        }
        if (!prefs.getBoolean(TUN_MTU1280_MIGRATION, false)) {
            val currentMtu = prefs.getInt("tunMtu", 1400)
            val editor = prefs.edit().putBoolean(TUN_MTU1280_MIGRATION, true)
            if (currentMtu == 1400) editor.putInt("tunMtu", 1280)
            editor.apply()
        }
        if (!prefs.getBoolean(FINALMASK_DELAY0_MIGRATION, false)) {
            val currentDelay = prefs.getInt("finalmaskDelayMs", 20)
            val editor = prefs.edit().putBoolean(FINALMASK_DELAY0_MIGRATION, true)
            if (currentDelay == 20) editor.putInt("finalmaskDelayMs", 0)
            editor.apply()
        }
        if (!prefs.getBoolean(BLOCK_UDP443_DISABLED_MIGRATION, false)) {
            prefs.edit()
                .putBoolean("blockUdp443", false)
                .putBoolean(BLOCK_UDP443_DISABLED_MIGRATION, true)
                .apply()
        }
        if (!prefs.getBoolean(SNI1_IRAN_HELLO_MIGRATION, false)) {
            val editor = prefs.edit().putBoolean(SNI1_IRAN_HELLO_MIGRATION, true)
            val sni = prefs.getString("tlsSni", MciConfig.TLS_SERVER_NAME)
            if (sni.equals(MciConfig.TLS_SERVER_NAME, ignoreCase = true)) {
                if (prefs.getString("tlsFingerprint", "chrome") == "chrome") {
                    editor.putString("tlsFingerprint", MciConfig.TLS_FINGERPRINT)
                }
                if (prefs.getString("primaryAddress", "104.18.1.1") == "104.18.1.1") {
                    editor.putString("primaryAddress", MciConfig.PRIMARY_EDGE.address)
                }
                if (prefs.getString("irancellAddress", "104.18.1.1") == "104.18.1.1") {
                    editor.putString("irancellAddress", "104.26.14.85")
                }
            }
            editor.apply()
        }
        if (!prefs.getBoolean(BUILTIN_FRONT_MIGRATION, false)) {
            val editor = prefs.edit().putBoolean(BUILTIN_FRONT_MIGRATION, true)
            val sni = prefs.getString("tlsSni", "")
            val password = prefs.getString("trojanPassword", "")
            val deadFront = sni.equals("www.ignitelimit.com", ignoreCase = true) ||
                password == "humanity"
            if (deadFront) {
                val fresh = AdvancedSettingsData.DEFAULT
                editor
                    .putString("outboundProtocol", fresh.outboundProtocol)
                    .putString("trojanPassword", fresh.trojanPassword)
                    .putString("tlsSni", fresh.tlsSni)
                    .putString("wsHost", fresh.wsHost)
                    .putString("wsPath", fresh.wsPath)
                    .putString("tlsAlpn", fresh.tlsAlpn)
                    .putString("tlsFingerprint", fresh.tlsFingerprint)
                if (prefs.getString("primaryAddress", "") == "188.114.97.6") {
                    editor.putString("primaryAddress", fresh.primaryAddress)
                }
                if (prefs.getString("irancellAddress", "") == "104.26.14.85") {
                    editor.putString("irancellAddress", fresh.irancellAddress)
                }
                if (prefs.getString("fallbackAddress", "") == "172.66.0.1") {
                    editor.putString("fallbackAddress", fresh.fallbackAddress)
                }
            }
            editor.apply()
        }
    }
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<AdvancedSettingsData> = mutable

    fun snapshot(): AdvancedSettingsData = read().copy(iranDirect = NetworkGuardStore.get(appContext).snapshot().iranDirect).also { mutable.value = it }
    fun update(block: (AdvancedSettingsData) -> AdvancedSettingsData) = save(block(snapshot()))
    fun resetDefaults(): AdvancedSettingsData = save(AdvancedSettingsData.DEFAULT)

    fun save(value: AdvancedSettingsData): AdvancedSettingsData {
        val v = value.validated()
        prefs.edit()
            .putString("connectionMode", v.connectionMode)
            .putString("primaryAddress", v.primaryAddress).putInt("primaryPort", v.primaryPort).putInt("primaryMaxSplit", v.primaryMaxSplit)
            .putString("irancellAddress", v.irancellAddress).putInt("irancellPort", v.irancellPort).putInt("irancellMaxSplit", v.irancellMaxSplit)
            .putString("fallbackAddress", v.fallbackAddress).putInt("fallbackPort", v.fallbackPort).putInt("fallbackMaxSplit", v.fallbackMaxSplit)
            .putBoolean("telegramRouteEnabled", v.telegramRouteEnabled).putString("telegramAddress", v.telegramAddress).putString("telegramFallbackAddress", v.telegramFallbackAddress).putInt("telegramPort", v.telegramPort).putInt("telegramMaxSplit", v.telegramMaxSplit).putString("telegramFingerprint", v.telegramFingerprint).putString("telegramCipherSuites", v.telegramCipherSuites).putBoolean("telegramSessionResumption", v.telegramSessionResumption)
            .putBoolean(TELEGRAM_EDGE9_MIGRATION, true)
            .putString("outboundProtocol", v.outboundProtocol).putString("transportNetwork", v.transportNetwork).putString("transportSecurity", v.transportSecurity)
            .putString("trojanPassword", v.trojanPassword).putString("tlsSni", v.tlsSni).putString("tlsAlpn", v.tlsAlpn).putString("tlsFingerprint", v.tlsFingerprint)
            .putString("wsHost", v.wsHost).putString("wsPath", v.wsPath).putString("finalmaskPacket", v.finalmaskPacket)
            .putInt("finalmaskLength", v.finalmaskLength).putInt("finalmaskDelayMs", v.finalmaskDelayMs)
            .putBoolean("muxEnabled", v.muxEnabled).putBoolean("iranDirect", v.iranDirect).putInt("muxConcurrency", v.muxConcurrency)
            .putString("domainStrategy", v.domainStrategy).putString("routingDomainStrategy", v.routingDomainStrategy).putBoolean("ipv4Only", v.ipv4Only)
            .putInt("keepAliveIdleSeconds", v.keepAliveIdleSeconds).putInt("keepAliveIntervalSeconds", v.keepAliveIntervalSeconds).putBoolean("blockUdp443", v.blockUdp443)
            .putString("socksAddress", v.socksAddress).putInt("socksPort", v.socksPort).putBoolean("socksUdp", v.socksUdp)
            .putInt("tunMtu", v.tunMtu).putString("tunAddress", v.tunAddress).putString("nativeDns", v.nativeDns).putString("dnsResolverUrl",v.dnsResolverUrl).putString("tunRoute", v.tunRoute)
            .putBoolean("dnsServeStale", v.dnsServeStale).putBoolean("tcpFastOpen", v.tcpFastOpen).putBoolean("bigBuffers", v.bigBuffers).putBoolean("prewarm", v.prewarm)
            .apply()
        NetworkGuardStore.get(appContext).setIranDirect(v.iranDirect)
        mutable.value = v
        return v
    }

    private fun read(): AdvancedSettingsData {
        val d = AdvancedSettingsData.DEFAULT
        fun s(k: String, default: String) = prefs.getString(k, default) ?: default
        fun i(k: String, default: Int) = prefs.getInt(k, default)
        fun b(k: String, default: Boolean) = prefs.getBoolean(k, default)
        var telegramAddress = s("telegramAddress", d.telegramAddress)
        var telegramFallbackAddress = s("telegramFallbackAddress", d.telegramFallbackAddress)
        if (!prefs.getBoolean(TELEGRAM_EDGE9_MIGRATION, false)) {
            if (telegramAddress == "104.18.8.83" && telegramFallbackAddress == "104.18.9.83") {
                telegramAddress = "104.18.9.83"
                telegramFallbackAddress = "104.18.8.83"
            }
            prefs.edit()
                .putString("telegramAddress", telegramAddress)
                .putString("telegramFallbackAddress", telegramFallbackAddress)
                .putBoolean(TELEGRAM_EDGE9_MIGRATION, true)
                .apply()
        }
        return AdvancedSettingsData(
            connectionMode=s("connectionMode",d.connectionMode),
            primaryAddress=s("primaryAddress",d.primaryAddress), primaryPort=i("primaryPort",d.primaryPort), primaryMaxSplit=i("primaryMaxSplit",d.primaryMaxSplit),
            irancellAddress=s("irancellAddress",d.irancellAddress), irancellPort=i("irancellPort",d.irancellPort), irancellMaxSplit=i("irancellMaxSplit",d.irancellMaxSplit),
            fallbackAddress=s("fallbackAddress",d.fallbackAddress), fallbackPort=i("fallbackPort",d.fallbackPort), fallbackMaxSplit=i("fallbackMaxSplit",d.fallbackMaxSplit),
            telegramRouteEnabled=b("telegramRouteEnabled",d.telegramRouteEnabled), telegramAddress=telegramAddress, telegramFallbackAddress=telegramFallbackAddress, telegramPort=i("telegramPort",d.telegramPort), telegramMaxSplit=i("telegramMaxSplit",d.telegramMaxSplit), telegramFingerprint=s("telegramFingerprint",d.telegramFingerprint), telegramCipherSuites=s("telegramCipherSuites",d.telegramCipherSuites), telegramSessionResumption=b("telegramSessionResumption",d.telegramSessionResumption),
            outboundProtocol=s("outboundProtocol",d.outboundProtocol), transportNetwork=s("transportNetwork",d.transportNetwork), transportSecurity=s("transportSecurity",d.transportSecurity),
            trojanPassword=s("trojanPassword",d.trojanPassword), tlsSni=s("tlsSni",d.tlsSni), tlsAlpn=s("tlsAlpn",d.tlsAlpn), tlsFingerprint=s("tlsFingerprint",d.tlsFingerprint),
            wsHost=s("wsHost",d.wsHost), wsPath=s("wsPath",d.wsPath), finalmaskPacket=s("finalmaskPacket",d.finalmaskPacket), finalmaskLength=i("finalmaskLength",d.finalmaskLength), finalmaskDelayMs=i("finalmaskDelayMs",d.finalmaskDelayMs),
            muxEnabled=b("muxEnabled",d.muxEnabled), iranDirect=b("iranDirect",d.iranDirect), muxConcurrency=i("muxConcurrency",d.muxConcurrency), domainStrategy=s("domainStrategy",d.domainStrategy), routingDomainStrategy=s("routingDomainStrategy",d.routingDomainStrategy), ipv4Only=b("ipv4Only",d.ipv4Only),
            keepAliveIdleSeconds=i("keepAliveIdleSeconds",d.keepAliveIdleSeconds), keepAliveIntervalSeconds=i("keepAliveIntervalSeconds",d.keepAliveIntervalSeconds), blockUdp443=b("blockUdp443",d.blockUdp443),
            socksAddress=s("socksAddress",d.socksAddress), socksPort=i("socksPort",d.socksPort), socksUdp=b("socksUdp",d.socksUdp), tunMtu=i("tunMtu",d.tunMtu), tunAddress=s("tunAddress",d.tunAddress), nativeDns=s("nativeDns",d.nativeDns), dnsResolverUrl=s("dnsResolverUrl",d.dnsResolverUrl), tunRoute=s("tunRoute",d.tunRoute),
            dnsServeStale=b("dnsServeStale",d.dnsServeStale), tcpFastOpen=b("tcpFastOpen",d.tcpFastOpen), bigBuffers=b("bigBuffers",d.bigBuffers), prewarm=b("prewarm",d.prewarm)
        ).validated()
    }

    companion object {
        private const val PREF="advanced_runtime_settings"
        private const val TELEGRAM_EDGE9_MIGRATION="telegram_edge9_primary_v1"
        private const val STREAMING_MTU1400_MIGRATION="streaming_mtu1400_no_telegram_route_tcp443_v2"
        private const val TUN_MTU1280_MIGRATION="tun_mtu_1280_v1"
        private const val FINALMASK_DELAY0_MIGRATION="finalmask_delay_0_v1"
        private const val BLOCK_UDP443_DISABLED_MIGRATION="block_udp443_disabled_v1"
        private const val SNI1_IRAN_HELLO_MIGRATION="sni1_iran_hello_split_v1"
        private const val BUILTIN_FRONT_MIGRATION="builtin_front_yohototo_v1"
        fun load(c:Context)=from(AdvancedSettingsStore(c).snapshot())
        fun reset(c:Context)=from(AdvancedSettingsStore(c).resetDefaults())
        fun save(c:Context,v:AdvancedSettings) { val st=AdvancedSettingsStore(c); val d=st.snapshot(); st.save(d.copy(primaryAddress=v.server,primaryPort=v.port.toIntOrNull()?:d.primaryPort,outboundProtocol=v.protocol,transportNetwork=v.transport,transportSecurity=v.security,trojanPassword=v.password,tlsSni=v.sni,tlsAlpn=v.alpn,tlsFingerprint=v.fingerprint,wsHost=v.wsHost,wsPath=v.wsPath,finalmaskPacket=v.packets,finalmaskLength=v.fragmentLength.toIntOrNull()?:d.finalmaskLength,finalmaskDelayMs=v.fragmentDelay.toIntOrNull()?:d.finalmaskDelayMs,primaryMaxSplit=v.maxSplit.toIntOrNull()?:d.primaryMaxSplit,domainStrategy=v.domainStrategy,keepAliveIdleSeconds=v.keepAliveIdle.toIntOrNull()?:d.keepAliveIdleSeconds,keepAliveIntervalSeconds=v.keepAliveInterval.toIntOrNull()?:d.keepAliveIntervalSeconds,tunMtu=v.mtu.toIntOrNull()?:d.tunMtu,nativeDns=v.dns,tunRoute=v.route,muxEnabled=v.mux,blockUdp443=v.blockUdp443,socksUdp=v.socksUdp)) }
        private fun from(d:AdvancedSettingsData)=AdvancedSettings(server=d.primaryAddress,port=d.primaryPort.toString(),protocol=d.outboundProtocol,transport=d.transportNetwork,security=d.transportSecurity,password=d.trojanPassword,sni=d.tlsSni,wsHost=d.wsHost,wsPath=d.wsPath,alpn=d.tlsAlpn,fingerprint=d.tlsFingerprint,packets=d.finalmaskPacket,fragmentLength=d.finalmaskLength.toString(),fragmentDelay=d.finalmaskDelayMs.toString(),maxSplit=d.primaryMaxSplit.toString(),domainStrategy=d.domainStrategy,keepAliveIdle=d.keepAliveIdleSeconds.toString(),keepAliveInterval=d.keepAliveIntervalSeconds.toString(),mtu=d.tunMtu.toString(),dns=d.nativeDns,route=d.tunRoute,mux=d.muxEnabled,blockUdp443=d.blockUdp443,socksUdp=d.socksUdp)
    }
}

