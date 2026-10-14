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
    /** TCP header obfuscation, `http` or blank. Only meaningful when [network] is `tcp`. */
    val headerType: String = "",
    /** TLS ECH config list: base64 key or `domain+udp://resolver` query form. */
    val ech: String = "",
    /** Per-link TLS cipher suite list (`cs`), colon separated. */
    val cipherSuites: String = "",
    /** Per-link Xray finalmask JSON object (`fm`), compacted. */
    val finalmask: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
    val country: CountryMetadata = CountryMetadata.UNKNOWN,
    val rawUri: String = "",
    val isBuiltIn: Boolean = false,
) {
    fun usesAdvancedSettingsIdentity(): Boolean = isBuiltIn && id == BUILT_IN_ID

    /** The Auto (Patterniha) entry: not a real link, resolved to a tested config at connect. */
    val isAuto: Boolean get() = id == AUTO_ID

    fun runtimeIdentity(settings: AdvancedSettingsData): RuntimeProxyIdentity =
        if (usesAdvancedSettingsIdentity()) {
            RuntimeProxyIdentity(
                protocol = if (settings.outboundProtocol.equals("vless", ignoreCase = true)) {
                    ProxyProtocol.VLESS
                } else {
                    ProxyProtocol.TROJAN
                },
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
                headerType = "",
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
                headerType = headerType,
                ech = ech,
                cipherSuites = cipherSuites,
                finalmask = finalmask,
                realityPublicKey = realityPublicKey,
                realityShortId = realityShortId,
                realitySpiderX = realitySpiderX,
            )
        }

    companion object {
        /**
         * Former built-in 1. No longer selectable (build 443); the id only backs
         * [ADVANCED_SETTINGS_TEMPLATE], the identity the Advanced Xray settings and tests build on.
         */
        const val BUILT_IN_ID = "builtin:mci"
        const val AUTO_ID = "auto:patterniha"
        /** Ids of the concrete configs Auto connects with (never stored in the profile list). */
        const val AUTO_RUN_PREFIX = "auto-run:"
        /** Removed in build 442 (dead server); kept only to migrate saved selections. */
        const val LEGACY_BUILT_IN_2_ID = "builtin:mci2"

        /** Not user-selectable; see [BUILT_IN_ID]. */
        val ADVANCED_SETTINGS_TEMPLATE = ProxyProfile(
            id = BUILT_IN_ID,
            name = "Advanced settings identity",
            protocol = ProxyProtocol.VLESS,
            credential = "4e4f1f70-5e56-4a76-bb9d-db087f4690c4",
            serverHost = "jp3.yohototo.top",
            serverPort = 443,
            network = "ws",
            security = "tls",
            sni = "jp3.yohototo.top",
            host = "jp3.yohototo.top",
            path = "/tyxyws",
            alpn = "http/1.1",
            fingerprint = "unsafe",
            country = CountryMetadata.resolve("FR", "France"),
            isBuiltIn = true,
        )

        val AUTO = ProxyProfile(
            id = AUTO_ID,
            name = "Auto · خودکار",
            protocol = ProxyProtocol.VLESS,
            credential = "",
            serverHost = "auto",
            serverPort = 443,
            network = "ws",
            security = "tls",
            sni = "",
            host = "",
            path = "/",
            alpn = "",
            fingerprint = "chrome",
            isBuiltIn = true,
        )

        val BUILT_IN_PROFILES = listOf(AUTO)

        fun isProtectedBuiltIn(id: String): Boolean = id == AUTO_ID

        /** Both removed built-ins (and anything Auto picked) map onto Auto. */
        fun migrateSelectedId(id: String): String = when {
            id == BUILT_IN_ID || id == LEGACY_BUILT_IN_2_ID -> AUTO_ID
            id.startsWith(AUTO_RUN_PREFIX) -> AUTO_ID
            else -> id
        }
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
    val headerType: String = "",
    val ech: String = "",
    val cipherSuites: String = "",
    val finalmask: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
) {
    val usesTls: Boolean get() = security == "tls"
    val usesReality: Boolean get() = security == "reality"
    val usesEch: Boolean get() = usesTls && ech.isNotBlank()

    /** ECH and REALITY must dial the link's own address; clean-IP swaps break them. */
    val pinnedToOwnAddress: Boolean get() = usesEch || usesReality
}

data class ProfileLibrary(
    val customProfiles: List<ProxyProfile>,
    val selectedId: String,
) {
    val allProfiles: List<ProxyProfile> get() = ProxyProfile.BUILT_IN_PROFILES + customProfiles
    val selectedProfile: ProxyProfile
        get() = allProfiles.firstOrNull { it.id == ProxyProfile.migrateSelectedId(selectedId) }
            ?: ProxyProfile.AUTO
}
