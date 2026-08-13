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
        if (profile.isBuiltIn || profile.rawUri.isBlank()) return null
        return runCatching {
            if (profile.rawUri.startsWith("vmess://", ignoreCase = true)) {
                parseVmess(profile.rawUri)
            } else {
                parseUri(profile.rawUri)
            }
        }.getOrNull()?.takeUnless { direct ->
            direct.address.equals("127.0.0.1", ignoreCase = true) ||
                direct.address.equals("localhost", ignoreCase = true) ||
                direct.port !in 1..65_535
        }
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
        val network = (query["type"] ?: query["network"] ?: "tcp").lowercase()
        require(network in SUPPORTED_NETWORKS) { "Direct transport $network is unsupported" }
        val security = (query["security"] ?: "tls").lowercase()
        require(security == "tls") { "Direct compatibility currently requires TLS" }
        require(query["headertype"].orEmpty().let { it.isBlank() || it.equals("none", true) }) {
            "Direct TCP header type is unsupported"
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
            ),
        )
    }

    private fun parseVmess(raw: String): DirectCompatProfile {
        val encoded = raw.substringAfter("://").substringBefore('#').trim()
        val json = JSONObject(Base64Codec.decode(encoded).toString(Charsets.UTF_8))
        val address = json.optString("add").trim()
        require(address.isNotBlank()) { "Direct VMess address is missing" }
        val port = json.optString("port", "443").toIntOrNull() ?: json.optInt("port", 443)
        val network = json.optString("net", "tcp").lowercase()
        require(network in SUPPORTED_NETWORKS) { "Direct VMess transport $network is unsupported" }
        val security = json.optString("tls", "tls").lowercase().ifBlank { "tls" }
        require(security == "tls") { "Direct VMess compatibility currently requires TLS" }
        require(json.optString("type").let { it.isBlank() || it.equals("none", true) }) {
            "Direct VMess TCP header type is unsupported"
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

    private val SUPPORTED_NETWORKS = setOf("ws", "tcp", "httpupgrade", "grpc")
    private val TRUE_VALUES = setOf("1", "true", "yes", "on")
}
