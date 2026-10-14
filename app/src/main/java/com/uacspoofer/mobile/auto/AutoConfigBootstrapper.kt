package com.uacspoofer.mobile.auto

import android.content.Context
import android.os.SystemClock
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayBatchRoute
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.profiles.DirectCompatProfile
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.vpn.ConnectEdgePoolStore
import com.uacspoofer.mobile.vpn.UnderlyingNetworkSnapshot
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Engine 1 Auto mode: picks a working Patterniha config for this network. Runs only inside a connect
 * (no background work): operator/DPI type -> list -> clean-IP copies -> capped batch real-delay test
 * (about 24 configs per Xray process, ~6 s) -> top 10 kept per network.
 */
class AutoConfigBootstrapper(context: Context) {
    private val appContext = context.applicationContext
    val store = AutoConfigStore(appContext)
    private val edgePoolStore = ConnectEdgePoolStore(appContext)

    private data class Prepared(val candidate: AutoCandidate, val profile: ProxyProfile, val direct: DirectCompatProfile)

    fun networkKey(snapshot: UnderlyingNetworkSnapshot): String = snapshot.fingerprint.learningKey()

    fun healthy(snapshot: UnderlyingNetworkSnapshot, now: Long = System.currentTimeMillis()): List<AutoRankedEntry> =
        AutoRanking.healthy(store.ranking(networkKey(snapshot)), now)

    fun recordFailure(snapshot: UnderlyingNetworkSnapshot, uri: String) =
        store.update(networkKey(snapshot)) { AutoRanking.markFailure(it, uri) }

    fun recordSuccess(snapshot: UnderlyingNetworkSnapshot, uri: String) =
        store.update(networkKey(snapshot)) { AutoRanking.markSuccess(it, uri) }

    /** The concrete profile Auto connects with; ECH links get a freshly fetched key. */
    suspend fun profileFor(
        uri: String,
        snapshot: UnderlyingNetworkSnapshot,
        systemDns: List<InetAddress>,
    ): ProxyProfile? {
        val runtimeUri = withEchKey(uri, snapshot, systemDns, HashMap())
        return runCatching {
            ProfileUriParser.parse(
                runtimeUri,
                id = ProxyProfile.AUTO_RUN_PREFIX + Integer.toHexString(uri.hashCode()),
                nameOverride = "Auto · " + PatternihaList.displayName(uri),
            )
        }.getOrNull()
    }

    suspend fun bootstrap(
        snapshot: UnderlyingNetworkSnapshot,
        settings: AdvancedSettingsData,
        systemDns: List<InetAddress>,
    ): List<AutoRankedEntry> {
        val fingerprint = snapshot.fingerprint
        val key = networkKey(snapshot)

        // 1. Operator + DPI type
        val carrier = fingerprint.carrierClass.ifBlank { "unknown" }
        AutoBootstrapStore.stage(AutoStage.OPERATOR, AutoStageState.RUNNING, carrier)
        XrayLiveFlow.done(XrayFlowStageId.OPERATOR, carrier, listOf("IPv6" to fingerprint.hasIpv6.toString()))
        val cachedDpi = store.dpi(key)
        XrayLiveFlow.start(XrayFlowStageId.DPI, if (cachedDpi != null) "از حافظه" else "تست SNI و IP")
        val dpi = cachedDpi ?: run {
            val counts = DpiProbe(snapshot.network).run()
            val decision = DpiDecision.classify(counts.reachable, counts.fragOk, carrier)
            log("AUTO DPI reachable=${counts.reachable} fragOk=${counts.fragOk} carrier=$carrier -> ${decision.dpi} (${decision.confidence}, ${decision.by})")
            if (decision.dpi != DpiType.UNKNOWN) store.saveDpi(key, decision.dpi)
            decision.dpi
        }
        AutoBootstrapStore.stage(AutoStage.OPERATOR, AutoStageState.DONE, "$carrier · ${dpiLabel(dpi)}")
        XrayLiveFlow.done(XrayFlowStageId.DPI, dpiLabel(dpi), listOf("DPI" to dpi.name, "cache" to (cachedDpi != null).toString()))
        XrayLiveFlow.start(XrayFlowStageId.LIST, "GitHub → jsDelivr → API")
        coroutineContext.ensureActive()

        // 2. List
        AutoBootstrapStore.stage(AutoStage.LIST, AutoStageState.RUNNING)
        val list = PatternihaFetcher(store, snapshot.network).load()
        val parsed = list?.let { PatternihaList.parse(it.text) }.orEmpty()
        if (parsed.isEmpty()) {
            AutoBootstrapStore.fail("لیست کانفیگ دریافت نشد (GitHub/jsDelivr)", AutoStage.LIST)
            XrayLiveFlow.fail(XrayFlowStageId.LIST, "GitHub / jsDelivr / API در دسترس نبود")
            return emptyList()
        }
        AutoBootstrapStore.stage(AutoStage.LIST, AutoStageState.DONE, "${parsed.size} · ${list?.source}")
        XrayLiveFlow.done(
            XrayFlowStageId.LIST,
            "${parsed.size} کانفیگ",
            listOf(
                "source" to (list?.source ?: "-"),
                "ECH" to parsed.count { it.variant == AutoVariant.ECH }.toString(),
                "fm+cs" to parsed.count { it.variant == AutoVariant.FM }.toString(),
            ),
        )
        XrayLiveFlow.start(XrayFlowStageId.CLEAN_IP)
        coroutineContext.ensureActive()

        // 3. Clean IP copies for non-ECH Cloudflare configs; ECH keeps its own address.
        AutoBootstrapStore.stage(AutoStage.CLEAN_IP, AutoStageState.RUNNING)
        val cleanIps = buildList {
            edgePoolStore.champion(fingerprint, ProxyProfile.AUTO_ID)?.address?.let(::add)
            edgePoolStore.pool(fingerprint, ProxyProfile.AUTO_ID)?.map(MciEdge::address)?.let(::addAll)
            addAll(DEFAULT_CLEAN_IPS)
        }.filter { !it.contains(':') }.distinct().take(CLEAN_IP_ALTERNATES)
        val base = PatternihaList.order(parsed, dpi, fingerprint.hasIpv6, MAX_CANDIDATES)
        val copies = base.filter { it.variant == AutoVariant.FM }.take(CLEAN_IP_SOURCES).flatMap { c ->
            cleanIps.filter { it != c.address }.take(2).map { ip ->
                c.copy(uri = PatternihaList.withAddress(c.uri, ip), address = ip, cleanIp = true)
            }
        }
        val ordered = PatternihaList.order(parsed + copies, dpi, fingerprint.hasIpv6, MAX_CANDIDATES)
        AutoBootstrapStore.stage(AutoStage.CLEAN_IP, AutoStageState.DONE, "${cleanIps.size} IP · ${copies.size}")
        XrayLiveFlow.done(XrayFlowStageId.CLEAN_IP, "${cleanIps.size} IP · ${copies.size} نسخه", listOf("IPs" to cleanIps.take(4).joinToString(", ")))
        XrayLiveFlow.start(XrayFlowStageId.ECH, "UDP type-65 → DoH → cache")

        // 4. Batch real-delay test
        AutoBootstrapStore.stage(AutoStage.TEST, AutoStageState.RUNNING, "0/${ordered.size}")
        val echCache = HashMap<String, String>()
        val prepared = ordered.mapNotNull { c ->
            val testUri = withEchKey(c.uri, snapshot, systemDns, echCache)
            runCatching {
                val profile = ProfileUriParser.parse(testUri)
                val direct = DirectCompatProfileParser.parse(profile) ?: return@runCatching null
                Prepared(c, profile, direct)
            }.getOrNull()
        }
        val total = prepared.size
        XrayLiveFlow.done(XrayFlowStageId.ECH, "${echCache.size} کلید", listOf("domains" to echCache.keys.take(3).joinToString(", ")))
        XrayLiveFlow.start(XrayFlowStageId.BATCH, "0/$total", listOf("batch" to "$BATCH_SIZE", "probe" to "gstatic 204"))
        XrayLiveFlow.progress(0, total, "0/$total")
        val tested = AtomicInteger(0)
        val ok = ArrayList<Pair<AutoCandidate, Long>>()
        for (batch in prepared.chunked(BATCH_SIZE).take(MAX_BATCHES)) {
            coroutineContext.ensureActive()
            ok += testBatch(batch, settings, depth = 0) {
                val done = tested.incrementAndGet()
                ConnectionStateStore.updateConnectRouteProgress(done, total)
                AutoBootstrapStore.stage(AutoStage.TEST, AutoStageState.RUNNING, "$done/$total")
                XrayLiveFlow.progress(done, total, "$done/$total")
            }
            AutoBootstrapStore.stage(AutoStage.TEST, AutoStageState.RUNNING, "${tested.get()}/$total · ${ok.size} OK")
            XrayLiveFlow.start(XrayFlowStageId.BATCH, "${tested.get()}/$total · ${ok.size} OK")
            if (ok.size >= ENOUGH_WORKING) break
        }
        if (ok.isEmpty()) {
            AutoBootstrapStore.fail("هیچ کانفیگ سالمی پیدا نشد", AutoStage.TEST)
            XrayLiveFlow.fail(XrayFlowStageId.BATCH, "هیچ کانفیگ سالمی پیدا نشد")
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val fresh = ok.sortedBy { it.second }.map { (c, delay) -> AutoRankedEntry(c.uri, delay, now) }
        val merged = AutoRanking.merge(store.ranking(key), fresh, now)
        store.saveRanking(key, merged)
        ok.minByOrNull { it.second }?.first?.takeIf { it.cleanIp }?.let { best ->
            edgePoolStore.saveChampion(fingerprint, ProxyProfile.AUTO_ID, MciEdge(best.address, 443, "auto-clean", 1))
        }
        AutoBootstrapStore.stage(AutoStage.TEST, AutoStageState.DONE, "${ok.size} OK · ${fresh.first().delayMs} ms")
        XrayLiveFlow.done(XrayFlowStageId.BATCH, "${tested.get()}/$total · ${ok.size} OK")
        XrayLiveFlow.done(
            XrayFlowStageId.RANKING,
            "${merged.size} کانفیگ · بهترین ${fresh.first().delayMs} ms",
            fresh.take(3).mapIndexed { i, e -> "#${i + 1}" to "${e.delayMs} ms · ${PatternihaList.displayName(e.uri)}" },
        )
        log("AUTO ranked ${merged.size} configs for network; best=${fresh.first().delayMs}ms ${PatternihaList.displayName(fresh.first().uri)}")
        return merged
    }

    /**
     * After a connect: a ~256 KB download through the top cached configs in a separate batch core
     * (the active tunnel keeps its own core), so the next connect ranks by delay and real speed.
     * At most once per network per [SPEED_INTERVAL_MS]; [stillWanted] stops it on disconnect or
     * screen off.
     */
    suspend fun measureSpeeds(
        snapshot: UnderlyingNetworkSnapshot,
        settings: AdvancedSettingsData,
        systemDns: List<InetAddress>,
        stillWanted: () -> Boolean,
    ) {
        val key = networkKey(snapshot)
        val now = System.currentTimeMillis()
        if (now - store.speedTestedAt(key) in 0 until SPEED_INTERVAL_MS) return
        val top = healthy(snapshot, now).take(SPEED_CONFIGS)
        if (top.isEmpty() || !stillWanted()) return
        store.markSpeedTested(key, now)
        val prepared = top.mapNotNull { e ->
            val uri = withEchKey(e.uri, snapshot, systemDns, HashMap())
            runCatching {
                val profile = ProfileUriParser.parse(uri)
                val direct = DirectCompatProfileParser.parse(profile) ?: return@runCatching null
                e.uri to Pair(profile, direct)
            }.getOrNull()
        }
        if (prepared.isEmpty()) return
        val ports = prepared.map { ServerSocket(0).use { s -> s.localPort } }
        val routes = prepared.mapIndexed { i, (_, pd) ->
            MciXrayBatchRoute(
                tag = "speed$i",
                edge = MciEdge(pd.second.address, pd.second.port, "auto", 1),
                settings = settings.copy(
                    connectionMode = CONNECTION_MODE_PROXY,
                    socksAddress = "127.0.0.1",
                    socksPort = ports[i],
                    socksUdp = false,
                ).validated(),
                runtimeOptions = MciXrayRuntimeOptions(
                    identityOverride = pd.second.identity,
                    finalmaskEnabled = false,
                    preserveEmptyAlpn = true,
                    preserveTransportFields = true,
                    quietLogging = true,
                ),
            )
        }
        val core = MciXrayCore(appContext)
        val speeds = HashMap<String, Long>()
        try {
            core.startBatch(routes, prepared.first().second.first)
            // One at a time: low impact on the live tunnel, and each sample is not shared.
            for ((i, entry) in prepared.withIndex()) {
                coroutineContext.ensureActive()
                if (!stillWanted()) break
                val kbps = runCatching { download(ports[i]) }.getOrNull() ?: continue
                speeds[entry.first] = kbps
                log("AUTO speed ${PatternihaList.displayName(entry.first)}: $kbps kbit/s")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("AUTO speed test skipped: ${e.message}")
        } finally {
            runCatching { core.stop() }
        }
        if (speeds.isNotEmpty()) store.update(key) { AutoRanking.withSpeed(it, speeds) }
    }

    /** kbit/s of a [SPEED_BYTES] HTTPS download through the SOCKS port. */
    private fun download(port: Int): Long? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        Socket(proxy).use { raw ->
            raw.soTimeout = SPEED_TIMEOUT_MS
            raw.connect(InetSocketAddress.createUnresolved(SPEED_HOST, 443), SPEED_TIMEOUT_MS)
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, SPEED_HOST, 443, true) as SSLSocket
            tls.use {
                it.startHandshake()
                it.outputStream.apply {
                    write("GET /__down?bytes=$SPEED_BYTES HTTP/1.1\r\nHost: $SPEED_HOST\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val input = it.inputStream
                if (!statusLine(input).contains(" 200")) return null
                val started = SystemClock.elapsedRealtime()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (total < SPEED_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
                val ms = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                if (total < SPEED_BYTES / 2) return null
                return total * 8 / ms
            }
        }
    }

    private suspend fun withEchKey(
        uri: String,
        snapshot: UnderlyingNetworkSnapshot,
        systemDns: List<InetAddress>,
        cache: MutableMap<String, String>,
    ): String {
        val ech = PatternihaList.echValue(uri)
        if (ech.isBlank() || EchDns.isBase64List(ech)) return uri
        val key = cache.getOrPut(ech) {
            EchKeyResolver(store, snapshot.network, systemDns).resolve(ech).value
        }
        return if (key.isNotBlank() && key != ech) PatternihaList.withEchKey(uri, key) else uri
    }

    private suspend fun testBatch(
        batch: List<Prepared>,
        settings: AdvancedSettingsData,
        depth: Int,
        onTested: () -> Unit,
    ): List<Pair<AutoCandidate, Long>> {
        if (batch.isEmpty()) return emptyList()
        val ports = batch.map { ServerSocket(0).use { s -> s.localPort } }
        val routes = batch.mapIndexed { i, p ->
            MciXrayBatchRoute(
                tag = "auto$i",
                edge = MciEdge(p.direct.address, p.direct.port, "auto", 1),
                settings = settings.copy(
                    connectionMode = CONNECTION_MODE_PROXY,
                    socksAddress = "127.0.0.1",
                    socksPort = ports[i],
                    socksUdp = false,
                ).validated(),
                runtimeOptions = MciXrayRuntimeOptions(
                    identityOverride = p.direct.identity,
                    finalmaskEnabled = false,
                    preserveEmptyAlpn = true,
                    preserveTransportFields = true,
                    quietLogging = true,
                ),
            )
        }
        val core = MciXrayCore(appContext)
        val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val started = runCatching { core.startBatch(routes, batch.first().profile, logLevel = BATCH_LOG_LEVEL) }
            if (started.isFailure) {
                log("AUTO batch of ${batch.size} failed to start: ${started.exceptionOrNull()?.message}")
                runCatching { core.stop() }
                if (depth >= 2 || batch.size <= 2) {
                    batch.forEach { onTested() }
                    return emptyList()
                }
                val half = batch.size / 2
                return testBatch(batch.take(half), settings, depth + 1, onTested) +
                    testBatch(batch.drop(half), settings, depth + 1, onTested)
            }
            val results = ConcurrentHashMap<Int, Long>()
            val jobs = batch.indices.map { i ->
                probeScope.launch {
                    val r = runCatching { realDelay(ports[i]) }
                    r.getOrNull()?.let { results[i] = it }
                        ?: log("AUTO probe auto$i ${batch[i].candidate.variant} ${PatternihaList.displayName(batch[i].candidate.uri)} failed: ${r.exceptionOrNull()?.let { it::class.java.simpleName + " " + it.message } ?: "bad status"}")
                    onTested()
                }
            }
            withTimeoutOrNull(TEST_BUDGET_MS) { jobs.joinAll() }
            log("AUTO batch ${batch.size}: ${results.size} OK")
            return results.map { (i, delay) -> batch[i].candidate to delay }
        } finally {
            probeScope.cancel()
            runCatching { core.stop() }
        }
    }

    /** gstatic generate_204 through the route (its time is the real delay), then one HTTPS page. */
    private fun realDelay(port: Int): Long? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val started = SystemClock.elapsedRealtime()
        Socket(proxy).use { s ->
            s.soTimeout = PROBE_TIMEOUT_MS
            s.connect(InetSocketAddress.createUnresolved("www.gstatic.com", 80), PROBE_TIMEOUT_MS)
            s.getOutputStream().apply {
                write("GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            if (!statusLine(s.getInputStream()).contains(" 204")) return null
        }
        val delay = SystemClock.elapsedRealtime() - started
        // The 204 through the tunnel is the pass criterion; the HTTPS page is a best-effort extra
        // that only penalises the delay when it fails (it used to reject working routes).
        val pageOk = runCatching { pageCheck(proxy) }.getOrDefault(false)
        return if (pageOk) delay else delay + PAGE_PENALTY_MS
    }

    private fun pageCheck(proxy: Proxy): Boolean {
        Socket(proxy).use { raw ->
            raw.soTimeout = PAGE_TIMEOUT_MS
            raw.connect(InetSocketAddress.createUnresolved(PAGE_HOST, 443), PAGE_TIMEOUT_MS)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, PAGE_HOST, 443, true) as SSLSocket
            ssl.use { tls ->
                tls.startHandshake()
                tls.outputStream.apply {
                    write("GET /robots.txt HTTP/1.1\r\nHost: $PAGE_HOST\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val status = statusLine(tls.inputStream)
                return Regex("^HTTP/\\d(?:\\.\\d)? [23]\\d\\d").containsMatchIn(status)
            }
        }
    }

    private fun statusLine(input: InputStream): String {
        val out = StringBuilder()
        while (out.length < 256) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            out.append(b.toChar())
        }
        return out.toString()
    }

    private fun dpiLabel(dpi: DpiType) = when (dpi) {
        DpiType.SNI -> "SNI"
        DpiType.IP -> "IP"
        DpiType.UNKNOWN -> "?"
    }

    private fun log(message: String) = runCatching {
        AppLogRepository.info(LogSource.ADAPTIVE, message)
        android.util.Log.i("UAC-SNI", message)
    }

    companion object {
        const val BATCH_SIZE = 24
        const val MAX_BATCHES = 2
        const val MAX_CANDIDATES = 48
        const val ENOUGH_WORKING = 3
        const val TEST_BUDGET_MS = 9_000L
        const val PROBE_TIMEOUT_MS = 7_000
        const val PAGE_PENALTY_MS = 1_500L
        const val PAGE_TIMEOUT_MS = 2_000
        const val SPEED_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val SPEED_CONFIGS = 3
        const val SPEED_BYTES = 262_144L
        const val SPEED_TIMEOUT_MS = 12_000
        private const val SPEED_HOST = "speed.cloudflare.com"
        /** Set to "debug" to see each batch outbound error in logcat. */
        const val BATCH_LOG_LEVEL = "warning"
        const val CLEAN_IP_SOURCES = 6
        const val CLEAN_IP_ALTERNATES = 3
        private const val PAGE_HOST = "www.google.com"
        val DEFAULT_CLEAN_IPS = listOf("104.26.13.100", "104.26.14.40", "104.18.8.83")
    }
}