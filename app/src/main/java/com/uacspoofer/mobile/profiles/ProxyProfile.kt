package com.uacspoofer.mobile.profiles

import com.uacspoofer.mobile.settings.AdvancedSettingsData

enum class ProxyProtocol(val wireName: String) {
    TROJAN("trojan"),
    VLESS("vless"),
    VMESS("vmess"),
}


data class ProxyProfile(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val credential: String,
    val serverHost: String,
    val serverPort: Int,
    val network: String,
    val security: String,
    val sni: String,
    val host: String,
    val path: String,
    val alpn: String,
    val fingerprint: String,
    val allowInsecure: Boolean = false,
    val flow: String = "",
    val encryption: String = "none",
    val alterId: Int = 0,
    val serviceName: String = "",
    val authority: String = "",
    val xhttpMode: String = "",
    val xhttpExtra: String = "",
    val packetEncoding: String = "",
    val country: CountryMetadata = CountryMetadata.UNKNOWN,
    val rawUri: String = "",
    val isBuiltIn: Boolean = false,
) {
    fun usesAdvancedSettingsIdentity(): Boolean = isBuiltIn && id == BUILT_IN_ID

    fun runtimeIdentity(settings: AdvancedSettingsData): RuntimeProxyIdentity =
        if (usesAdvancedSettingsIdentity()) {
            RuntimeProxyIdentity(
                protocol = ProxyProtocol.TROJAN,
                credential = settings.trojanPassword,
                network = settings.transportNetwork,
                security = settings.transportSecurity,
                sni = settings.tlsSni,
                host = settings.wsHost,
                path = settings.wsPath,
                alpn = TlsAlpnResolver.canonicalString(settings.tlsAlpn, settings.transportNetwork),
                fingerprint = settings.tlsFingerprint,
                allowInsecure = false,
                flow = "",
                encryption = "none",
                alterId = 0,
                serviceName = "",
                authority = "",
                xhttpMode = "",
                xhttpExtra = "",
                packetEncoding = "",
            )
        } else {
            RuntimeProxyIdentity(
                protocol = protocol,
                credential = credential,
                network = network,
                security = security,
                sni = sni,
                host = host,
                path = path,
                alpn = TlsAlpnResolver.canonicalString(alpn, network),
                fingerprint = fingerprint,
                allowInsecure = allowInsecure,
                flow = flow,
                encryption = encryption,
                alterId = alterId,
                serviceName = serviceName,
                authority = authority,
                xhttpMode = xhttpMode,
                xhttpExtra = xhttpExtra,
                packetEncoding = packetEncoding,
            )
        }

    companion object {
        const val BUILT_IN_ID = "builtin:mci"
        const val BUILT_IN_2_ID = "builtin:mci2"

        val UAC_SNI_BUILT_IN = ProxyProfile(
            id = BUILT_IN_ID,
            name = "UAC SNI built-in",
            protocol = ProxyProtocol.TROJAN,
            credential = "humanity",
            serverHost = "www.ignitelimit.com",
            serverPort = 443,
            network = "ws",
            security = "tls",
            sni = "www.ignitelimit.com",
            host = "www.ignitelimit.com",
            path = "/assignment",
            alpn = "http/1.1",
            fingerprint = "chrome",
            country = CountryMetadata.resolve("FR", "France"),
            isBuiltIn = true,
        )

        val UAC_SNI_BUILT_IN_2 = ProxyProfile(
            id = BUILT_IN_2_ID,
            name = "UAC SNI built-in 2",
            protocol = ProxyProtocol.TROJAN,
            credential = "humanity",
            serverHost = "127.0.0.1",
            serverPort = 40443,
            network = "ws",
            security = "tls",
            sni = "api-ir.behroozuac.dpdns.org",
            host = "api-ir.behroozuac.dpdns.org",
            path = "/assignment",
            alpn = "http/1.1",
            fingerprint = "chrome",
            country = CountryMetadata.resolve("NL", "Netherlands"),
            rawUri = "trojan://humanity@127.0.0.1:40443?type=ws&security=tls&sni=api-ir.behroozuac.dpdns.org&host=api-ir.behroozuac.dpdns.org&path=%2Fassignment&alpn=http%2F1.1&fp=chrome#humanity-user",
            isBuiltIn = true,
        )

        val BUILT_IN_PROFILES = listOf(UAC_SNI_BUILT_IN, UAC_SNI_BUILT_IN_2)

        fun isProtectedBuiltIn(id: String): Boolean = id == BUILT_IN_ID || id == BUILT_IN_2_ID
    }
}

data class RuntimeProxyIdentity(
    val protocol: ProxyProtocol,
    val credential: String,
    val network: String,
    val security: String,
    val sni: String,
    val host: String,
    val path: String,
    val alpn: String,
    val fingerprint: String,
    val allowInsecure: Boolean,
    val flow: String,
    val encryption: String,
    val alterId: Int,
    val serviceName: String,
    val authority: String,
    val xhttpMode: String = "",
    val xhttpExtra: String = "",
    val packetEncoding: String = "",
)

data class ProfileLibrary(
    val customProfiles: List<ProxyProfile>,
    val selectedId: String,
) {
    val allProfiles: List<ProxyProfile> get() = ProxyProfile.BUILT_IN_PROFILES + customProfiles
    val selectedProfile: ProxyProfile
        get() = allProfiles.firstOrNull { it.id == selectedId } ?: ProxyProfile.UAC_SNI_BUILT_IN
}
