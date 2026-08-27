package com.uacspoofer.mobile.profiles

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class DirectCompatProfile(
    val address: String,
    val port: Int,
    val identity: RuntimeProxyIdentity,
)

object DirectCompatProfileParser {
    fun parse(profile: ProxyProfile): DirectCompatProfile? {
        if (profile.usesAdvancedSettingsIdentity() || profile.rawUri.isBlank()) return null
        return parseUnfiltered(profile)?.takeUnless { direct ->
            direct.address.equals(LocalForwardProfile.HOST, ignoreCase = true) ||
                direct.address.equals("localhost", ignoreCase = true) ||
                direct.port !in 1..65_535
        }
    }

    fun parseIdentity(profile: ProxyProfile): RuntimeProxyIdentity? =
        parseUnfiltered(profile)?.identity

    internal fun parseRaw(profile: ProxyProfile): DirectCompatProfile? = parseUnfiltered(profile)

    private fun parseUnfiltered(profile: ProxyProfile): DirectCompatProfile? {
        if (profile.rawUri.isBlank()) return null
        return runCatching {
            if (profile.rawUri.startsWith("vmess://", ignoreCase = true)) {
                parseVmess(profile.rawUri)
            } else {
                parseUri(profile.rawUri)
            }
        }.getOrNull()
    }

    private fun parseUri(raw: String): DirectCompatProfile {
        val uri = URI(raw.trim().replace("&amp;", "&", ignoreCase = true))
        val protocol = when (uri.scheme?.lowercase()) {
            "vless" -> ProxyProtocol.VLESS
            "trojan" -> ProxyProtocol.TROJAN
            else -> error("Direct compatibility supports VLESS, Trojan and VMess only")
        }
        val address = uri.host?.removePrefix("[")?.removeSuffix("]").orEmpty()
        require(address.isNotBlank()) { "Direct profile address is missing" }
        val port = uri.port.takeIf { it in 1..65_535 } ?: 443
        val query = parseQuery(uri.rawQuery)
        val network = ProfileNetworks.requireSupported(query["type"] ?: query["network"] ?: "tcp", "Direct transport")
        val security = (query["security"] ?: "tls").lowercase()
        require(security == "tls") { "Direct compatibility currently requires TLS" }
        val headerType = query["headertype"].orEmpty()
        if (!ProfileNetworks.isXhttp(network)) {
            require(headerType.isBlank() || headerType.equals("none", true)) {
                "Direct TCP header type is unsupported"
            }
        }
        val credential = decode(uri.rawUserInfo.orEmpty()).trim()
        require(credential.isNotBlank()) { "Direct profile credential is missing" }
        val sni = query["sni"].orEmpty().ifBlank { query["servername"].orEmpty() }
        val serviceName = query["servicename"].orEmpty()
            .ifBlank { if (network == "grpc") query["path"].orEmpty().removePrefix("/") else "" }
        if (network == "grpc") require(serviceName.isNotBlank()) { "Direct gRPC serviceName is missing" }
        val encryption = if (protocol == ProxyProtocol.VLESS) {
            query["encryption"].orEmpty().ifBlank { "none" }
        } else {
            "none"
        }
        return DirectCompatProfile(
            address = address,
            port = port,
            identity = RuntimeProxyIdentity(
                protocol = protocol,
                credential = credential,
                network = network,
                security = security,
                sni = sni,
                host = query["host"].orEmpty(),
                path = query["path"].orEmpty(),
                alpn = query["alpn"].orEmpty(),
                fingerprint = query["fp"].orEmpty().ifBlank { query["fingerprint"].orEmpty() },
                allowInsecure = boolean(query["allowinsecure"] ?: query["insecure"]),
                flow = query["flow"].orEmpty(),
                encryption = encryption,
                alterId = 0,
                serviceName = serviceName,
                authority = query["authority"].orEmpty(),
                xhttpMode = if (ProfileNetworks.isXhttp(network)) {
                    ProfileNetworks.normalizeMode(query["mode"].orEmpty())
                        .ifBlank { ProfileNetworks.optionalMode(headerType) }
                } else {
                    ""
                },
                xhttpExtra = if (ProfileNetworks.isXhttp(network)) {
                    ProfileNetworks.normalizeExtra(query["extra"].orEmpty())
                } else {
                    ""
                },
                packetEncoding = ProfileNetworks.normalizePacketEncoding(query["packetencoding"].orEmpty()),
            ),
        )
    }

    private fun parseVmess(raw: String): DirectCompatProfile {
        val encoded = raw.substringAfter("://").substringBefore('#').trim()
        val json = JSONObject(Base64Codec.decode(encoded).toString(Charsets.UTF_8))
        val address = json.optString("add").trim()
        require(address.isNotBlank()) { "Direct VMess address is missing" }
        val port = json.optString("port", "443").toIntOrNull() ?: json.optInt("port", 443)
        val network = ProfileNetworks.requireSupported(json.optString("net", "tcp"), "Direct VMess transport")
        val security = json.optString("tls", "tls").lowercase().ifBlank { "tls" }
        require(security == "tls") { "Direct VMess compatibility currently requires TLS" }
        val headerType = json.optString("type")
        if (!ProfileNetworks.isXhttp(network)) {
            require(headerType.isBlank() || headerType.equals("none", true)) {
                "Direct VMess TCP header type is unsupported"
            }
        }
        val serviceName = if (network == "grpc") {
            json.optString("serviceName").ifBlank { json.optString("path").removePrefix("/") }
        } else {
            ""
        }
        if (network == "grpc") require(serviceName.isNotBlank()) { "Direct VMess gRPC serviceName is missing" }
        return DirectCompatProfile(
            address = address,
            port = port,
            identity = RuntimeProxyIdentity(
                protocol = ProxyProtocol.VMESS,
                credential = json.optString("id").trim(),
                network = network,
                security = security,
                sni = json.optString("sni"),
                host = json.optString("host"),
                path = json.optString("path"),
                alpn = json.optString("alpn"),
                fingerprint = json.optString("fp"),
                allowInsecure = jsonBoolean(json, "allowInsecure"),
                flow = "",
                encryption = json.optString("scy", "auto").ifBlank { "auto" },
                alterId = json.optString("aid", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0,
                serviceName = serviceName,
                authority = json.optString("authority"),
                xhttpMode = if (ProfileNetworks.isXhttp(network)) {
                    ProfileNetworks.vmessMode(headerType, json.optString("mode"))
                } else {
                    ""
                },
                xhttpExtra = if (ProfileNetworks.isXhttp(network)) {
                    ProfileNetworks.normalizeExtra(json.optString("extra"))
                } else {
                    ""
                },
                packetEncoding = ProfileNetworks.normalizePacketEncoding(json.optString("packetEncoding")),
            ),
        )
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return buildMap {
            raw.replace("&amp;", "&", ignoreCase = true).split('&').forEach { part ->
                if (part.isBlank()) return@forEach
                val split = part.split('=', limit = 2)
                val key = decode(split[0]).trim().lowercase().removePrefix("amp;")
                put(key, decode(split.getOrElse(1) { "" }))
            }
        }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun boolean(value: String?): Boolean = value?.trim()?.lowercase() in TRUE_VALUES

    private fun jsonBoolean(json: JSONObject, key: String): Boolean = when (val value = json.opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> boolean(value?.toString())
    }

    private val TRUE_VALUES = setOf("1", "true", "yes", "on")
}
