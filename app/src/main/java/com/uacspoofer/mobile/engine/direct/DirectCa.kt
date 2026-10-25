package com.uacspoofer.mobile.engine.direct

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import org.json.JSONObject
import java.io.File

/**
 * Per-install CA for engine 4 MITM (Google fronting + ECH re-pack). Private key never leaves
 * app storage. The user must install the .crt as a user CA for browsers to trust MITM'd sites.
 */
object DirectCa {
    private const val DIR = "direct_ca"
    private const val CRT = "uac-direct-ca.crt"
    private const val KEY = "uac-direct-ca.key"
    private const val JSON = "uac-direct-ca.json"

    data class Material(val certificatePemLines: List<String>, val keyPemLines: List<String>, val crtFile: File)

    fun ensure(context: Context): Material {
        val dir = File(context.filesDir, DIR).also { it.mkdirs() }
        val crt = File(dir, CRT)
        val key = File(dir, KEY)
        val json = File(dir, JSON)
        if (crt.isFile && key.isFile && crt.length() > 0 && key.length() > 0) {
            return Material(crt.readLines().filter { it.isNotBlank() }, key.readLines().filter { it.isNotBlank() }, crt)
        }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.isFile) { "Xray binary missing; cannot mint CA" }
        val started = ProcessBuilder(binary.absolutePath, "tls", "cert", "--ca", "--expire=87600h")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val out = started.inputStream.bufferedReader().readText()
        val code = started.waitFor()
        check(code == 0) { "xray tls cert failed ($code): ${out.take(300)}" }
        val obj = JSONObject(out)
        val certLines = obj.getJSONArray("certificate").let { a -> (0 until a.length()).map { a.getString(it) } }
        val keyLines = obj.getJSONArray("key").let { a -> (0 until a.length()).map { a.getString(it) } }
        crt.writeText(certLines.joinToString("\n") + "\n")
        key.writeText(keyLines.joinToString("\n") + "\n")
        json.writeText(out)
        AppLogRepository.info(LogSource.XRAY, "UAC Direct: minted per-install MITM CA")
        return Material(certLines, keyLines, crt)
    }

    fun exportToDownloads(context: Context): File {
        val material = ensure(context)
        val publicDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dest = File(publicDir, "UAC-Direct-CA.crt")
        material.crtFile.copyTo(dest, overwrite = true)
        return dest
    }
}
