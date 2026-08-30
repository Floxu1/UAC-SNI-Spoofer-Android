package com.uacspoofer.mobile.engine.tor

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

internal object TorControlClient {
    private val PROGRESS = Regex("PROGRESS=(\\d+)")

    fun signalShutdown(controlPort: Int, controlSocket: File?): Boolean {
        return session(controlPort, controlSocket) { writer, reader ->
            if (!authenticate(writer, reader)) return@session false
            writer.write("SIGNAL SHUTDOWN\r\n".toByteArray())
            writer.flush()
            ok(reader)
        } == true
    }

    fun bootstrapPercent(controlPort: Int, controlSocket: File?): Int? {
        return session(controlPort, controlSocket) { writer, reader ->
            if (!authenticate(writer, reader)) return@session null
            writer.write("GETINFO status/bootstrap-phase\r\n".toByteArray())
            writer.flush()
            parseBootstrapProgress(readUntilComplete(reader).orEmpty())
        }
    }

    fun setExitCountry(
        controlPort: Int,
        controlSocket: File?,
        countryCode: String,
        strict: Boolean,
    ): Boolean {
        val commands = TorExitCountry.controlCommands(countryCode, strict)
        val configured = session(
            controlPort = controlPort,
            controlSocket = controlSocket,
            connectTimeoutMs = 1_500,
            soTimeoutMs = 8_000,
        ) { writer, reader ->
            if (!authenticate(writer, reader)) return@session false
            for (command in commands.filterNot { it.startsWith("SIGNAL") }) {
                writer.write("$command\r\n".toByteArray())
                writer.flush()
                val body = readUntilComplete(reader).orEmpty()
                if (isControlError(body)) {
                    AppLogRepository.warning(LogSource.TOR, "Tor $command failed: ${body.take(180)}")
                    return@session false
                }
            }
            true
        } == true
        if (!configured) return false
        val confirmed = getConf(controlPort, controlSocket, "ExitNodes")
        if (!TorExitCountry.exitNodesConfigured(confirmed.orEmpty(), countryCode)) {
            AppLogRepository.warning(
                LogSource.TOR,
                "ExitNodes did not stick GETCONF=${confirmed.orEmpty().take(120)} wanted={${countryCode.ifBlank { "auto" }}}",
            )
            return false
        }
        closeBuiltCircuits(controlPort, controlSocket)
        if (!signalNewNym(controlPort, controlSocket)) {
            Thread.sleep(11_000)
            if (!signalNewNym(controlPort, controlSocket)) {
                AppLogRepository.warning(LogSource.TOR, "SIGNAL NEWNYM failed; new streams may keep the old exit")
            }
        }
        return true
    }

    private fun closeBuiltCircuits(controlPort: Int, controlSocket: File?) {
        val status = getInfo(controlPort, controlSocket, "circuit-status").orEmpty()
        val ids = parseBuiltCircuitIds(status)
        if (ids.isEmpty()) return
        AppLogRepository.info(LogSource.TOR, "Closing ${ids.size} built Tor circuit(s) after ExitNodes change")
        session(
            controlPort = controlPort,
            controlSocket = controlSocket,
            connectTimeoutMs = 1_500,
            soTimeoutMs = 8_000,
        ) { writer, reader ->
            if (!authenticate(writer, reader)) return@session false
            for (id in ids) {
                writer.write("CLOSECIRCUIT $id\r\n".toByteArray())
                writer.flush()
                readUntilComplete(reader)
            }
            true
        }
    }

    private fun signalNewNym(controlPort: Int, controlSocket: File?): Boolean {
        return session(
            controlPort = controlPort,
            controlSocket = controlSocket,
            connectTimeoutMs = 1_500,
            soTimeoutMs = 8_000,
        ) { writer, reader ->
            if (!authenticate(writer, reader)) return@session false
            writer.write("SIGNAL NEWNYM\r\n".toByteArray())
            writer.flush()
            val body = readUntilComplete(reader).orEmpty()
            val failed = isControlError(body)
            if (failed) {
                AppLogRepository.warning(LogSource.TOR, "Tor SIGNAL NEWNYM failed: ${body.take(180)}")
            }
            !failed
        } == true
    }

    private fun getConf(controlPort: Int, controlSocket: File?, keyword: String): String? =
        controlQuery(controlPort, controlSocket, "GETCONF $keyword")

    private fun getInfo(controlPort: Int, controlSocket: File?, keyword: String): String? =
        controlQuery(controlPort, controlSocket, "GETINFO $keyword")

    private fun controlQuery(controlPort: Int, controlSocket: File?, command: String): String? {
        return session(
            controlPort = controlPort,
            controlSocket = controlSocket,
            connectTimeoutMs = 1_500,
            soTimeoutMs = 8_000,
        ) { writer, reader ->
            if (!authenticate(writer, reader)) return@session null
            writer.write("$command\r\n".toByteArray())
            writer.flush()
            readUntilComplete(reader)
        }
    }

    internal fun parseBuiltCircuitIds(body: String): List<String> {
        val ids = linkedSetOf<String>()
        body.lineSequence().forEach { raw ->
            val line = raw.trim().let { text ->
                when {
                    text.startsWith("250-circuit-status=") -> text.removePrefix("250-circuit-status=")
                    text.startsWith("250+circuit-status=") -> text.removePrefix("250+circuit-status=")
                    text == "." || text.startsWith("250") -> ""
                    else -> text
                }
            }.trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].all(Char::isDigit) && parts[1].equals("BUILT", ignoreCase = true)) {
                ids.add(parts[0])
            }
        }
        return ids.toList()
    }

    internal fun parseBootstrapProgress(body: String): Int? =
        PROGRESS.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()

    internal fun isControlError(body: String): Boolean =
        body.lineSequence().any { line ->
            line.startsWith("5") && line.length >= 3 && line.take(3).all(Char::isDigit)
        }

    private fun <T> session(
        controlPort: Int,
        controlSocket: File?,
        connectTimeoutMs: Int = 400,
        soTimeoutMs: Int = 800,
        block: (OutputStream, BufferedReader) -> T,
    ): T? {
        tcp(controlPort, connectTimeoutMs, soTimeoutMs, block)?.let { return it }
        if (controlSocket != null) unix(controlSocket, soTimeoutMs, block)?.let { return it }
        return null
    }

    private fun <T> tcp(
        port: Int,
        connectTimeoutMs: Int,
        soTimeoutMs: Int,
        block: (OutputStream, BufferedReader) -> T,
    ): T? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
            socket.soTimeout = soTimeoutMs
            block(socket.getOutputStream(), socket.getInputStream().bufferedReader())
        }
    }.getOrNull()

    private fun <T> unix(
        file: File,
        soTimeoutMs: Int,
        block: (OutputStream, BufferedReader) -> T,
    ): T? {
        if (!file.exists()) return null
        return runCatching {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
                )
                socket.soTimeout = soTimeoutMs
                block(socket.outputStream, BufferedReader(InputStreamReader(socket.inputStream)))
            }
        }.getOrNull()
    }

    private fun authenticate(output: OutputStream, reader: BufferedReader): Boolean {
        output.write("AUTHENTICATE\r\n".toByteArray())
        output.flush()
        return ok(reader)
    }

    private fun ok(reader: BufferedReader): Boolean {
        val line = reader.readLine() ?: return false
        return line.startsWith("250")
    }

    private fun readUntilComplete(reader: BufferedReader): String? {
        val text = StringBuilder()
        var inData = false
        while (true) {
            val line = reader.readLine() ?: return if (text.isEmpty()) null else text.toString()
            text.appendLine(line)
            if (inData) {
                if (line == ".") inData = false
                continue
            }
            if (line.length >= 4 && line.take(3).all(Char::isDigit) && line[3] == '+') {
                inData = true
                continue
            }
            if (line.length >= 3 && line.take(3).all(Char::isDigit) && line.getOrNull(3) != '-') {
                return text.toString()
            }
        }
    }
}
