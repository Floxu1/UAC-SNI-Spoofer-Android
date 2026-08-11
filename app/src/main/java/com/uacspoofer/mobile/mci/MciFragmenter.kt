package com.uacspoofer.mobile.mci

import java.nio.charset.StandardCharsets

enum class FragmentStrategy {
    FINALMASK_TLS_HELLO,
    FULL5,
    FULL10,
    FULL20,
    SNI_BOUNDARY,
    SNI_SPLIT,
    TLS_RECORD_FRAG,
    TLS_SNI_RECORDS,
    HALF,
    RAW,
}

data class FinalMaskSettings(
    val packet: String = MciConfig.FINALMASK_PACKET,
    val length: Int = MciConfig.FINALMASK_LENGTH,
    val delayMs: Int = MciConfig.FINALMASK_DELAY_MS,
    val maxSplit: Int = MciConfig.FINALMASK_MAX_SPLIT,
)

data class FinalMaskRewrite(
    val firstWrite: ByteArray,
    val trailingWrite: ByteArray? = null,
) {
    fun writes(): List<ByteArray> = listOfNotNull(firstWrite, trailingWrite)

    fun bytes(): ByteArray = firstWrite + (trailingWrite ?: byteArrayOf())
}


object MciFragmenter {
    val finalMask = FinalMaskSettings()

    fun findSni(data: ByteArray): String {
        val location = locateSni(data) ?: return ""
        return String(data, location.first, location.second, StandardCharsets.US_ASCII)
    }

    fun fragment(
        data: ByteArray,
        strategy: FragmentStrategy,
    ): List<ByteArray> = when (strategy) {
        FragmentStrategy.FINALMASK_TLS_HELLO -> rewriteFinalMaskWrites(data).writes()
        FragmentStrategy.FULL5 -> fixedChunks(data, 5)
        FragmentStrategy.FULL10 -> fixedChunks(data, 10)
        FragmentStrategy.FULL20 -> fixedChunks(data, 20)
        FragmentStrategy.SNI_BOUNDARY -> splitAtSni(data, atBoundary = true)
        FragmentStrategy.SNI_SPLIT -> splitAtSni(data, atBoundary = false)
        FragmentStrategy.TLS_RECORD_FRAG -> splitTlsRecord(data, atSni = false)
        FragmentStrategy.TLS_SNI_RECORDS -> splitTlsRecord(data, atSni = true)
        FragmentStrategy.HALF -> splitAt(data, data.size / 2)
        FragmentStrategy.RAW -> listOf(data.copyOf())
    }

    






    fun rewriteFinalMaskWrites(
        data: ByteArray,
        settings: FinalMaskSettings = finalMask,
    ): FinalMaskRewrite {
        if (
            settings.packet != "tlshello" ||
            settings.length <= 0 ||
            settings.maxSplit < 2 ||
            data.size < 6 ||
            data[0].toInt() and 0xff != 0x16
        ) {
            return FinalMaskRewrite(data.copyOf())
        }
        val recordLength = unsignedShort(data, 3)
        val recordEnd = 5 + recordLength
        if (recordLength <= settings.length || recordEnd > data.size) {
            return FinalMaskRewrite(data.copyOf())
        }

        val version = data.copyOfRange(1, 3)
        val payload = data.copyOfRange(5, recordEnd)
        val first = tlsRecord(version, payload.copyOfRange(0, settings.length))
        val second = tlsRecord(version, payload.copyOfRange(settings.length, payload.size))
        val trailing = data.copyOfRange(recordEnd, data.size).takeIf { it.isNotEmpty() }
        return FinalMaskRewrite(first + second, trailing)
    }

    fun rewriteFinalMaskTlsHello(
        data: ByteArray,
        settings: FinalMaskSettings = finalMask,
    ): ByteArray = rewriteFinalMaskWrites(data, settings).bytes()

    private fun locateSni(data: ByteArray): Pair<Int, Int>? {
        try {
            if (data.size < 9 || data[0].toInt() and 0xff != 0x16) return null
            val recordEnd = minOf(data.size, 5 + unsignedShort(data, 3))
            var position = 5
            if (unsigned(data[position]) != 0x01) return null
            position += 4 + 2 + 32
            val sessionLength = unsigned(data[position])
            position += 1 + sessionLength
            val cipherLength = unsignedShort(data, position)
            position += 2 + cipherLength
            val compressionLength = unsigned(data[position])
            position += 1 + compressionLength
            val extensionsLength = unsignedShort(data, position)
            position += 2
            val extensionsEnd = minOf(recordEnd, position + extensionsLength)
            while (position + 4 <= extensionsEnd) {
                val type = unsignedShort(data, position)
                val length = unsignedShort(data, position + 2)
                position += 4
                if (type == 0 && position + length <= extensionsEnd) {
                    var namePosition = position + 2
                    val namesEnd = position + length
                    while (namePosition + 3 <= namesEnd) {
                        val nameType = unsigned(data[namePosition])
                        val nameLength = unsignedShort(data, namePosition + 1)
                        namePosition += 3
                        if (nameType == 0 && namePosition + nameLength <= namesEnd) {
                            return namePosition to nameLength
                        }
                        namePosition += nameLength
                    }
                }
                position += length
            }
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        return null
    }

    private fun fixedChunks(data: ByteArray, size: Int): List<ByteArray> {
        if (data.isEmpty()) return listOf(data.copyOf())
        val safeSize = maxOf(1, size)
        return (data.indices step safeSize).map { offset ->
            data.copyOfRange(offset, minOf(offset + safeSize, data.size))
        }
    }

    private fun splitAtSni(data: ByteArray, atBoundary: Boolean): List<ByteArray> {
        val location = locateSni(data) ?: return splitAt(data, data.size / 2)
        val split = if (atBoundary) location.first else location.first + maxOf(1, location.second / 2)
        return splitAt(data, split)
    }

    private fun splitAt(data: ByteArray, requested: Int): List<ByteArray> {
        if (data.size < 2) return listOf(data.copyOf())
        val split = requested.coerceIn(1, data.size - 1)
        return listOf(data.copyOfRange(0, split), data.copyOfRange(split, data.size))
    }

    private fun splitTlsRecord(data: ByteArray, atSni: Boolean): List<ByteArray> {
        if (data.size < 6 || unsigned(data[0]) != 0x16) return listOf(data.copyOf())
        val payload = data.copyOfRange(5, data.size)
        if (payload.size < 2) return listOf(data.copyOf())
        val location = if (atSni) locateSni(data) else null
        val requested = if (location != null) location.first - 5 else payload.size / 2
        val split = requested.coerceIn(1, payload.size - 1)
        val version = data.copyOfRange(1, 3)
        return listOf(
            tlsRecord(version, payload.copyOfRange(0, split)),
            tlsRecord(version, payload.copyOfRange(split, payload.size)),
        )
    }

    private fun tlsRecord(version: ByteArray, payload: ByteArray): ByteArray = byteArrayOf(
        0x16,
        version[0],
        version[1],
        ((payload.size ushr 8) and 0xff).toByte(),
        (payload.size and 0xff).toByte(),
    ) + payload

    private fun unsigned(value: Byte) = value.toInt() and 0xff

    private fun unsignedShort(data: ByteArray, offset: Int): Int =
        (unsigned(data[offset]) shl 8) or unsigned(data[offset + 1])
}
