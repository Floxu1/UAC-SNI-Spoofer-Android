package com.uacspoofer.mobile.engine.direct

import android.net.VpnService
import com.uacspoofer.mobile.engine.pow.PowPsiphonClient
import com.uacspoofer.mobile.engine.pow.PowPsiphonProtocols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class DirectPsiphonService : com.uacspoofer.mobile.engine.pow.PowPsiphonService()

internal class DirectCountryExit(private val service: VpnService) {
    private var client: PowPsiphonClient? = null
    @Volatile private var running = false

    fun allocatePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    suspend fun start(wireGuardPort: Int, region: String, port: Int) {
        val dataDir = service.getDir("uac-direct-exit", 0).absolutePath
        var lastFailure: Exception? = null
        for (strategy in PowPsiphonProtocols.ladder(direct = false)) {
            val connected = CompletableDeferred<Unit>()
            val portReady = CompletableDeferred<Int>()
            val callbacks = object : PowPsiphonClient.Callbacks {
                override fun onConnecting() { running = false }
                override fun onConnected() {
                    running = true
                    connected.complete(Unit)
                }
                override fun onExiting() {
                    running = false
                    onStartFailed("Psiphon stopped on the way to $region")
                }
                override fun onListeningSocksProxyPort(port: Int) { portReady.complete(port) }
                override fun onStartFailed(message: String) {
                    connected.completeExceptionally(IllegalStateException(message))
                }
                override fun onDiagnosticMessage(message: String) = Unit
                override fun onBytesTransferred(sent: Long, received: Long) = Unit
                override fun onClientRegion(region: String) = Unit
                override fun onConnectedServerRegion(region: String) = Unit
                override fun onAvailableEgressRegions(regions: List<String>) = Unit
                override fun onClientAddress(address: String) = Unit
            }
            val next = PowPsiphonClient(service, callbacks, DirectPsiphonService::class.java.name)
            client = next
            val config = JSONObject(PowPsiphonProtocols.buildConfig(
                socksPort = port, dataDirectory = dataDir, preferredRegion = region, strategy = strategy,
            )).put("UpstreamProxyURL", "socks5://127.0.0.1:$wireGuardPort")
            try {
                next.start(config.toString())
                val boundPort = withTimeoutOrNull(30_000) {
                    connected.await()
                    portReady.await()
                } ?: error("Psiphon did not connect to $region")
                check(boundPort == port) { "Psiphon bound an unexpected SOCKS port" }
                val observed = verifyCountry(boundPort)
                check(observed == region) { "Requested exit $region, verified $observed" }
                return
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { stop() }
                throw cancelled
            } catch (error: Exception) {
                lastFailure = error
                stop()
            }
        }
        throw IllegalStateException("Could not verify the selected exit $region. ${lastFailure?.message.orEmpty()}", lastFailure)
    }

    fun isRunning() = running

    suspend fun stop() {
        val stopping = client ?: return
        client = null
        try {
            stopping.stop()
        } finally {
            stopping.close()
            running = false
        }
    }

    private fun verifyCountry(port: Int): String {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val request = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(proxy) as HttpsURLConnection
        request.connectTimeout = 5_000
        request.readTimeout = 5_000
        request.instanceFollowRedirects = false
        request.useCaches = false
        try {
            check(request.responseCode == 200) { "Exit country check returned HTTP ${request.responseCode}" }
            return request.inputStream.bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")
                    ?: error("Exit country check did not return a country")
            }
        } finally {
            request.disconnect()
        }
    }
}
