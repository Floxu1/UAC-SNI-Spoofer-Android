package com.uacspoofer.mobile.engine.tor

import android.content.Context
import android.os.Build
import android.system.Os
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.torproject.jni.TorService
import kotlin.coroutines.coroutineContext

class TorDaemon(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var process: Process? = null
    @Volatile private var torThread: Thread? = null
    @Volatile private var lastControlPort: Int = TorEngineSettings.CONTROL_PORT
    @Volatile private var lastControlSocket: File? = null
    private val running = AtomicBoolean(false)
    private val noticePumpRunning = AtomicBoolean(false)
    @Volatile private var noticeLines: ConcurrentLinkedQueue<String>? = null
    @Volatile private var lastSettings: TorEngineSettings = TorEngineSettings.DEFAULT
    @Volatile private var verifyNext = true
    @Volatile var lastStartWasCold: Boolean = true
        private set

    /** True when the previous start failed: run --verify-config before the next one. */
    fun requestVerify() { verifyNext = true }

    fun isRunning(): Boolean {
        if (!running.get()) return false
        if (torThread?.isAlive == true) return true
        return process?.isAlive == true
    }

    fun applyExitCountry(settings: TorEngineSettings): Boolean {
        if (!isRunning()) return false
        val validated = settings.validated()
        return TorControlClient.setExitCountry(
            lastControlPort,
            lastControlSocket,
            validated.exitCountryCode,
            validated.exitStrict,
            validated.fastExit,
        )
    }

    fun signal(name: String): Boolean =
        isRunning() && TorControlClient.signal(lastControlPort, lastControlSocket, name)

    fun setConf(assignment: String): Boolean =
        isRunning() && TorControlClient.setConf(lastControlPort, lastControlSocket, assignment)

    fun controlBigInfo(keyword: String): String? =
        if (isRunning()) TorControlClient.bigInfo(lastControlPort, lastControlSocket, keyword) else null

    fun controlInfo(keyword: String): String? =
        if (isRunning()) TorControlClient.info(lastControlPort, lastControlSocket, keyword) else null

    /** Fingerprints Tor is really using: up entry guards first, then first hops of built circuits. */
    fun usedBridgeFingerprints(): List<String> {
        val guards = controlInfo("entry-guards")?.let(TorControlClient::parseUpGuardFingerprints).orEmpty()
        val hops = controlInfo("circuit-status")?.let(TorControlClient::parseFirstHops).orEmpty()
        return (hops + guards).distinct()
    }

    /**
     * Swap bridges in the running Tor via SETCONF and wait for bootstrap again.
     * Returns false when the control port refused (caller restarts instead).
     */
    suspend fun swapBridges(
        bridges: List<WebTunnelBridge>,
        timeoutMs: Long = BRIDGE_BOOTSTRAP_TIMEOUT_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        val lines = noticeLines
        if (!isRunning() || lines == null) return@withContext false
        val torrcLines = bridges.map { it.torrcLine().removePrefix("Bridge ").trim() }
        if (!TorControlClient.setBridges(lastControlPort, lastControlSocket, torrcLines)) return@withContext false
        AppLogRepository.info(LogSource.TOR, "Swapped ${bridges.size} WebTunnel bridge(s) via control port (no restart)")
        lines.clear()
        try {
            awaitBootstrap(lastSettings, timeoutMs, lines, process, daemonAlive = { isRunning() })
            noticePumpRunning.set(false)
        } catch (error: Throwable) {
            if (error is TorPtProxyRefusedException || error is TorPtCrashLoopException) stop()
            throw error
        }
        true
    }

    fun locateTorBinary(): File? = installedOrNative("tor", "libtor.so")

    fun locateWebTunnelPlugin(): File? = installedOrNative("webtunnel", "libwebtunnel.so")

    suspend fun start(
        settings: TorEngineSettings,
        bridges: List<WebTunnelBridge>,
        timeoutMs: Long = if (bridges.isEmpty()) DIRECT_BOOTSTRAP_TIMEOUT_MS else BRIDGE_BOOTSTRAP_TIMEOUT_MS,
        fragmentProxyPort: Int = 0,
        keepOnFailure: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        stop()
        lastSettings = settings.validated()
        if (bridges.isNotEmpty()) {
            TorLiveFlow.start(TorFlowStageId.ARCH)
            try {
                checkNativeBridgeAbi()
            } catch (error: Throwable) {
                TorLiveFlow.fail(TorFlowStageId.ARCH, error.message.orEmpty())
                throw error
            }
            TorLiveFlow.done(
                TorFlowStageId.ARCH,
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                listOf("abi" to Build.SUPPORTED_ABIS.joinToString(), "libs" to File(appContext.applicationInfo.nativeLibraryDir.orEmpty()).name),
            )
        }
        val password = TorControlAuth.newPassword()
        TorControlClient.controlPassword = ""
        val runtime = TorNativeRuntime.install(appContext)
        val plugin = runtime.webtunnel.takeIf { it.isFile }
        if (bridges.isNotEmpty()) {
            check(plugin != null && plugin.isFile && plugin.length() > 0L) {
                "WebTunnel plugin is missing for ${Build.SUPPORTED_ABIS.joinToString()}"
            }
        }
        val dataDir = preparePrivateDir(File(appContext.filesDir, "tor/data"))
        // filesDir, not cacheDir: Android must not purge the consensus / microdesc cache.
        val cacheDir = preparePrivateDir(File(appContext.filesDir, "tor/cache"))
        lastStartWasCold = !File(cacheDir, "cached-microdesc-consensus").isFile &&
            !File(dataDir, "cached-microdesc-consensus").isFile
        val rcFile = File(appContext.filesDir, "tor/torrc")
        val defaultsFile = File(appContext.filesDir, "tor/torrc-defaults")
        val noticeLog = File(appContext.filesDir, "tor/notice.log")
        val controlSocket = File(dataDir, TorLaunchArgs.CONTROL_SOCKET_NAME)
        val countryPinned = settings.validated().exitCountryCode.isNotEmpty()
        val geoIp = try {
            TorGeoIp.install(appContext)
        } catch (error: Throwable) {
            if (countryPinned) throw error
            AppLogRepository.warning(
                LogSource.TOR,
                "GeoIP unavailable; Automatic Tor will continue without country pinning",
                error,
            )
            null
        }
        rcFile.parentFile?.mkdirs()
        rcFile.writeText(
            TorRcWriter.render(
                settings = settings,
                dataDirectory = dataDir,
                webtunnelPlugin = plugin,
                bridges = bridges,
                pluginExecTokens = plugin?.let { listOf(it.absolutePath) },
                noticeLog = noticeLog,
                geoIpFile = geoIp?.geoip,
                geoIp6File = geoIp?.geoip6,
                fragmentProxyPort = fragmentProxyPort,
                hashedControlPassword = TorControlAuth.hash(password),
            ),
        )
        TorControlClient.controlPassword = password
        defaultsFile.writeText("# UAC Tor defaults; SocksPort is in torrc\n")
        noticeLog.delete()
        lastControlPort = settings.validated().controlPort
        lastControlSocket = controlSocket
        TorStatusStore.update(TorPhase.STARTING, 0, "Starting Tor")
        // --verify-config spawns a second Tor and parses GeoIP: only after a failed start.
        val verifyText = if (verifyNext) {
            verifyConfig(
                runtime.tor,
                rcFile,
                defaultsFile,
                dataDir,
                cacheDir,
                controlSocket,
                timeoutSec = if (geoIp != null) 45L else 15L,
            )
        } else {
            AppLogRepository.debug(LogSource.TOR, "Skipping torrc --verify-config (last start was clean)")
            ""
        }
        if (verifyText.isNotBlank()) {
            verifyText.lineSequence().filter { it.isNotBlank() }.take(12).forEach { line ->
                AppLogRepository.debug(LogSource.TOR, line.take(400))
            }
        }
        val logLines = startNoticePump(noticeLog)
        noticeLines = logLines
        // An in-process Tor that already watched a PT die cannot run again in this process:
        // tor's transports.c keeps static counters and aborts the whole app
        // (assert_unconfigured_count_ok). Use a separate tor process instead.
        val launchedJni = if (jniTainted) {
            AppLogRepository.warning(LogSource.TOR, "In-process Tor is unsafe after a PT crash; using a separate Tor process")
            false
        } else tryLaunchJni(
            settings = settings,
            torrc = rcFile,
            defaultsTorrc = defaultsFile,
            dataDir = dataDir,
            cacheDir = cacheDir,
            controlSocket = controlSocket,
            noticeLog = noticeLog,
            plugin = plugin,
            bridges = bridges.size,
        )
        if (!launchedJni) {
            TorControlClient.signalShutdown(lastControlPort, lastControlSocket)
            torThread?.let { leftover ->
                runCatching { leftover.join(1_000) }
                torThread = null
            }
            startExec(settings, rcFile, dataDir, cacheDir, plugin, bridges.size)
        }
        try {
            awaitBootstrap(
                settings = settings,
                timeoutMs = timeoutMs,
                logLines = logLines,
                processStdout = process,
                daemonAlive = { isRunning() },
            )
            verifyNext = false
            // Bootstrapped: stop the 120 ms notice.log poll; control port serves later checks.
            noticePumpRunning.set(false)
        } catch (error: Throwable) {
            verifyNext = true
            if (!keepOnFailure || error is TorPtProxyRefusedException || error is TorPtCrashLoopException || !isRunning()) stop()
            throw error
        }
    }

    fun stop() {
        running.set(false)
        noticePumpRunning.set(false)
        noticeLines = null
        val thread = torThread
        val current = process
        process = null
        torThread = null
        if (current != null || thread?.isAlive == true) {
            TorControlClient.signalShutdown(lastControlPort, lastControlSocket)
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(4_000) }
            if (thread.isAlive) {
                AppLogRepository.warning(LogSource.TOR, "Tor JNI thread still running after shutdown")
            }
        }
        if (current != null) {
            current.destroy()
            runCatching { current.waitFor() }
            if (current.isAlive) current.destroyForcibly()
        }
        if (current != null || thread != null) {
            AppLogRepository.info(LogSource.TOR, "Tor process stopped")
        }
        TorStatusStore.reset()
    }

    private fun tryLaunchJni(
        settings: TorEngineSettings,
        torrc: File,
        defaultsTorrc: File,
        dataDir: File,
        cacheDir: File,
        controlSocket: File,
        noticeLog: File,
        plugin: File?,
        bridges: Int,
    ): Boolean {
        val runArgs = TorLaunchArgs.commandLine(
            torrc = torrc,
            defaultsTorrc = defaultsTorrc,
            dataDir = dataDir,
            cacheDir = cacheDir,
            controlSocket = controlSocket,
        )
        AppLogRepository.info(
            LogSource.TOR,
            "Tor JNI argv ${runArgs.joinToString(" ")}",
        )
        val ready = CountDownLatch(1)
        val launchError = AtomicReference<Throwable?>(null)
        val thread = Thread({
            val native = try {
                TorService()
            } catch (error: Throwable) {
                launchError.set(error)
                ready.countDown()
                return@Thread
            }
            try {
                if (!native.createTorConfiguration()) {
                    error("createTorConfiguration failed")
                }
                if (!native.mainConfigurationSetCommandLine(runArgs)) {
                    error("Setting Tor command line failed")
                }
                if (!native.mainConfigurationSetupControlSocket()) {
                    error("Setting up Tor ControlSocket failed")
                }
                running.set(true)
                ready.countDown()
                val code = native.runMain()
                AppLogRepository.info(LogSource.TOR, "Tor runMain exited code=$code ${noticeTail(noticeLog)}")
                if (code != 0) {
                    launchError.compareAndSet(
                        null,
                        IllegalStateException("Tor runMain exited code=$code ${noticeTail(noticeLog)}"),
                    )
                }
            } catch (error: Throwable) {
                launchError.compareAndSet(null, error)
                ready.countDown()
                AppLogRepository.warning(
                    LogSource.TOR,
                    "Tor JNI: ${error.message.orEmpty()} ${noticeTail(noticeLog)}",
                )
            } finally {
                running.set(false)
                runCatching { native.mainConfigurationFree() }
            }
        }, "tor-main")
        torThread = thread
        thread.start()
        if (!ready.await(20, TimeUnit.SECONDS)) {
            AppLogRepository.warning(LogSource.TOR, "JNI Tor did not finish setup in 20s")
            return false
        }
        val error = launchError.get()
        if (error is UnsatisfiedLinkError || error is NoSuchFieldError) {
            AppLogRepository.warning(
                LogSource.TOR,
                "JNI Tor unavailable (${error.javaClass.simpleName}), falling back to exec",
            )
            torThread = null
            return false
        }
        if (error != null) {
            torThread = null
            throw error
        }
        if (!thread.isAlive) {
            val detail = noticeTail(noticeLog)
            AppLogRepository.warning(
                LogSource.TOR,
                "JNI Tor thread exited immediately ${detail.ifBlank { "notice.log empty" }}",
            )
            torThread = null
            running.set(false)
            throw IllegalStateException(
                "Tor JNI exited immediately ${detail.ifBlank { "see logcat tag UacTor" }}",
            )
        }
        AppLogRepository.info(
            LogSource.TOR,
            "Tor JNI started socks=127.0.0.1:${settings.socksPort} bridges=$bridges " +
                "plugin=${plugin?.absolutePath ?: "none"}",
        )
        return true
    }

    private fun startExec(
        settings: TorEngineSettings,
        rcFile: File,
        dataDir: File,
        cacheDir: File,
        plugin: File?,
        bridges: Int,
    ) {
        val args = listOf(
            "-f", rcFile.absolutePath,
            "--RunAsDaemon", "0",
            "--ignore-missing-torrc",
            "--DataDirectory", dataDir.absolutePath,
            "--CacheDirectory", cacheDir.absolutePath,
            "--TruncateLogFile", "1",
        )
        val tor = locateTorBinary() ?: error("libtor.so is missing")
        AppLogRepository.info(
            LogSource.TOR,
            "Launching Tor exec ${tor.absolutePath} ${args.joinToString(" ")}",
        )
        val started = startProcess(tor, args, dataDir)
        process = started
        running.set(true)
        AppLogRepository.info(
            LogSource.TOR,
            "Tor process started socks=127.0.0.1:${settings.socksPort} bridges=$bridges " +
                "plugin=${plugin?.absolutePath ?: "none"}",
        )
    }

    private fun verifyConfig(
        tor: File,
        torrc: File,
        defaultsTorrc: File,
        dataDir: File,
        cacheDir: File,
        controlSocket: File,
        timeoutSec: Long = 15L,
    ): String {
        val args = TorLaunchArgs.commandLine(
            torrc = torrc,
            defaultsTorrc = defaultsTorrc,
            dataDir = dataDir,
            cacheDir = cacheDir,
            controlSocket = controlSocket,
            verifyConfig = true,
        )
        val argv = listOf(tor.absolutePath) + args.drop(1)
        AppLogRepository.info(LogSource.TOR, "Verifying torrc")
        val started = processBuilder(argv, dataDir).start()
        val output = started.inputStream.bufferedReader().readText()
        val finished = started.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            started.destroyForcibly()
            error("torrc --verify-config timed out")
        }
        val code = started.exitValue()
        AppLogRepository.info(LogSource.TOR, "torrc verify exit=$code")
        if (code != 0) {
            error(
                output.lineSequence().firstOrNull { it.contains("[warn]") || it.contains("[err]") }
                    ?: output.ifBlank { "torrc --verify-config exited $code" }.take(400),
            )
        }
        return output
    }

    private fun startProcess(binary: File, extraArgs: List<String>, directory: File): Process {
        val attempts = listOfNotNull(
            TorExec.argv(binary, extraArgs),
            TorExec.detectLinker()?.let { linker -> TorExec.argv(binary, extraArgs, linker = linker) },
        ).distinct()
        var lastError: Throwable? = null
        for (argv in attempts) {
            val started = runCatching { processBuilder(argv, directory).start() }
            if (started.isSuccess) {
                AppLogRepository.info(LogSource.TOR, "Exec ${argv.joinToString(" ")}")
                return started.getOrThrow()
            }
            lastError = started.exceptionOrNull()
            AppLogRepository.warning(
                LogSource.TOR,
                "Exec failed ${argv.joinToString(" ")}: ${lastError?.message.orEmpty()}",
            )
        }
        throw lastError ?: IllegalStateException("Could not start ${binary.absolutePath}")
    }

    private fun processBuilder(argv: List<String>, directory: File): ProcessBuilder =
        ProcessBuilder(*argv.toTypedArray())
            .directory(directory)
            .redirectErrorStream(true)
            .also { builder ->
                val env = builder.environment()
                env["LD_LIBRARY_PATH"] = appContext.applicationInfo.nativeLibraryDir
                env["HOME"] = appContext.filesDir.absolutePath
                env["TMPDIR"] = appContext.cacheDir.absolutePath
            }

    private fun startNoticePump(file: File): ConcurrentLinkedQueue<String> {
        val lines = ConcurrentLinkedQueue<String>()
        noticePumpRunning.set(true)
        Thread({
            var position = 0L
            while (noticePumpRunning.get()) {
                runCatching {
                    if (file.isFile) {
                        RandomAccessFile(file, "r").use { raf ->
                            val length = raf.length()
                            if (length < position) position = 0L
                            raf.seek(position)
                            while (true) {
                                val line = raf.readLine() ?: break
                                lines.add(line)
                            }
                            position = raf.filePointer
                        }
                    }
                }
                Thread.sleep(120)
            }
        }, "tor-notice").apply {
            isDaemon = true
            start()
        }
        return lines
    }

    private suspend fun awaitBootstrap(
        settings: TorEngineSettings,
        timeoutMs: Long,
        logLines: ConcurrentLinkedQueue<String>,
        processStdout: Process?,
        daemonAlive: () -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                val stdout = processStdout?.let { BufferedReader(InputStreamReader(it.inputStream)) }
                val recent = ArrayDeque<String>()
                var lastPercent = 0
                var lastChangeAt = System.currentTimeMillis()
                var lastProblem = ""
                var reachedHandshake = false
                var controlWatch: TorControlWatch? = null
                val startedAt = System.currentTimeMillis()
                val coldStart = lastStartWasCold
                val ptCrashes = PtCrashCounter()
                var ptDeaths = 0
                TorLiveFlow.newAttempt()
                TorLiveFlow.start(TorFlowStageId.PT, "webtunnel", listOf("cache" to if (coldStart) "cold" else "warm"))
                TorLiveFlow.fact(TorFlowStageId.DIRECTORY, "cache", if (coldStart) "cold (download)" else "warm (on disk)")
                try {
                    while (true) {
                    coroutineContext.ensureActive()
                    var line = logLines.poll()
                    if (line == null && stdout?.ready() == true) {
                        line = stdout.readLine()
                    }
                    while (line != null) {
                        if (line.isNotBlank()) {
                            AppLogRepository.debug(LogSource.TOR, line.take(400))
                            recent.addLast(line.take(240))
                            while (recent.size > 24) recent.removeFirst()
                        }
                        if (line.contains("helper program for dynamic executables", ignoreCase = true)) {
                            error("Android linker was started instead of Tor. Direct exec of libtor.so is required.")
                        }
                        if (PtCrashCounter.isPtTermination(line) && torThread != null) jniTainted = true
                        if (PtCrashCounter.isPtTermination(line)) {
                            ptDeaths++
                            TorLiveFlow.fact(TorFlowStageId.PT, "terminations", ptDeaths.toString())
                        }
                        if (ptCrashes.record(line, System.currentTimeMillis()) && !reachedHandshake) {
                            throw TorPtCrashLoopException(
                                "WebTunnel plugin keeps crashing (${PtCrashCounter.LIMIT}x in " +
                                    "${PtCrashCounter.WINDOW_MS / 1000}s): ${sanitize(line)}",
                            )
                        }
                        if (TorBootstrapWatch.isProxyRefusal(line)) {
                            throw TorPtProxyRefusedException(sanitize(line))
                        }
                        if (isFatalLine(line)) {
                            lastProblem = sanitize(line)
                            AppLogRepository.warning(LogSource.TOR, lastProblem)
                        }
                        if (TorBootstrapWatch.isProgressLine(line)) {
                            lastChangeAt = System.currentTimeMillis()
                        }
                        val percent = bootstrapPercent(line)
                        if (percent != null && percent != lastPercent) {
                            lastPercent = percent
                            lastChangeAt = System.currentTimeMillis()
                            if (TorBootstrapWatch.marksHandshake(percent)) reachedHandshake = true
                            TorLiveFlow.onBootstrap(percent, TorLiveFlow.phaseOf(line))
                            TorStatusStore.update(
                                phase = if (percent >= 100) TorPhase.CONNECTED else TorPhase.BOOTSTRAPPING,
                                bootstrapPercent = percent,
                                detail = if (percent >= 100) {
                                    "Tor circuit is ready · SOCKS 127.0.0.1:${settings.socksPort}"
                                } else {
                                    "Bootstrapping Tor $percent%"
                                },
                            )
                            if (percent >= 100) {
                                waitForSocks(settings.daemonSocksPort)
                                return@withTimeout
                            }
                        }
                        line = logLines.poll() ?: if (stdout?.ready() == true) stdout.readLine() else null
                    }
                    if (controlWatch == null) {
                        controlWatch = TorControlClient.openWatch(lastControlPort, lastControlSocket)
                    }
                    val controlPercent = controlWatch?.bootstrapPercent()
                    if (controlPercent == null && controlWatch != null) {
                        runCatching { controlWatch.close() }
                        controlWatch = null
                    }
                    if (controlPercent != null && controlPercent != lastPercent) {
                        lastPercent = controlPercent
                        lastChangeAt = System.currentTimeMillis()
                        if (TorBootstrapWatch.marksHandshake(controlPercent)) reachedHandshake = true
                        TorLiveFlow.onBootstrap(controlPercent, "")
                        TorStatusStore.update(
                            phase = if (controlPercent >= 100) TorPhase.CONNECTED else TorPhase.BOOTSTRAPPING,
                            bootstrapPercent = controlPercent,
                            detail = if (controlPercent >= 100) {
                                "Tor circuit is ready · SOCKS 127.0.0.1:${settings.socksPort}"
                            } else {
                                "Bootstrapping Tor $controlPercent%"
                            },
                        )
                        if (controlPercent >= 100) {
                            waitForSocks(settings.daemonSocksPort)
                            return@withTimeout
                        }
                    }
                    if (!daemonAlive() && lastPercent < 100) {
                        val extra = drainRemaining(logLines, stdout)
                        extra.forEach { leftover ->
                            if (leftover.isNotBlank()) {
                                AppLogRepository.debug(LogSource.TOR, leftover.take(400))
                                recent.addLast(leftover.take(240))
                            }
                        }
                        val exit = processStdout?.let { runCatching { it.exitValue() }.getOrNull() }
                        error(
                            lastProblem.ifBlank {
                                "Tor exited before bootstrap finished exit=$exit last=${recent.joinToString(" | ")}"
                            },
                        )
                    }
                    if (TorBootstrapWatch.handshakeMissed(
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            reachedHandshake = reachedHandshake,
                            coldStart = coldStart,
                            enabled = settings.stageTimeouts,
                        )
                    ) {
                        error(
                            lastProblem.ifBlank {
                                "Tor handshake not done in ${TorBootstrapWatch.handshakeDeadlineMs(coldStart) / 1000}s " +
                                    "(stage timeout, at $lastPercent%)"
                            },
                        )
                    }
                    val stalled = lastPercent in 1..99 &&
                        System.currentTimeMillis() - lastChangeAt >=
                        TorBootstrapWatch.stallBudgetMs(lastPercent, reachedHandshake, coldStart || !settings.stageTimeouts)
                    if (stalled) {
                        error(
                            lastProblem.ifBlank {
                                "Tor stuck at $lastPercent%. WebTunnel bridge is not completing the circuit."
                            },
                        )
                    }
                    kotlinx.coroutines.delay(150)
                    }
                } finally {
                    runCatching { controlWatch?.close() }
                }
            }
        }
    }

    private fun drainRemaining(
        logLines: ConcurrentLinkedQueue<String>,
        stdout: BufferedReader?,
    ): List<String> {
        val extra = mutableListOf<String>()
        while (true) {
            val line = logLines.poll() ?: break
            extra += line
        }
        if (stdout != null) {
            runCatching {
                while (stdout.ready()) {
                    val line = stdout.readLine() ?: break
                    extra += line
                }
            }
        }
        return extra
    }

    private fun waitForSocks(port: Int) {
        repeat(25) {
            val ready = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                    true
                }
            }.getOrDefault(false)
            if (ready) return
            Thread.sleep(200)
        }
        AppLogRepository.warning(
            LogSource.TOR,
            "Tor reported 100% but SOCKS 127.0.0.1:$port was not accepting yet",
        )
    }

    private fun bootstrapPercent(line: String): Int? {
        val match = BOOTSTRAP_PATTERN.find(line) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun isFatalLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("no configured transport called") ||
            lower.contains("no such transport is supported") ||
            lower.contains("didn't launch any pluggable transport listeners") ||
            lower.contains("could not launch managed proxy") ||
            lower.contains("failed to bind") ||
            lower.contains("could not bind") ||
            lower.contains("address already in use") ||
            lower.contains("no such file or directory") ||
            lower.contains("permissions on directory") ||
            lower.contains("couldn't set permissions") ||
            lower.contains("failed to parse/validate config")
    }

    private fun sanitize(line: String): String = line
        .replace(Regex("""^\w+ \[[^\]]+\] """), "")
        .take(280)

    private fun noticeTail(file: File): String {
        if (!file.isFile || file.length() == 0L) return ""
        return runCatching {
            file.readLines().takeLast(16).joinToString(" | ").take(600)
        }.getOrDefault("")
    }

    /**
     * An arm/arm64 APK on an x86 emulator runs Java through native-bridge translation, but
     * a directly exec'd Go PT (libwebtunnel.so) dies with SIGILL there. Fail fast and clearly.
     */
    private fun checkNativeBridgeAbi() {
        val libDir = appContext.applicationInfo.nativeLibraryDir.orEmpty()
        val hostX86 = System.getProperty("os.arch").orEmpty().contains("x86") ||
            System.getProperty("os.arch").orEmpty().contains("amd64")
        val armLibs = libDir.endsWith("/arm64") || libDir.endsWith("/arm")
        if (hostX86 && armLibs) {
            error(
                "This ARM build runs under x86 emulation; the WebTunnel plugin cannot start here. " +
                    "Install the x86_64 APK on this emulator.",
            )
        }
    }

    private fun preparePrivateDir(dir: File): File {
        dir.mkdirs()
        dir.setReadable(true, true)
        dir.setWritable(true, true)
        dir.setExecutable(true, true)
        runCatching { Os.chmod(dir.absolutePath, MODE_0700) }
        File(dir, "lock").delete()
        File(dir, TorLaunchArgs.CONTROL_SOCKET_NAME).delete()
        return dir
    }

    private fun installedOrNative(installedName: String, nativeName: String): File? = firstExisting(
        File(appContext.filesDir, "tor/bin/$installedName"),
        File(appContext.applicationInfo.nativeLibraryDir, nativeName),
    )

    private fun firstExisting(vararg files: File): File? =
        files.firstOrNull { it.isFile && it.length() > 0L }

    companion object {
        private val BOOTSTRAP_PATTERN = Regex("Bootstrapped (\\d+)%")

        /** Process-wide: an in-process tor_main saw a managed proxy die. */
        @Volatile private var jniTainted = false
        internal const val DIRECT_BOOTSTRAP_TIMEOUT_MS = 30_000L
        internal const val BRIDGE_BOOTSTRAP_TIMEOUT_MS = 90_000L
        private const val MODE_0700 = 448
    }
}
