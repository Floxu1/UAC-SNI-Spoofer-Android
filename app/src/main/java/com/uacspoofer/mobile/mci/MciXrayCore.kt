package com.uacspoofer.mobile.mci

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class MciXrayCore(
    private val context: Context,
) {
    private val lifecycleMutex = Mutex()
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null
    private var logJob: Job? = null

    suspend fun start(
        edge: MciEdge,
        settings: AdvancedSettingsData = AdvancedSettingsData.DEFAULT,
        profile: ProxyProfile = ProxyProfile.UAC_SNI_BUILT_IN,
        runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
    ): XrayStartupTiming = lifecycleMutex.withLock {
        stopLocked()
        startLocked(
            label = "${edge.role} route",
            configName = "xray-mci-${settings.socksPort}.json",
            configText = buildConfig(edge, settings, profile, runtimeOptions),
            listeners = listOf(settings.socksAddress to settings.socksPort),
        )
    }

    suspend fun startBatch(
        routes: List<MciXrayBatchRoute>,
        profile: ProxyProfile,
    ): XrayStartupTiming = lifecycleMutex.withLock {
        stopLocked()
        require(routes.isNotEmpty()) { "Batch route list is empty" }
        startLocked(
            label = "route screening batch (${routes.size})",
            configName = "xray-route-batch-${routes.first().settings.socksPort}-${routes.size}.json",
            configText = MciXrayConfigBuilder.buildBatch(routes, profile),
            listeners = routes.map { it.settings.socksAddress to it.settings.socksPort },
        )
    }

    private suspend fun startLocked(
        label: String,
        configName: String,
        configText: String,
        listeners: List<Pair<String, Int>>,
    ): XrayStartupTiming {
        AppLogRepository.info(LogSource.XRAY, "Starting core for $label")
        val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.isFile && binary.length() > 0L) {
            "Xray binary missing for ${Build.SUPPORTED_ABIS.joinToString()}"
        }
        check(binary.canExecute() || binary.setExecutable(true, false)) {
            "Xray binary is not executable"
        }

        val configStarted = SystemClock.elapsedRealtime()
        val config = File(context.filesDir, configName).apply {
            writeText(configText, Charsets.UTF_8)
        }
        val configPrepareMs = SystemClock.elapsedRealtime() - configStarted
        val coreStarted = SystemClock.elapsedRealtime()
        val started = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-config",
            config.absolutePath,
        ).directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
        val coreStartupMs = SystemClock.elapsedRealtime() - coreStarted
        process = started
        logJob = logScope.launch {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Log.i(TAG, "XRAY $line")
                        AppLogRepository.info(LogSource.XRAY, line)
                    }
                }
            }.onFailure { AppLogRepository.warning(LogSource.XRAY, "Log stream ended", it) }
        }

        val readinessStarted = SystemClock.elapsedRealtime()
        return try {
            withTimeout(XRAY_START_TIMEOUT_MS) {
                while (true) {
                    check(isAlive(started)) { "Xray exited with code ${started.exitValue()}" }
                    if (listeners.all { (address, port) -> isSocksReady(address, port) }) break
                    delay(READY_POLL_MS)
                }
            }
            val ports = listeners.joinToString(",") { it.second.toString() }
            Log.i(TAG, "Xray SOCKS ready on $ports")
            AppLogRepository.info(LogSource.XRAY, "SOCKS listeners ready on $ports")
            XrayStartupTiming(
                configPrepareMs = configPrepareMs,
                coreStartupMs = coreStartupMs,
                proxyReadyMs = SystemClock.elapsedRealtime() - readinessStarted,
            )
        } catch (error: Throwable) {
            AppLogRepository.error(LogSource.XRAY, "Core startup failed", error)
            stopLocked()
            throw error
        }
    }

    suspend fun stop() = withContext(NonCancellable) {
        lifecycleMutex.withLock { stopLocked() }
    }

    fun isRunning(): Boolean = process?.let(::isAlive) == true

    private suspend fun stopLocked() {
        withContext(NonCancellable) {
            val current = process
            var exited = current == null
            try {
                if (current != null) {
                    runCatching { current.destroy() }
                    exited = waitForExit(current, XRAY_GRACEFUL_STOP_MS)
                    if (!exited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runCatching { current.destroyForcibly() }
                    } else if (!exited) {
                        
                        
                        runCatching { current.destroy() }
                    }
                    if (!exited) exited = waitForExit(current, XRAY_FORCE_STOP_MS)
                    if (exited && process === current) process = null
                }
            } finally {
                if (current != null && !exited) {
                    
                    
                    runCatching { current.inputStream.close() }
                    runCatching { current.errorStream.close() }
                    runCatching { current.outputStream.close() }
                }
                val currentLogJob = logJob
                logJob = null
                currentLogJob?.cancelAndJoin()
            }
            if (current != null && exited) {
                AppLogRepository.info(LogSource.XRAY, "Core stopped")
            }
            check(exited) { "Xray process did not exit during teardown" }
        }
    }

    private suspend fun waitForExit(candidate: Process, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (isAlive(candidate)) delay(25L)
            true
        } == true

    private fun isSocksReady(address: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(address, port),
                120,
            )
        }
        true
    }.getOrDefault(false)

    private fun isAlive(candidate: Process): Boolean = try {
        candidate.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    companion object {
        private const val TAG = "UAC-SNI"
        private const val XRAY_START_TIMEOUT_MS = 5_000L
        private const val XRAY_GRACEFUL_STOP_MS = 1_000L
        private const val XRAY_FORCE_STOP_MS = 2_000L
        private const val READY_POLL_MS = 50L
        internal fun buildConfig(
            edge: MciEdge = MciConfig.PRIMARY_EDGE,
            settings: AdvancedSettingsData = AdvancedSettingsData.DEFAULT,
            profile: ProxyProfile = ProxyProfile.UAC_SNI_BUILT_IN,
            runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
        ): String = MciXrayConfigBuilder.build(
            edge,
            settings,
            profile,
            nativeTun = false,
            runtimeOptions = runtimeOptions,
        )
    }
}

data class XrayStartupTiming(
    val configPrepareMs: Long,
    val coreStartupMs: Long,
    val proxyReadyMs: Long,
)
