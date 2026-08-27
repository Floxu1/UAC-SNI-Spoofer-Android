package com.uacspoofer.mobile.profiles

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

object ProfileUriParser {
    private val recognizedKeys = setOf(
        "type", "network", "security", "sni", "servername", "host", "path", "alpn", "fp", "fingerprint",
        "flow", "encryption", "servicename", "authority", "headertype", "mode", "extra", "packetencoding",
        "allowinsecure", "insecure", "country", "countrycode", "cc", "location",
    )

    fun parse(raw: String, id: String = newId(), nameOverride: String? = null): ProxyProfile {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Configuration URI is empty" }
        if (text.startsWith("vmess://", ignoreCase = true)) {
            return parseVmess(text, id, nameOverride)
        }
        val uri = runCatching { URI(text) }.getOrElse { throw IllegalArgumentException("Malformed configuration URI") }
        val protocol = when (uri.scheme?.lowercase()) {
            "trojan" -> ProxyProtocol.TROJAN
            "vless" -> ProxyProtocol.VLESS
            else -> throw IllegalArgumentException("Only VLESS, Trojan and VMess configurations are supported")
        }
        val credential = decode(uri.rawUserInfo.orEmpty()).trim()
        require(credential.isNotEmpty()) {
            if (protocol == ProxyProtocol.VLESS) "VLESS UUID is missing" else "Trojan password is missing"
        }
        if (protocol == ProxyProtocol.VLESS) {
            require(runCatching { UUID.fromString(credential) }.isSuccess) { "VLESS UUID is invalid" }
        }

        val sourceServerHost = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        require(sourceServerHost.isNotEmpty()) { "Server host is missing or invalid" }
        val sourceServerPort = if (uri.port == -1) 443 else uri.port
        require(sourceServerPort in 1..65535) { "Server port is invalid" }
        val query = parseQuery(uri.rawQuery)
        val unknown = query.keys - recognizedKeys
        require(unknown.isEmpty()) { "Unsupported parameter: ${unknown.first()}" }

        val network = ProfileNetworks.requireSupported(query["type"] ?: query["network"] ?: "ws")
        val security = (query["security"] ?: "tls").lowercase()
        require(security == "tls") { "UAC SNI requires TLS security" }
        val headerType = query["headertype"].orEmpty().lowercase()
        if (!ProfileNetworks.isXhttp(network)) {
            require(headerType.isBlank() || headerType == "none") { "Unsupported TCP header type: $headerType" }
        }

        val encryption = query["encryption"].orEmpty().ifBlank { "none" }
        if (protocol == ProxyProtocol.VLESS) {
            require(encryption.equals("none", ignoreCase = true)) { "Only VLESS encryption=none is supported" }
        }
        val flow = query["flow"].orEmpty()
        val sni = query["sni"].orEmpty()
            .ifBlank { query["servername"].orEmpty() }
            .ifBlank { query["host"].orEmpty() }
            .ifBlank { sourceServerHost }
        val host = query["host"].orEmpty().ifBlank { sni }
        val path = normalizePath(query["path"].orEmpty(), network)
        val alpn = TlsAlpnResolver.canonicalString(query["alpn"].orEmpty(), network)
        val fingerprint = query["fp"].orEmpty().ifBlank { query["fingerprint"].orEmpty() }.ifBlank { "chrome" }
        val allowInsecure = parseBoolean(query["allowinsecure"] ?: query["insecure"])
        val serviceName = query["servicename"].orEmpty()
        val authority = query["authority"].orEmpty()
        val xhttpMode = if (ProfileNetworks.isXhttp(network)) {
            ProfileNetworks.normalizeMode(query["mode"].orEmpty())
                .ifBlank { ProfileNetworks.optionalMode(headerType) }
        } else {
            ""
        }
        val xhttpExtra = if (ProfileNetworks.isXhttp(network)) {
            ProfileNetworks.normalizeExtra(query["extra"].orEmpty())
        } else {
            ""
        }
        val packetEncoding = ProfileNetworks.normalizePacketEncoding(query["packetencoding"].orEmpty())
        val country = CountryMetadata.resolve(
            code = query["countrycode"] ?: query["cc"],
            name = query["country"] ?: query["location"],
        )
        if (network == "grpc") require(serviceName.isNotBlank()) { "gRPC serviceName is missing" }

        val fragmentName = decode(uri.rawFragment.orEmpty()).trim()
        val name = nameOverride?.trim().orEmpty().ifBlank {
            fragmentName.ifBlank { "${protocol.name} • $sourceServerHost" }
        }.take(80)

        return ProxyProfile(
            id = id,
            name = name,
            protocol = protocol,
            credential = credential,
            serverHost = LocalForwardProfile.HOST,
            serverPort = LocalForwardProfile.PORT,
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
            alterId = 0,
            serviceName = serviceName,
            authority = authority,
            xhttpMode = xhttpMode,
            xhttpExtra = xhttpExtra,
            packetEncoding = packetEncoding,
            country = country,
            rawUri = text,
        )
    }

    
    fun parseForSniMaker(raw: String, id: String = newId()): ProxyProfile {
        val clean = raw.trim().replace("&amp;", "&", ignoreCase = true)
        return runCatching { parse(clean, id = id) }.getOrElse {
            if (clean.startsWith("vmess://", ignoreCase = true)) {
                parse(coerceVmessToSni(clean), id = id)
            } else {
                parse(coerceUriToSni(clean), id = id)
            }
        }
    }

    fun extractUris(text: String): List<String> {
        val matcher = Regex("(?i)(?:vless|trojan|vmess)://[^\\s<>\\\"']+")
        return matcher.findAll(text)
            .map { it.value.trim().trimEnd(',', ';', ')', ']', '}') }
            .distinct()
            .toList()
    }

    fun canonicalUri(profile: ProxyProfile): String {
        if (profile.usesAdvancedSettingsIdentity()) return ""
        if (profile.rawUri.isNotBlank()) return profile.rawUri
        if (profile.protocol == ProxyProtocol.VMESS) {
            val payload = JSONObject()
                .put("v", "2")
                .put("ps", profile.name)
                .put("add", profile.serverHost)
                .put("port", profile.serverPort.toString())
                .put("id", profile.credential)
                .put("aid", profile.alterId.toString())
                .put("scy", profile.encryption.ifBlank { "auto" })
                .put("net", profile.network)
                .put("type", if (ProfileNetworks.isXhttp(profile.network)) profile.xhttpMode.ifBlank { "none" } else "none")
                .put("host", profile.host)
                .put("path", profile.path)
                .put("tls", profile.security)
                .put("sni", profile.sni)
                .put("alpn", profile.alpn)
                .put("fp", profile.fingerprint)
                .apply {
                    if (profile.xhttpExtra.isNotBlank()) put("extra", profile.xhttpExtra)
                    if (profile.packetEncoding.isNotBlank()) put("packetEncoding", profile.packetEncoding)
                    profile.country.countryCode?.let { put("countryCode", it) }
                }
                .toString()
            return "vmess://${Base64Codec.encode(payload.toByteArray(Charsets.UTF_8))}"
        }
        val query = linkedMapOf(
            "type" to profile.network,
            "security" to profile.security,
            "sni" to profile.sni,
            "host" to profile.host,
            "path" to profile.path,
            "alpn" to profile.alpn,
            "fp" to profile.fingerprint,
        )
        if (profile.protocol == ProxyProtocol.VLESS) query["encryption"] = profile.encryption
        if (profile.allowInsecure) query["allowInsecure"] = "1"
        if (profile.flow.isNotBlank()) query["flow"] = profile.flow
        if (profile.serviceName.isNotBlank()) query["serviceName"] = profile.serviceName
        if (profile.authority.isNotBlank()) query["authority"] = profile.authority
        if (profile.xhttpMode.isNotBlank()) query["mode"] = profile.xhttpMode
        if (profile.xhttpExtra.isNotBlank()) query["extra"] = profile.xhttpExtra
        if (profile.packetEncoding.isNotBlank()) query["packetEncoding"] = profile.packetEncoding
        profile.country.countryCode?.let { query["countryCode"] = it }
        if (profile.country.isKnown) query["country"] = profile.country.countryName
        val encodedQuery = query.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val host = if (profile.serverHost.contains(':')) "[${profile.serverHost}]" else profile.serverHost
        return "${profile.protocol.wireName}://${encode(profile.credential)}@$host:${profile.serverPort}?$encodedQuery#${encode(profile.name)}"
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('&').filter { it.isNotBlank() }.forEach { part ->
            val split = part.split('=', limit = 2)
            val key = decode(split[0]).trim().lowercase()
            require(key !in result) { "Duplicate parameter: $key" }
            result[key] = decode(split.getOrElse(1) { "" })
        }
        return result
    }

    private fun parseVmess(text: String, id: String, nameOverride: String?): ProxyProfile {
        val encoded = text.substringAfter("://").substringBefore('#').trim()
        val json = runCatching {
            JSONObject(Base64Codec.decode(encoded).toString(Charsets.UTF_8))
        }.getOrElse { throw IllegalArgumentException("Invalid VMess Base64 payload") }
        val credential = json.optString("id").trim()
        require(runCatching { UUID.fromString(credential) }.isSuccess) { "VMess UUID is invalid" }
        val sourceHost = json.optString("add").trim()
        require(sourceHost.isNotBlank()) { "VMess server host is missing" }
        val sourcePort = json.optString("port", "443").toIntOrNull() ?: json.optInt("port", 443)
        require(sourcePort in 1..65_535) { "VMess server port is invalid" }
        val network = ProfileNetworks.requireSupported(json.optString("net", "ws"), "VMess transport")
        val security = json.optString("tls", "tls").lowercase().ifBlank { "tls" }
        require(security == "tls") { "SNI mode requires TLS security" }
        val sni = json.optString("sni").ifBlank { json.optString("host") }.ifBlank { sourceHost }
        val host = json.optString("host").ifBlank { sni }
        val path = normalizePath(json.optString("path"), network)
        val headerType = json.optString("type")
        val serviceName = if (network == "grpc") json.optString("path").removePrefix("/") else ""
        if (network == "grpc") require(serviceName.isNotBlank()) { "gRPC serviceName is missing" }
        if (!ProfileNetworks.isXhttp(network)) {
            require(headerType.isBlank() || headerType.equals("none", true)) { "Unsupported TCP header type: $headerType" }
        }
        val xhttpMode = if (ProfileNetworks.isXhttp(network)) {
            ProfileNetworks.vmessMode(headerType, json.optString("mode"))
        } else {
            ""
        }
        val xhttpExtra = if (ProfileNetworks.isXhttp(network)) {
            ProfileNetworks.normalizeExtra(json.optString("extra"))
        } else {
            ""
        }
        val packetEncoding = ProfileNetworks.normalizePacketEncoding(json.optString("packetEncoding"))
        val country = CountryMetadata.resolve(json.optString("countryCode"), json.optString("country"))
        val name = nameOverride?.trim().orEmpty().ifBlank {
            json.optString("ps").trim().ifBlank { "VMESS • $sourceHost" }
        }.take(80)
        return ProxyProfile(
            id = id,
            name = name,
            protocol = ProxyProtocol.VMESS,
            credential = credential,
            serverHost = LocalForwardProfile.HOST,
            serverPort = LocalForwardProfile.PORT,
            network = network,
            security = security,
            sni = sni,
            host = host,
            path = path,
            alpn = json.optString("alpn").ifBlank { TlsAlpnResolver.canonicalString("", network) },
            fingerprint = json.optString("fp").ifBlank { "chrome" },
            allowInsecure = parseBoolean(json.optString("allowInsecure")),
            encryption = json.optString("scy", "auto").ifBlank { "auto" },
            alterId = json.optString("aid", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0,
            serviceName = serviceName,
            xhttpMode = xhttpMode,
            xhttpExtra = xhttpExtra,
            packetEncoding = packetEncoding,
            country = country,
            rawUri = text,
        )
    }

    private fun normalizePath(raw: String, network: String): String {
        if (network == "tcp" || network == "grpc") return raw
        val value = raw.ifBlank { "/" }
        return if (value.startsWith('/')) value else "/$value"
    }

    private fun coerceUriToSni(text: String): String {
        val uri = runCatching { URI(text) }
            .getOrElse { throw IllegalArgumentException("Malformed configuration URI") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "vless" || scheme == "trojan") { "Unsupported configuration protocol" }
        val credential = uri.rawUserInfo.orEmpty()
        val sourceHost = uri.host?.removePrefix("[")?.removeSuffix("]").orEmpty()
        require(sourceHost.isNotBlank()) { "Server host is missing or invalid" }
        val sourcePort = if (uri.port in 1..65_535) uri.port else 443
        val query = parseQueryLenient(uri.rawQuery)
        val sourceNetwork = (query["type"] ?: query["network"] ?: "ws").lowercase()
        val network = when (sourceNetwork) {
            "ws", "tcp", "httpupgrade", "grpc", "xhttp" -> sourceNetwork
            "splithttp" -> "xhttp"
            "h2", "http" -> "httpupgrade"
            "raw", "none" -> "tcp"
            else -> if (query["host"].orEmpty().isNotBlank() || query["path"].orEmpty().isNotBlank()) "ws" else "tcp"
        }
        val sni = query["sni"].orEmpty()
            .ifBlank { query["servername"].orEmpty() }
            .ifBlank { query["host"].orEmpty() }
            .ifBlank { sourceHost }
        val host = query["host"].orEmpty().ifBlank { sni }
        val serviceName = query["servicename"].orEmpty()
            .ifBlank { if (network == "grpc") query["path"].orEmpty().removePrefix("/") else "" }
        val sanitized = linkedMapOf(
            "type" to network,
            "security" to "tls",
            "sni" to sni,
            "host" to host,
            "path" to query["path"].orEmpty(),
            "alpn" to query["alpn"].orEmpty().ifBlank { TlsAlpnResolver.canonicalString("", network) },
            "fp" to query["fp"].orEmpty().ifBlank { query["fingerprint"].orEmpty() }.ifBlank { "chrome" },
        )
        if (scheme == "vless") sanitized["encryption"] = "none"
        if (query["security"].orEmpty().equals("tls", ignoreCase = true)) {
            query["flow"]?.takeIf(String::isNotBlank)?.let { sanitized["flow"] = it }
        }
        serviceName.takeIf(String::isNotBlank)?.let { sanitized["serviceName"] = it }
        query["authority"]?.takeIf(String::isNotBlank)?.let { sanitized["authority"] = it }
        if (ProfileNetworks.isXhttp(network)) {
            query["mode"]?.takeIf(String::isNotBlank)?.let { sanitized["mode"] = it }
            query["extra"]?.takeIf(String::isNotBlank)?.let { sanitized["extra"] = it }
        }
        query["packetencoding"]?.takeIf(String::isNotBlank)?.let { sanitized["packetEncoding"] = it }
        (query["countrycode"] ?: query["cc"])?.takeIf(String::isNotBlank)?.let { sanitized["countryCode"] = it }
        (query["country"] ?: query["location"])?.takeIf(String::isNotBlank)?.let { sanitized["country"] = it }
        val insecure = query["allowinsecure"] ?: query["insecure"]
        if (insecure?.trim()?.lowercase() in setOf("1", "true", "yes", "on")) sanitized["allowInsecure"] = "1"

        val encodedQuery = sanitized.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val hostPart = if (sourceHost.contains(':')) "[$sourceHost]" else sourceHost
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$credential@$hostPart:$sourcePort?$encodedQuery$fragment"
    }

    private fun coerceVmessToSni(text: String): String {
        val encoded = text.substringAfter("://").substringBefore('#').trim()
        val json = runCatching { JSONObject(Base64Codec.decode(encoded).toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Invalid VMess Base64 payload") }
        val sourceNetwork = json.optString("net", "ws").lowercase()
        val network = when (sourceNetwork) {
            "ws", "tcp", "httpupgrade", "grpc", "xhttp" -> sourceNetwork
            "splithttp" -> "xhttp"
            "h2", "http" -> "httpupgrade"
            "raw", "none" -> "tcp"
            else -> if (json.optString("host").isNotBlank() || json.optString("path").isNotBlank()) "ws" else "tcp"
        }
        val sourceHost = json.optString("add").trim()
        val sni = json.optString("sni").ifBlank { json.optString("host") }.ifBlank { sourceHost }
        json.put("net", network)
            .put("tls", "tls")
            .put("sni", sni)
            .put("host", json.optString("host").ifBlank { sni })
            .put("fp", json.optString("fp").ifBlank { "chrome" })
            .put("alpn", json.optString("alpn").ifBlank { TlsAlpnResolver.canonicalString("", network) })
        return "vmess://${Base64Codec.encode(json.toString().toByteArray(Charsets.UTF_8))}"
    }

    private fun parseQueryLenient(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.replace("&amp;", "&", ignoreCase = true).split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val split = part.split('=', limit = 2)
            val key = decode(split[0]).trim().lowercase().removePrefix("amp;")
            result[key] = decode(split.getOrElse(1) { "" })
        }
        return result
    }

    private fun parseBoolean(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return when (raw.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> throw IllegalArgumentException("Invalid allowInsecure value")
        }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrElse { value }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun newId(): String = "profile:${UUID.randomUUID()}"

}
