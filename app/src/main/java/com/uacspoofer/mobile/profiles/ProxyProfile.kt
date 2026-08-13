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
    val country: CountryMetadata = CountryMetadata.UNKNOWN,
    val rawUri: String = "",
    val isBuiltIn: Boolean = false,
) {
    fun runtimeIdentity(settings: AdvancedSettingsData): RuntimeProxyIdentity =
        if (isBuiltIn) {
            RuntimeProxyIdentity(
                protocol = ProxyProtocol.TROJAN,
                credential = settings.trojanPassword,
                network = settings.transportNetwork,
                security = settings.transportSecurity,
                sni = settings.tlsSni,
                host = settings.wsHost,
                path = settings.wsPath,
                alpn = settings.tlsAlpn,
                fingerprint = settings.tlsFingerprint,
                allowInsecure = false,
                flow = "",
                encryption = "none",
                alterId = 0,
                serviceName = "",
                authority = "",
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
                alpn = alpn,
                fingerprint = fingerprint,
                allowInsecure = allowInsecure,
                flow = flow,
                encryption = encryption,
                alterId = alterId,
                serviceName = serviceName,
                authority = authority,
            )
        }

    companion object {
        const val BUILT_IN_ID = "builtin:mci"

        val MCI_BUILT_IN = ProxyProfile(
            id = BUILT_IN_ID,
            name = "MCI built-in",
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
)

data class ProfileLibrary(
    val customProfiles: List<ProxyProfile>,
    val selectedId: String,
) {
    val allProfiles: List<ProxyProfile> get() = listOf(ProxyProfile.MCI_BUILT_IN) + customProfiles
    val selectedProfile: ProxyProfile
        get() = allProfiles.firstOrNull { it.id == selectedId } ?: ProxyProfile.MCI_BUILT_IN
}
