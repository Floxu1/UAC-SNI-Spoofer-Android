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
        )
    }

    fun locateTorBinary(): File? = installedOrNative("tor", "libtor.so")

    fun locateWebTunnelPlugin(): File? = installedOrNative("webtunnel", "libwebtunnel.so")

    suspend fun start(
        settings: TorEngineSettings,
        bridges: List<WebTunnelBridge>,
        timeoutMs: Long = if (bridges.isEmpty()) DIRECT_BOOTSTRAP_TIMEOUT_MS else BRIDGE_BOOTSTRAP_TIMEOUT_MS,
    ) = withContext(Dispatchers.IO) {
        stop()
        val runtime = TorNativeRuntime.install(appContext)
        val plugin = runtime.webtunnel.takeIf { it.isFile }
        if (bridges.isNotEmpty()) {
            check(plugin != null && plugin.isFile && plugin.length() > 0L) {
                "WebTunnel plugin is missing for ${Build.SUPPORTED_ABIS.joinToString()}"
            }
        }
        val dataDir = preparePrivateDir(File(appContext.filesDir, "tor/data"))
        val cacheDir = preparePrivateDir(File(appContext.cacheDir, "tor"))
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
            ),
        )
        defaultsFile.writeText("# UAC Tor defaults; SocksPort is in torrc\n")
        noticeLog.delete()
        lastControlPort = settings.validated().controlPort
        lastControlSocket = controlSocket
        TorStatusStore.update(TorPhase.STARTING, 0, "Starting Tor")
        val verifyText = verifyConfig(
            runtime.tor,
            rcFile,
            defaultsFile,
            dataDir,
            cacheDir,
            controlSocket,
            timeoutSec = if (geoIp != null) 45L else 15L,
        )
        if (verifyText.isNotBlank()) {
            verifyText.lineSequence().filter { it.isNotBlank() }.take(12).forEach { line ->
                AppLogRepository.debug(LogSource.TOR, line.take(400))
            }
        }
        val logLines = startNoticePump(noticeLog)
        val launchedJni = tryLaunchJni(
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
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        running.set(false)
        noticePumpRunning.set(false)
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
                        if (isFatalLine(line)) {
                            lastProblem = sanitize(line)
                            AppLogRepository.warning(LogSource.TOR, lastProblem)
                        }
                        val percent = bootstrapPercent(line)
                        if (percent != null && percent != lastPercent) {
                            lastPercent = percent
                            lastChangeAt = System.currentTimeMillis()
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
                                waitForSocks(settings.socksPort)
                                return@withTimeout
                            }
                        }
                        line = logLines.poll() ?: if (stdout?.ready() == true) stdout.readLine() else null
                    }
                    val controlPercent = TorControlClient.bootstrapPercent(lastControlPort, lastControlSocket)
                    if (controlPercent != null && controlPercent != lastPercent) {
                        lastPercent = controlPercent
                        lastChangeAt = System.currentTimeMillis()
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
                            waitForSocks(settings.socksPort)
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
                    val stalled = lastPercent in 1..99 &&
                        System.currentTimeMillis() - lastChangeAt >= STALL_TIMEOUT_MS
                    if (stalled) {
                        error(
                            lastProblem.ifBlank {
                                "Tor stuck at $lastPercent%. WebTunnel bridge is not completing the circuit."
                            },
                        )
                    }
                    kotlinx.coroutines.delay(150)
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
        internal const val DIRECT_BOOTSTRAP_TIMEOUT_MS = 30_000L
        internal const val BRIDGE_BOOTSTRAP_TIMEOUT_MS = 90_000L
        private const val STALL_TIMEOUT_MS = 18_000L
        private const val MODE_0700 = 448
    }
}
