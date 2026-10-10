package com.uacspoofer.mobile.engine.pow

import android.content.Context
import java.util.Locale

internal data class PowIdentityStatus(
    val present: Boolean,
    val enrolled: Boolean,
    val deviceId: String,
    val ipv4: String,
    val ipv6: String,
    val endpoint: String,
    val organization: String,
    val masqueCertificate: Boolean,
    val issuedAtEpochSec: Long,
    val peerPublicPrefix: String,
    val modifiedAtMs: Long,
) {
    fun issuedLabel(): String {
        if (issuedAtEpochSec <= 0L) return ""
        val millis = issuedAtEpochSec * 1000L
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val ageDays = ((System.currentTimeMillis() - millis) / 86_400_000L).coerceAtLeast(0L)
        val left = (365L - ageDays).coerceAtLeast(0L)
        return "${format.format(java.util.Date(millis))} · ${left}d"
    }

    companion object {
        val EMPTY = PowIdentityStatus(
            present = false,
            enrolled = false,
            deviceId = "",
            ipv4 = "",
            ipv6 = "",
            endpoint = "",
            organization = "",
            masqueCertificate = false,
            issuedAtEpochSec = 0L,
            peerPublicPrefix = "",
            modifiedAtMs = 0L,
        )

        fun read(context: Context): PowIdentityStatus {
            val file = PowCoreConfig.identityPath(context)
            if (!file.isFile) return EMPTY
            val fields = runCatching { parseTomlScalars(file.readText()) }.getOrDefault(emptyMap())
            val token = fields["access_token"].orEmpty()
            val privateKey = fields["wg_private_key"].orEmpty()
            val cert = fields["cert_pem"].orEmpty()
            val peer = fields["wg_peer_public_key"].orEmpty()
            return PowIdentityStatus(
                present = true,
                enrolled = token.length > 8 && privateKey.length > 20,
                deviceId = fields["device_id"].orEmpty(),
                ipv4 = fields["ipv4"].orEmpty(),
                ipv6 = fields["ipv6"].orEmpty(),
                endpoint = fields["assigned_endpoint"].orEmpty(),
                organization = fields["organization"].orEmpty(),
                masqueCertificate = cert.contains("BEGIN CERTIFICATE"),
                issuedAtEpochSec = fields["cert_issued_at"]?.toLongOrNull() ?: 0L,
                peerPublicPrefix = peer.take(16),
                modifiedAtMs = file.lastModified(),
            )
        }

        fun forget(context: Context): Boolean =
            PowCoreConfig.identityPath(context).let { file -> file.isFile && file.delete() }

        fun parseTomlScalars(text: String): Map<String, String> {
            val out = linkedMapOf<String, String>()
            val lines = text.lineSequence().iterator()
            while (lines.hasNext()) {
                val raw = lines.next()
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                out[key] = when {
                    value.startsWith("\"\"\"") -> readMultiline(value.removePrefix("\"\"\""), lines)
                    value.startsWith("\"") -> unquote(value)
                    else -> value.substringBefore('#').trim()
                }
            }
            return out
        }

        private fun readMultiline(first: String, lines: Iterator<String>): String {
            if (first.endsWith("\"\"\"") && first.length >= 3) {
                return first.removeSuffix("\"\"\"").trim()
            }
            val buffer = StringBuilder(first)
            while (lines.hasNext()) {
                val next = lines.next()
                val end = next.trimEnd()
                if (end.endsWith("\"\"\"")) {
                    buffer.append('\n').append(end.removeSuffix("\"\"\""))
                    break
                }
                buffer.append('\n').append(next)
            }
            return buffer.toString().trim()
        }

        private fun unquote(value: String): String {
            val body = value.removePrefix("\"")
            val end = body.indexOf('"')
            val raw = if (end >= 0) body.substring(0, end) else body
            return raw.replace("\\\"", "\"").replace("\\n", "\n")
        }
    }
}
