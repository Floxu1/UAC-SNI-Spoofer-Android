package com.uacspoofer.mobile.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale

internal class IpAddress private constructor(private val value: ByteArray) {
    val byteCount: Int get() = value.size
    val isIpv4: Boolean get() = value.size == IPV4_BYTES
    val isIpv6: Boolean get() = value.size == IPV6_BYTES
    val canonical: String by lazy {
        InetAddress.getByAddress(value).hostAddress.orEmpty().substringBefore('%').lowercase(Locale.ROOT)
    }
    val key: String by lazy {
        buildString(value.size * 2 + 1) {
            append(if (isIpv4) '4' else '6')
            append(':')
            value.forEach { byte -> append("%02x".format(Locale.ROOT, byte.toInt() and 0xff)) }
        }
    }

    fun bytes(): ByteArray = value.copyOf()

    fun subnetKey(prefixLength: Int = if (isIpv4) 24 else 48): String {
        val prefix = prefixLength.coerceIn(0, value.size * Byte.SIZE_BITS)
        val masked = mask(value, prefix)
        return "${if (isIpv4) 4 else 6}:${masked.toHex()}/$prefix"
    }

    override fun equals(other: Any?): Boolean = other is IpAddress && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = canonical

    companion object {
        private const val IPV4_BYTES = 4
        private const val IPV6_BYTES = 16

        fun parse(raw: String): IpAddress? {
            val text = raw.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            if (text.isBlank()) return null
            parseIpv4(text)?.let { return IpAddress(it) }
            if (!text.contains(':')) return null
            val address = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return null
            return when (address) {
                is Inet6Address -> IpAddress(address.address)
                is Inet4Address -> IpAddress(address.address)
                else -> null
            }
        }

        fun from(address: InetAddress): IpAddress? = when (address) {
            is Inet4Address, is Inet6Address -> IpAddress(address.address)
            else -> null
        }

        fun fromBytes(bytes: ByteArray): IpAddress? = when (bytes.size) {
            IPV4_BYTES, IPV6_BYTES -> IpAddress(bytes.copyOf())
            else -> null
        }

        private fun parseIpv4(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != IPV4_BYTES) return null
            val output = ByteArray(IPV4_BYTES)
            parts.forEachIndexed { index, part ->
                if (part.isBlank() || part.length > 3 || part.any { !it.isDigit() }) return null
                val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                output[index] = value.toByte()
            }
            return output
        }

        internal fun mask(bytes: ByteArray, prefixLength: Int): ByteArray {
            val output = bytes.copyOf()
            val fullBytes = prefixLength / Byte.SIZE_BITS
            val partialBits = prefixLength % Byte.SIZE_BITS
            if (fullBytes < output.size && partialBits > 0) {
                val keepMask = (0xff shl (Byte.SIZE_BITS - partialBits)) and 0xff
                output[fullBytes] = (output[fullBytes].toInt() and keepMask).toByte()
            }
            val zeroFrom = fullBytes + if (partialBits > 0) 1 else 0
            for (index in zeroFrom until output.size) output[index] = 0
            return output
        }
    }
}

internal class IpCidr private constructor(
    val network: IpAddress,
    val prefixLength: Int,
) {
    val isIpv4: Boolean get() = network.isIpv4
    val isIpv6: Boolean get() = network.isIpv6
    val canonical: String get() = "${network.canonical}/$prefixLength"

    fun contains(address: IpAddress): Boolean {
        if (address.byteCount != network.byteCount) return false
        return IpAddress.mask(address.bytes(), prefixLength)
            .contentEquals(network.bytes())
    }

    fun sample(seed: String, ordinal: Int): IpAddress {
        var attempt = 0
        while (true) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$seed|$canonical|$ordinal|$attempt".toByteArray(Charsets.UTF_8))
            val sampled = digest.copyOf(network.byteCount)
            restoreNetworkBits(sampled)
            val address = requireNotNull(IpAddress.fromBytes(sampled))
            if (!isUnusableIpv4Boundary(address) || attempt >= MAX_BOUNDARY_RETRIES) return address
            attempt++
        }
    }

    private fun restoreNetworkBits(sampled: ByteArray) {
        val base = network.bytes()
        val fullBytes = prefixLength / Byte.SIZE_BITS
        val partialBits = prefixLength % Byte.SIZE_BITS
        for (index in 0 until fullBytes) sampled[index] = base[index]
        if (fullBytes < sampled.size && partialBits > 0) {
            val keepMask = (0xff shl (Byte.SIZE_BITS - partialBits)) and 0xff
            sampled[fullBytes] = (
                (base[fullBytes].toInt() and keepMask) or
                    (sampled[fullBytes].toInt() and keepMask.inv() and 0xff)
                ).toByte()
        }
    }

    private fun isUnusableIpv4Boundary(address: IpAddress): Boolean {
        if (!isIpv4 || prefixLength > 30) return false
        val bytes = address.bytes()
        val first = network.bytes()
        val last = first.copyOf().also { output ->
            for (bit in prefixLength until 32) {
                val byteIndex = bit / Byte.SIZE_BITS
                val bitIndex = 7 - (bit % Byte.SIZE_BITS)
                output[byteIndex] = (output[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }
        return bytes.contentEquals(first) || bytes.contentEquals(last)
    }

    companion object {
        private const val MAX_BOUNDARY_RETRIES = 8

        fun parse(raw: String): IpCidr? {
            val parts = raw.trim().split('/', limit = 2)
            if (parts.size != 2) return null
            val address = IpAddress.parse(parts[0]) ?: return null
            val prefix = parts[1].toIntOrNull() ?: return null
            if (prefix !in 0..address.byteCount * Byte.SIZE_BITS) return null
            val network = IpAddress.fromBytes(IpAddress.mask(address.bytes(), prefix)) ?: return null
            return IpCidr(network, prefix)
        }
    }
}

internal fun canonicalEndpointKey(address: String, port: Int): String {
    val ip = IpAddress.parse(address)
    val hostKey = ip?.key ?: address.trim().trimEnd('.').lowercase(Locale.ROOT)
    return "$hostKey:$port"
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
