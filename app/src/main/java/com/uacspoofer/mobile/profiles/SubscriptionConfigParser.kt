package com.uacspoofer.mobile.profiles

import java.util.ArrayDeque

data class SubscriptionParseResult(
    val profiles: List<ProxyProfile>,
    val invalidCount: Int,
    val decodedPayloadCount: Int,
    val truncated: Boolean,
)


object SubscriptionConfigParser {
    fun parse(raw: String, maxProfiles: Int = MAX_PROFILES): SubscriptionParseResult {
        if (raw.isBlank()) return SubscriptionParseResult(emptyList(), 0, 0, false)
        val sourceWasTruncated = raw.length > MAX_SOURCE_CHARS
        val payloads = expandPayloads(raw)
        val uris = LinkedHashSet<String>()
        var truncated = false
        payloads.forEach { payload ->
            ProfileUriParser.extractUris(payload).forEach { uri ->
                if (uris.size < maxProfiles) uris += uri else truncated = true
            }
        }

        val profiles = ArrayList<ProxyProfile>(uris.size)
        val signatures = HashSet<String>(uris.size)
        var invalid = 0
        uris.forEach { uri ->
            runCatching { ProfileUriParser.parseForSniMaker(uri) }
                .onSuccess { profile ->
                    val signature = ProfileUriParser.canonicalUri(profile)
                    if (signatures.add(signature)) profiles += profile
                }
                .onFailure { invalid++ }
        }
        return SubscriptionParseResult(
            profiles = profiles,
            invalidCount = invalid,
            decodedPayloadCount = (payloads.size - 1).coerceAtLeast(0),
            truncated = truncated || sourceWasTruncated,
        )
    }

    private fun expandPayloads(raw: String): List<String> {
        val queue = ArrayDeque<Pair<String, Int>>()
        val payloads = ArrayList<String>()
        val seenPayloads = HashSet<String>()

        fun enqueue(text: String, depth: Int) {
            val clean = text.trim().take(MAX_SOURCE_CHARS)
            if (clean.isNotBlank() && seenPayloads.add(clean)) queue.add(clean to depth)
        }

        enqueue(raw, 0)
        while (queue.isNotEmpty() && payloads.size < MAX_DECODED_PAYLOADS) {
            val (text, depth) = queue.removeFirst()
            payloads += text
            if (depth >= MAX_BASE64_DEPTH) continue

            val collapsed = text.filterNot(Char::isWhitespace)
            decodeCandidate(collapsed)?.let { enqueue(it, depth + 1) }
            text.lineSequence().take(MAX_BASE64_LINES).forEach { line ->
                decodeCandidate(line.trim())?.let { enqueue(it, depth + 1) }
            }
        }
        return payloads
    }

    private fun decodeCandidate(value: String): String? {
        if (value.length !in MIN_BASE64_LENGTH..MAX_BASE64_LENGTH) return null
        if (!value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' || it == '=' }) return null
        return runCatching { Base64Codec.decode(value).toString(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { decoded ->
                decoded.contains("://") || decoded.count { it == '\n' } > 1 ||
                    decoded.filterNot(Char::isWhitespace).let { nested ->
                        nested.length >= MIN_BASE64_LENGTH && nested.all {
                            it.isLetterOrDigit() || it == '+' || it == '/' || it == '-' || it == '_' || it == '='
                        }
                    }
            }
    }

    private const val MAX_PROFILES = 10_000
    private const val MAX_SOURCE_CHARS = 24 * 1024 * 1024
    private const val MAX_DECODED_PAYLOADS = MAX_PROFILES + 32
    private const val MAX_BASE64_LINES = 12_000
    private const val MAX_BASE64_DEPTH = 3
    private const val MIN_BASE64_LENGTH = 24
    private const val MAX_BASE64_LENGTH = 24 * 1024 * 1024
}
