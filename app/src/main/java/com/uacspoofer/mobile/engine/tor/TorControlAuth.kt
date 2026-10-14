package com.uacspoofer.mobile.engine.tor

import java.security.MessageDigest
import java.security.SecureRandom

/** Per-start random control password + Tor's HashedControlPassword (RFC 2440 S2K, c=0x60). */
internal object TorControlAuth {
    private const val S2K_SPECIFIER = 0x60

    fun newPassword(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hash(password: String, salt: ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) }): String {
        require(salt.size == 8)
        val c = S2K_SPECIFIER
        val count = (16 + (c and 15)) shl ((c shr 4) + 6)
        val secret = salt + password.toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-1")
        var remaining = count
        while (remaining > 0) {
            if (remaining >= secret.size) {
                sha.update(secret)
                remaining -= secret.size
            } else {
                sha.update(secret, 0, remaining)
                remaining = 0
            }
        }
        val digest = sha.digest()
        return "16:" + (salt + byteArrayOf(c.toByte()) + digest).joinToString("") { "%02X".format(it) }
    }
}