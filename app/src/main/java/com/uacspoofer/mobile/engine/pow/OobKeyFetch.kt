package com.uacspoofer.mobile.engine.pow

import android.net.VpnService
import com.uacspoofer.mobile.vpn.SocketProtectGate
import android.util.Base64
import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import android.system.Os
import android.system.OsConstants
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Android SslSocket + TCP urgent-byte OOB lane, mirroring desktop keyfetch-net.ps1
 * (the .NET/SChannel path that got KEY=YES). Used when api_camo=oob and no
 * identity is on disk yet: BoringSSL OOB on bionic often handshakes then hangs
 * on POST /reg with 0 response bytes.
 */
internal object OobKeyFetch {
    private const val TAG = "UAC-POW"
    private const val HOST = "api.cloudflareclient.com"
    private const val API = "/v0a4471/reg"
    private val DEFAULT_IPS = listOf("104.16.24.84", "104.16.192.82")

    private data class Method(val name: String, val mode: String, val sniRel: Int)

    private val METHODS = listOf(
        // Match native Android auto order (account.rs / oob.rs): nottl + plain/andr/split first.
        Method("oob-byte0-nottl", "oob", -1),
        Method("disoob-byte0-ttl1-plain", "disoobplain", -1),
        Method("disoob-byte0-ttl1-andr", "disoobandr", -1),
        Method("disoob-byte0-ttl1-split", "disoobsplit", -1),
        Method("disoob-byte0-ttl1", "disoob", -1),
        Method("oob-insni+5-ttl1", "oobttl", 5),
    )

    private val fetchLock = Any()

    @Volatile
    private var vpnService: VpnService? = null

    fun bindVpn(service: VpnService?) {
        vpnService = service
    }


    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun tryFetch(identityFile: File): Boolean = synchronized(fetchLock) {
        note("Key: java-oob lane starting (SslSocket + MSG_OOB, desktop keyfetch-net parity)")
        val ips = resolveIps()
        note("Key: java-oob target IPs ${ips.joinToString(", ")}")
        val masque = try {
            MasqueCred.generate()
        } catch (e: Exception) {
            note("Key: java-oob MASQUE cred generate failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        note("Key: java-oob MASQUE cred ready")
        var fails = 0
        for (method in METHODS) {
            // Skip separate probe handshake — register on first OOB connection.
            var ip = ips.first()
            note("Key: java-oob ${method.name} registering via $ip (no separate probe)")
            val wg = makeWgKey()
            val tos = utcNow()
            val serial = UUID.randomUUID().toString().replace("-", "").take(16)
            val regBody =
                """{"key":"$wg","install_id":"","fcm_token":"","tos":"$tos","model":"PC","serial_number":"$serial","os_version":"","key_type":"curve25519","tunnel_type":"wireguard","locale":"en_US"}"""
            val r1 = http(ip, method, "POST", API, regBody, null)
            val c1 = r1.code
            if (c1 == 429) {
                note("Key: java-oob ${method.name} register=429 (rate limited); stopping")
                return false
            }
            if (c1 != 200) {
                note("Key: java-oob ${method.name} register=$c1 KEY=NO detail=${r1.body.take(160)}")
                fails += 1
                // Cap Java burn so native connect_protected OOB can start (phone users have no desktop key).
                if (fails >= 3) {
                    note("Key: java-oob stopping after $fails KEY=NO; handing off to native OOB")
                    return false
                }
                Thread.sleep(400)
                continue
            }
            val j0 = runCatching { JSONObject(r1.body) }.getOrNull()
            val id = j0?.optString("id").orEmpty()
            val token = j0?.optString("token").orEmpty()
            if (j0 == null || id.isBlank() || token.isBlank()) {
                note("Key: java-oob ${method.name} register ok but no id/token")
                Thread.sleep(900)
                continue
            }
            val j = j0
            val patchBody =
                """{"key":"${masque.spkiB64}","key_type":"secp256r1","tunnel_type":"masque"}"""
            val r2 = http(ip, method, "PATCH", "$API/$id", patchBody, token)
            if (r2.code != 200) {
                note("Key: java-oob ${method.name} register=200 MASQUE=${r2.code} KEY=NO; cleaning up")
                runCatching { http(ip, method, "DELETE", "$API/$id", null, token) }
                Thread.sleep(900)
                continue
            }
            val peerPub = j.optJSONObject("config")
                ?.optJSONArray("peers")
                ?.optJSONObject(0)
                ?.optString("public_key")
                .orEmpty()
            if (peerPub.isBlank()) {
                note("Key: java-oob ${method.name} missing peer public key")
                runCatching { http(ip, method, "DELETE", "$API/$id", null, token) }
                continue
            }
            val ipv4 = j.optJSONObject("config")?.optJSONObject("interface")
                ?.optJSONObject("addresses")?.optString("v4").orEmpty()
            val ipv6 = j.optJSONObject("config")?.optJSONObject("interface")
                ?.optJSONObject("addresses")?.optString("v6").orEmpty()
            var assigned = endpointHost(
                j.optJSONObject("config")?.optJSONArray("peers")
                    ?.optJSONObject(0)?.optJSONObject("endpoint")?.optString("v4"),
            )
            runCatching {
                val j2 = JSONObject(r2.body)
                val ep2 = endpointHost(
                    j2.optJSONObject("config")?.optJSONArray("peers")
                        ?.optJSONObject(0)?.optJSONObject("endpoint")?.optString("v4"),
                )
                if (ep2.isNotBlank()) assigned = ep2
            }
            val clientId = j.optJSONObject("config")?.optString("client_id").orEmpty().ifBlank { "AAAA" }
            val org = j.optJSONObject("account")?.optString("organization").orEmpty()
            val gw = j.optJSONObject("config")?.optJSONObject("services")
                ?.optString("http_proxy").orEmpty()
            writeToml(
                identityFile,
                id = id,
                token = token,
                certPem = masque.certPem,
                keyPem = masque.keyPem,
                ipv4 = ipv4,
                ipv6 = ipv6,
                wgPrivate = wg,
                peerPub = peerPub,
                clientId = clientId,
                org = org,
                gw = gw,
                assigned = assigned,
            )
            note("Key: java-oob ${method.name} register=200 MASQUE=200 KEY=YES via $ip; wrote ${identityFile.name}")
            return true
        }
        note("Key: java-oob: no method produced a key")
        return false
    }

    private fun resolveIps(): List<String> {
        // Keep the two proven anycast edges; DNS may be filtered on MCI.
        return DEFAULT_IPS
    }

    private fun probeHello(ip: String, method: Method): Boolean = try {
        openSsl(ip, method).use { session ->
            session.ssl.startHandshake()
            true
        }
    } catch (e: Exception) {
        note("Key: java-oob ${method.name} hello via $ip failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun http(
        ip: String,
        method: Method,
        verb: String,
        path: String,
        body: String?,
        token: String?,
    ): HttpResult {
        fun once(): HttpResult = openSsl(ip, method).use { session ->
            val ssl = session.ssl
            ssl.startHandshake()
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            val head = StringBuilder()
            head.append(verb).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(HOST).append("\r\n")
            head.append("CF-Client-Version: a-6.35-4471\r\n")
            head.append("User-Agent: WARP for Android\r\n")
            head.append("Content-Type: application/json; charset=UTF-8\r\n")
            head.append("Connection: close\r\n")
            if (!token.isNullOrBlank()) head.append("Authorization: Bearer ").append(token).append("\r\n")
            head.append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
            val out = ssl.outputStream
            out.write(head.toString().toByteArray(StandardCharsets.US_ASCII))
            if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
            out.flush()
            val raw = readHttp(ssl.inputStream)
            if (raw.isEmpty()) return HttpResult(0, "READ empty")
            val sp = raw.indexOf(' ')
            val sp2 = if (sp < 0) -1 else raw.indexOf(' ', sp + 1)
            val code = if (sp >= 0 && sp2 > sp) raw.substring(sp + 1, sp2).toIntOrNull() ?: 0 else 0
            val hdrEnd = raw.indexOf("\r\n\r\n")
            val rbody = if (hdrEnd >= 0) raw.substring(hdrEnd + 4) else raw
            HttpResult(code, rbody)
        }
        // Single attempt per method: second try on same filtered path just burns ~20s.
        return runCatching { once() }.getOrElse { HttpResult(0, it.javaClass.simpleName + ":" + it.message) }
    }

    private fun readHttp(input: InputStream): String {
        val bis = BufferedInputStream(input)
        val ms = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var hdrEnd = -1
        var clen = -1
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (bis.available() == 0) {
                // Still try a blocking read with socket timeout already set.
            }
            val n = try {
                bis.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) break
            ms.write(buf, 0, n)
            val cur = ms.toString(StandardCharsets.UTF_8.name())
            if (hdrEnd < 0) {
                hdrEnd = cur.indexOf("\r\n\r\n")
                if (hdrEnd >= 0) {
                    val hl = cur.substring(0, hdrEnd).lowercase(Locale.US)
                    val ci = hl.indexOf("content-length:")
                    if (ci >= 0) {
                        var ce = hl.indexOf("\r\n", ci)
                        if (ce < 0) ce = hl.length
                        clen = hl.substring(ci + 15, ce).trim().toIntOrNull() ?: -1
                    }
                }
            }
            if (hdrEnd >= 0 && clen >= 0 && ms.size() >= hdrEnd + 4 + clen) break
        }
        return ms.toString(StandardCharsets.UTF_8.name())
    }

    private fun openSsl(ip: String, method: Method): OobSession {
        // Conscrypt writes ClientHello to the FD and bypasses getOutputStream().
        // Local bridge forces the hello through our pump so MSG_OOB can run
        // (desktop keyfetch-net / SslStream-over-socket parity).
        // Protect BEFORE connect (native connect_protected parity). Connecting
        // first can bind the socket into the VPN routing table on some ROMs.
        val remote = Socket()
        remote.tcpNoDelay = true
        remote.soTimeout = 8_000
        val vpn = vpnService
        if (vpn != null) {
            if (!SocketProtectGate.isEnabled(vpn)) {
                note("Key: java-oob protect skipped (socket_protect_enabled=false)")
            } else {
                val ok = runCatching { vpn.protect(remote) }.getOrDefault(false)
                note("Key: java-oob protect(socket)=$ok (pre-connect)")
            }
        } else {
            note("Key: java-oob protect skipped (no VpnService yet)")
        }
        remote.connect(InetSocketAddress(ip, 443), 6_000)
        val bridge = OobBridge(remote, method.mode, method.sniRel, HOST)
        bridge.start()
        val local = bridge.connectLocal()
        local.tcpNoDelay = true
        local.soTimeout = 8_000
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(null, arrayOf(TrustAll), SecureRandom())
        val factory: SSLSocketFactory = ctx.socketFactory
        val ssl = factory.createSocket(local, HOST, 443, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.2")
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(HOST))
        // Match C#: no ALPN. Do NOT call setApplicationProtocols - API 29+ only.
        ssl.sslParameters = params
        ssl.soTimeout = 8_000
        return OobSession(ssl, bridge)
    }

    private class OobSession(
        val ssl: SSLSocket,
        private val bridge: OobBridge,
    ) : Closeable {
        override fun close() {
            try { ssl.close() } catch (_: Exception) {}
            try { bridge.close() } catch (_: Exception) {}
        }
    }

    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Accepts one local SSLSocket peer and pumps bytes to [remote], applying
     * TCP urgent-byte desync on the first complete TLS record (type 0x16).
     */
    private class OobBridge(
        private val remote: Socket,
        private val mode: String,
        private val sniRel: Int,
        private val sniHost: String,
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val first = AtomicBoolean(true)
        @Volatile private var local: Socket? = null
        @Volatile private var alive = true

        fun start() {
            Thread({
                try {
                    val peer = server.accept()
                    local = peer
                    try { server.close() } catch (_: Exception) {}
                    val up = Thread({ pumpUp(peer) }, "java-oob-up")
                    val down = Thread({ pumpDown(peer) }, "java-oob-down")
                    up.isDaemon = true
                    down.isDaemon = true
                    up.start()
                    down.start()
                } catch (e: Exception) {
                    note("Key: java-oob bridge accept failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }, "java-oob-accept").also { it.isDaemon = true; it.start() }
        }

        fun connectLocal(): Socket {
            val s = Socket()
            s.tcpNoDelay = true
            // Wait briefly for accept thread.
            var last: Exception? = null
            for (i in 0 until 50) {
                try {
                    s.connect(InetSocketAddress("127.0.0.1", server.localPort), 500)
                    return s
                } catch (e: Exception) {
                    last = e
                    Thread.sleep(20)
                }
            }
            throw last ?: IllegalStateException("bridge connect failed")
        }

        private fun pumpUp(peer: Socket) {
            try {
                val inp = peer.getInputStream()
                val out = remote.getOutputStream()
                val buf = ByteArray(16_384)
                val pending = ByteArrayOutputStream()
                while (alive) {
                    val n = try { inp.read(buf) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    if (!first.get()) {
                        out.write(buf, 0, n)
                        out.flush()
                        continue
                    }
                    pending.write(buf, 0, n)
                    val all = pending.toByteArray()
                    // Wait for a complete TLS record before desync.
                    if (all.size < 5) continue
                    val recLen = ((all[3].toInt() and 0xff) shl 8) or (all[4].toInt() and 0xff)
                    val need = 5 + recLen
                    if (all.size < need) continue
                    val record = all.copyOf(need)
                    val rest = if (all.size > need) all.copyOfRange(need, all.size) else ByteArray(0)
                    first.set(false)
                    note("Key: java-oob bridge first TLS record ${record.size} bytes (type=${record[0].toInt() and 0xff}) mode=$mode")
                    desyncFirstWrite(record, out)
                    if (rest.isNotEmpty()) {
                        out.write(rest)
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                note("Key: java-oob bridge up ended: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { remote.shutdownOutput() } catch (_: Exception) {}
            }
        }

        private fun pumpDown(peer: Socket) {
            try {
                val inp = remote.getInputStream()
                val out = peer.getOutputStream()
                val buf = ByteArray(16_384)
                while (alive) {
                    val n = try {
                        inp.read(buf)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        -1
                    }
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { peer.shutdownOutput() } catch (_: Exception) {}
            }
        }

        private fun desyncFirstWrite(ch: ByteArray, out: OutputStream) {
            var off = 0
            if (sniRel >= 0) {
                val so = indexOf(ch, sniHost.toByteArray(StandardCharsets.US_ASCII))
                off = (if (so < 0) 0 else so) + sniRel
                if (off < 0 || off >= ch.size) off = 0
            }
            // C# / aether: restore TTL right after urgent/prefix; pause BEFORE normal hello.
            when (mode) {
                "plain" -> out.write(ch)
                "disoob", "disoobandr" -> {
                    setTtl(remote, 1)
                    remote.sendUrgentData(ch[0].toInt() and 0xff)
                    setTtl(remote, 64)
                    Thread.sleep(50)
                    out.write(ch)
                }
                "disoobplain" -> {
                    setTtl(remote, 1)
                    out.write(ch, 0, 1)
                    out.flush()
                    setTtl(remote, 64)
                    Thread.sleep(50)
                    out.write(ch)
                }
                "disoobsplit" -> {
                    setTtl(remote, 1)
                    remote.sendUrgentData(ch[0].toInt() and 0xff)
                    setTtl(remote, 64)
                    Thread.sleep(50)
                    out.write(ch, 0, 1)
                    out.flush()
                    out.write(ch, 1, ch.size - 1)
                }
                "oob" -> {
                    if (off > 0) out.write(ch, 0, off)
                    remote.sendUrgentData(ch[off].toInt() and 0xff)
                    Thread.sleep(50)
                    out.write(ch, off, ch.size - off)
                }
                "oobttl" -> {
                    setTtl(remote, 1)
                    if (off > 0) out.write(ch, 0, off)
                    remote.sendUrgentData(ch[off].toInt() and 0xff)
                    setTtl(remote, 64)
                    Thread.sleep(50)
                    out.write(ch, off, ch.size - off)
                }
                else -> out.write(ch)
            }
        }

        private fun setTtl(sock: Socket, ttl: Int) {
            runCatching {
                val implField: Field = Socket::class.java.getDeclaredField("impl").also { it.isAccessible = true }
                val impl = implField.get(sock) ?: return
                val getFd = impl.javaClass.getDeclaredMethod("getFileDescriptor").also { it.isAccessible = true }
                val fd = getFd.invoke(impl) as java.io.FileDescriptor
                Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, ttl)
            }
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) {
                    if (hay[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        override fun close() {
            alive = false
            try { local?.close() } catch (_: Exception) {}
            try { remote.close() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}
        }
    }

    private data class MasqueCred(val spkiB64: String, val certPem: String, val keyPem: String) {
        companion object {
            fun generate(): MasqueCred {
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(ECGenParameterSpec("secp256r1"))
                val kp = kpg.generateKeyPair()
                val spki = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
                val now = System.currentTimeMillis()
                val notBefore = Date(now - 86_400_000L)
                val notAfter = Date(now + 365L * 86_400_000L)
                // Android P+: BC must not supply Signature.SHA256withECDSA (platform does).
                // Keep BC for ASN.1 cert builder only; sign + convert via platform JCA.
                val dn = X500Name("CN=Cloudflare")
                val builder = JcaX509v3CertificateBuilder(
                    dn,
                    BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
                    notBefore,
                    notAfter,
                    dn,
                    kp.public,
                )
                val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
                val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
                return MasqueCred(
                    spkiB64 = spki,
                    // NO_WRAP + PemUtil: Base64.DEFAULT inserts 76-col newlines that
                    // become blank PEM lines when re-chunked to 64 -> BoringSSL NOT_PROC_TYPE.
                    certPem = PemUtil.fromDer("CERTIFICATE", cert.encoded),
                    keyPem = PemUtil.fromDer("PRIVATE KEY", kp.private.encoded),
                )
            }
        }
    }

    private fun makeWgKey(): String {
        val kb = ByteArray(32)
        SecureRandom().nextBytes(kb)
        return Base64.encodeToString(kb, Base64.NO_WRAP)
    }

    private fun utcNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun endpointHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val i = raw.lastIndexOf(':')
        return if (i > 0) raw.substring(0, i) else raw
    }

    private fun writeToml(
        file: File,
        id: String,
        token: String,
        certPem: String,
        keyPem: String,
        ipv4: String,
        ipv6: String,
        wgPrivate: String,
        peerPub: String,
        clientId: String,
        org: String,
        gw: String,
        assigned: String,
    ) {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        val issued = System.currentTimeMillis() / 1000
        val text = buildString {
            append("device_id = \"").append(esc(id)).append("\"\n")
            append("access_token = \"").append(esc(token)).append("\"\n")
            append("cert_pem = \"").append(esc(PemUtil.normalizePem(certPem))).append("\"\n")
            append("key_pem = \"").append(esc(PemUtil.normalizePem(keyPem))).append("\"\n")
            append("cert_issued_at = ").append(issued).append('\n')
            append("ipv4 = \"").append(esc(ipv4)).append("\"\n")
            append("ipv6 = \"").append(esc(ipv6)).append("\"\n")
            append("wg_private_key = \"").append(esc(wgPrivate)).append("\"\n")
            append("wg_peer_public_key = \"").append(esc(peerPub)).append("\"\n")
            append("client_id = \"").append(esc(clientId)).append("\"\n")
            append("organization = \"").append(esc(org)).append("\"\n")
            append("gateway_proxy = \"").append(esc(gw)).append("\"\n")
            append("assigned_endpoint = \"").append(esc(assigned)).append("\"\n")
        }
        file.parentFile?.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
    }

    private fun note(msg: String) {
        Log.i(TAG, msg)
        AppLogRepository.info(LogSource.POW, msg.take(500))
    }
}
