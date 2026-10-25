package com.uacspoofer.mobile.engine.pow

import android.net.VpnService
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object AetherNative {
    @Volatile
    private var loadError: String? = null

    init {
        loadError = runCatching {
            System.loadLibrary("aether")
            System.loadLibrary("aether_jni")
            null
        }.exceptionOrNull()?.message
    }

    val available: Boolean get() = loadError == null

    val unavailableReason: String
        get() = loadError ?: "UAC PoW native libraries are not packaged for this ABI"

    data class TunnelAddresses(
        val ipv4: String,
        val ipv6: String,
        val gatewayProxy: String = "",
        val organization: String = "",
    )

    fun prepare(config: String): TunnelAddresses {
        checkAvailable()
        // When the user chose OOB-only and there is no identity yet, try the
        // Java SslSocket+OOB lane first (desktop keyfetch-net parity). BoringSSL
        // OOB on Android often completes the TLS handshake then gets 0 HTTP bytes.
        runCatching {
            val json = JSONObject(config)
            if (json.optString("api_camo") == "oob") {
                val path = json.optString("config_path")
                if (path.isNotBlank()) {
                    val file = java.io.File(path)
                    if (!file.exists()) {
                        val ok = OobKeyFetch.tryFetch(file)
                        android.util.Log.i("UAC-POW", "Key: java-oob tryFetch returned $ok")
                    }
                }
            }
        }.onFailure { err ->
            android.util.Log.e("UAC-POW", "Key: java-oob lane crashed: ${err.javaClass.simpleName}: ${err.message}", err)
        }
        // Defensive: rewrite identity PEMs if an older build left blank lines in cert_pem.
        runCatching {
            val path = JSONObject(config).optString("config_path")
            if (path.isNotBlank()) {
                val file = java.io.File(path)
                if (PemUtil.sanitizeIdentityFile(file)) {
                    android.util.Log.i("UAC-POW", "Key: sanitized blank lines out of identity PEM")
                }
            }
        }
        check(nativePrepare(config) == 0) { nativeLastError().ifBlank { "prepare failed" } }
        val result = JSONObject(nativeLastResult())
        return TunnelAddresses(
            result.getString("ipv4"),
            result.optString("ipv6"),
            result.optString("gateway_proxy"),
            result.optString("organization"),
        )
    }

    @Volatile
    private var prepareThread: Thread? = null
    private val prepareGeneration = AtomicLong(0L)

    /**
     * Runs the blocking native prepare (key race, certificate renewal) on its own
     * thread so the caller can be cancelled. Cancelling the caller asks the native
     * core to abort the prepare and returns immediately instead of waiting for it.
     */
    suspend fun prepareCancellable(config: String): TunnelAddresses {
        awaitPrepareIdle(PREPARE_IDLE_WAIT_MS)
        val generation = prepareGeneration.incrementAndGet()
        val outcome = CompletableDeferred<Result<TunnelAddresses>>()
        val worker = Thread({
            outcome.complete(runCatching { prepare(config) })
        }, "uac-pow-prepare-$generation")
        worker.isDaemon = true
        prepareThread = worker
        worker.start()
        try {
            return outcome.await().getOrThrow()
        } catch (cancelled: CancellationException) {
            abortPrepare()
            throw cancelled
        }
    }

    /** Non-blocking: keeps asking the native core to drop an in-flight prepare until it returns. */
    fun abortPrepare() {
        if (!available) return
        val worker = prepareThread ?: return
        if (!worker.isAlive) return
        val generation = prepareGeneration.get()
        nativeCancelPrepare()
        val reaper = Thread({
            val deadline = SystemClock.elapsedRealtime() + PREPARE_ABORT_WINDOW_MS
            while (
                worker.isAlive &&
                prepareGeneration.get() == generation &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                nativeCancelPrepare()
                runCatching { Thread.sleep(100L) }
            }
        }, "uac-pow-prepare-abort")
        reaper.isDaemon = true
        reaper.start()
    }

    fun isPreparing(): Boolean = prepareThread?.isAlive == true

    private fun awaitPrepareIdle(timeoutMs: Long) {
        val worker = prepareThread ?: return
        if (!worker.isAlive) return
        runCatching { worker.join(timeoutMs) }
    }

    private const val PREPARE_IDLE_WAIT_MS = 3_000L
    private const val PREPARE_ABORT_WINDOW_MS = 10_000L

    fun startProxy(config: String): Int {
        checkAvailable()
        return nativeStartProxy(config)
    }

    fun stop(): Int {
        if (!available) return 0
        return nativeStop()
    }

    fun isRunning(): Boolean = available && nativeIsRunning()

    fun isReady(): Boolean = available && nativeIsReady()

    fun lastError(): String = if (available) nativeLastError() else unavailableReason

    fun lastLog(): String = if (available) nativeLastLog() else ""

    fun attach(service: VpnService) {
        checkAvailable()
        OobKeyFetch.bindVpn(service)
        nativeAttach(service)
    }

    fun detach() {
        if (!available) return
        nativeDetach()
    }

    private fun checkAvailable() {
        check(available) { unavailableReason }
    }

    @JvmStatic private external fun nativePrepare(config: String): Int
    @JvmStatic private external fun nativeLastResult(): String
    @JvmStatic private external fun nativeStartProxy(config: String): Int
    @JvmStatic private external fun nativeStop(): Int
    @JvmStatic private external fun nativeCancelPrepare(): Int
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeIsReady(): Boolean
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeLastLog(): String
    @JvmStatic private external fun nativeAttach(service: VpnService)
    @JvmStatic private external fun nativeDetach()
}
