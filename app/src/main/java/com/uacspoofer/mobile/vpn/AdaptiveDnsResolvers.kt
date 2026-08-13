package com.uacspoofer.mobile.vpn

data class AdaptiveDnsResolver(
    val id: String,
    val label: String,
    val url: String,
    val bootstrapHosts: Map<String, List<String>>,
)

object AdaptiveDnsResolvers {
    val CLOUDFLARE = AdaptiveDnsResolver(
        id = "cloudflare",
        label = "Cloudflare",
        url = "https://cloudflare-dns.com/dns-query",
        bootstrapHosts = mapOf("cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1")),
    )
    val GOOGLE = AdaptiveDnsResolver(
        id = "google",
        label = "Google",
        url = "https://dns.google/dns-query",
        bootstrapHosts = mapOf("dns.google" to listOf("8.8.8.8", "8.8.4.4")),
    )
    val QUAD9 = AdaptiveDnsResolver(
        id = "quad9",
        label = "Quad9",
        url = "https://dns.quad9.net/dns-query",
        bootstrapHosts = mapOf("dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112")),
    )
    val ADGUARD = AdaptiveDnsResolver(
        id = "adguard",
        label = "AdGuard unfiltered",
        url = "https://unfiltered.adguard-dns.com/dns-query",
        bootstrapHosts = mapOf(
            "unfiltered.adguard-dns.com" to listOf("94.140.14.140", "94.140.14.141"),
        ),
    )
    val OPENDNS = AdaptiveDnsResolver(
        id = "opendns",
        label = "OpenDNS",
        url = "https://doh.opendns.com/dns-query",
        bootstrapHosts = mapOf("doh.opendns.com" to listOf("208.67.222.222", "208.67.220.220")),
    )

    val all: List<AdaptiveDnsResolver> = listOf(CLOUDFLARE, GOOGLE, QUAD9, ADGUARD, OPENDNS)

    fun byUrl(url: String): AdaptiveDnsResolver? = all.firstOrNull { it.url.equals(url, ignoreCase = true) }

    fun ordered(preferredUrl: String): List<AdaptiveDnsResolver> {
        val preferred = byUrl(preferredUrl) ?: AdaptiveDnsResolver(
            id = "custom",
            label = "Custom",
            url = preferredUrl,
            bootstrapHosts = emptyMap(),
        )
        val fallback = when (preferred.id) {
            GOOGLE.id -> CLOUDFLARE
            CLOUDFLARE.id -> GOOGLE
            else -> GOOGLE
        }
        return listOf(preferred, fallback).distinctBy { it.url.lowercase() }
    }

    fun idFor(url: String): String = byUrl(url)?.id ?: "custom"
}
