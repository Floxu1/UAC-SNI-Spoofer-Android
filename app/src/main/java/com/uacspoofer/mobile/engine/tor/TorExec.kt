package com.uacspoofer.mobile.engine.tor

import android.os.Build
import java.io.File

internal object TorExec {
    fun argv(
        binary: File,
        extraArgs: List<String> = emptyList(),
        linker: String? = null,
    ): List<String> {
        val command = buildList {
            if (!linker.isNullOrBlank()) add(linker)
            add(binary.absolutePath)
            addAll(extraArgs)
        }
        check(command.all { it.isNotEmpty() && !it.any(Char::isWhitespace) }) {
            "Tor exec path cannot contain spaces: ${command.joinToString(" ")}"
        }
        return command
    }

    fun detectLinker(): String? {
        val linker64 = File("/system/bin/linker64")
        val linker = File("/system/bin/linker")
        return when {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && linker64.exists() -> linker64.absolutePath
            linker.exists() -> linker.absolutePath
            linker64.exists() -> linker64.absolutePath
            else -> null
        }
    }
}
