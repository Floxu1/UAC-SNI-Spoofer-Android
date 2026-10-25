package com.uacspoofer.mobile.engine.pow

/**
 * Normalizes PEM blobs so BoringSSL/OpenSSL never see blank lines inside the
 * base64 body (PEM_R_NOT_PROC_TYPE / NOT_PROC_TYPE).
 *
 * Android Base64.DEFAULT wraps at 76 cols; re-chunking that string into 64-char
 * lines can leave a lone "\n" as its own "line", which breaks cert parsing.
 */
internal object PemUtil {
    fun normalizePem(pem: String): String {
        if (pem.isBlank()) return pem
        val lines = pem.replace("\r\n", "\n").replace("\r", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return pem.trim() + "\n"

        val begin = lines.firstOrNull { it.startsWith("-----BEGIN") }
            ?: return pem.trimEnd() + "\n"
        val end = lines.lastOrNull { it.startsWith("-----END") }
            ?: return pem.trimEnd() + "\n"
        val body = lines
            .filter { !it.startsWith("-----") }
            .joinToString("") { it.filterNot { ch -> ch.isWhitespace() } }

        val out = StringBuilder()
        out.append(begin).append('\n')
        var i = 0
        while (i < body.length) {
            val endIdx = minOf(i + 64, body.length)
            out.append(body, i, endIdx).append('\n')
            i = endIdx
        }
        out.append(end).append('\n')
        return out.toString()
    }

    fun fromDer(label: String, der: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
        val raw = buildString {
            append("-----BEGIN ").append(label).append("-----\n")
            var i = 0
            while (i < b64.length) {
                val end = minOf(i + 64, b64.length)
                append(b64, i, end).append('\n')
                i = end
            }
            append("-----END ").append(label).append("-----\n")
        }
        return normalizePem(raw)
    }

    /** Rewrites cert_pem / key_pem in an identity TOML if they contain blank lines or odd wrapping. */
    fun sanitizeIdentityFile(file: java.io.File): Boolean {
        if (!file.isFile) return false
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val fields = PowIdentityStatus.parseTomlScalars(text)
        val cert = fields["cert_pem"].orEmpty()
        val key = fields["key_pem"].orEmpty()
        if (cert.isEmpty() && key.isEmpty()) return false
        val nCert = if (cert.isNotEmpty()) normalizePem(cert) else cert
        val nKey = if (key.isNotEmpty()) normalizePem(key) else key
        if (nCert == cert && nKey == key) return false

        fun esc(s: String) =
            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

        val rebuilt = buildString {
            fun putStr(k: String, v: String) {
                append(k).append(" = \"").append(esc(v)).append("\"\n")
            }
            fun putRaw(k: String, v: String) {
                append(k).append(" = ").append(v).append('\n')
            }
            putStr("device_id", fields["device_id"].orEmpty())
            putStr("access_token", fields["access_token"].orEmpty())
            putStr("cert_pem", nCert)
            putStr("key_pem", nKey)
            val issued = fields["cert_issued_at"].orEmpty()
            if (issued.isNotBlank()) putRaw("cert_issued_at", issued) else putRaw("cert_issued_at", "0")
            putStr("ipv4", fields["ipv4"].orEmpty())
            putStr("ipv6", fields["ipv6"].orEmpty())
            putStr("wg_private_key", fields["wg_private_key"].orEmpty())
            putStr("wg_peer_public_key", fields["wg_peer_public_key"].orEmpty())
            putStr("client_id", fields["client_id"].orEmpty())
            putStr("organization", fields["organization"].orEmpty())
            putStr("gateway_proxy", fields["gateway_proxy"].orEmpty())
            putStr("assigned_endpoint", fields["assigned_endpoint"].orEmpty())
        }
        file.writeText(rebuilt, Charsets.UTF_8)
        return true
    }
}
