package com.uacspoofer.mobile.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object AppLogRepository {
    private const val MAX_ENTRIES = 2_000
    private val sequence = AtomicLong(0L)
    private val lock = Any()
    private val mutableEntries = MutableStateFlow<List<AppLogEntry>>(emptyList())

    val entries: StateFlow<List<AppLogEntry>> = mutableEntries.asStateFlow()

    init {
        info(LogSource.APP, "Live logging initialized")
    }

    fun debug(source: LogSource, message: String) = append(LogLevel.DEBUG, source, message)
    fun info(source: LogSource, message: String) = append(LogLevel.INFO, source, message)
    fun success(source: LogSource, message: String) = append(LogLevel.SUCCESS, source, message)
    fun warning(source: LogSource, message: String, error: Throwable? = null) =
        append(LogLevel.WARNING, source, withError(message, error))

    fun error(source: LogSource, message: String, error: Throwable? = null) =
        append(LogLevel.ERROR, source, withError(message, error))

    fun clear() {
        synchronized(lock) { mutableEntries.value = emptyList() }
    }

    fun snapshotText(): String = entries.value.joinToString(separator = "\n") { entry ->
        "${entry.timestamp}  ${entry.level.label.padEnd(5)}  ${entry.source.label.padEnd(7)}  ${entry.message}"
    }

    fun entryCount(): Int = entries.value.size

    fun estimatedBytes(): Long = entries.value.sumOf { entry ->
        80L + (entry.message.length + entry.timestamp.length + entry.source.label.length) * 2L
    }

    private fun append(level: LogLevel, source: LogSource, rawMessage: String) {
        val message = rawMessage
            .replace('\r', ' ')
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .trim()
            .take(2_000)
        if (message.isEmpty()) return

        val entry = AppLogEntry(
            id = sequence.incrementAndGet(),
            timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()),
            level = level,
            source = source,
            message = message,
        )
        synchronized(lock) {
            val current = mutableEntries.value
            mutableEntries.value = if (current.size < MAX_ENTRIES) {
                current + entry
            } else {
                current.drop(current.size - MAX_ENTRIES + 1) + entry
            }
        }
    }

    private fun withError(message: String, error: Throwable?): String = if (error == null) {
        message
    } else {
        "$message — ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }
}

data class AppLogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val source: LogSource,
    val message: String,
)

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    SUCCESS("OK"),
    WARNING("WARN"),
    ERROR("ERROR"),
}

enum class LogSource(val label: String) {
    APP("APP"),
    SERVICE("SERVICE"),
    ADAPTIVE("ADAPT"),
    XRAY("XRAY"),
    PROXY("PROXY"),
    TUN("TUN"),
    TOR("TOR"),
}
