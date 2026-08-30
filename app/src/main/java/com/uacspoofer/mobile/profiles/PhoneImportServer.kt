package com.uacspoofer.mobile.profiles

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.concurrent.thread

internal class PhoneImportServer(
    private val onConfigsReceived: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    @Volatile private var token: String = ""
    var url: String? = null
        private set

    fun start(): Boolean {
        if (serverSocket != null) return true
        val host = PhoneImportLan.pickLanIpv4() ?: return false
        val socket = (18_890..18_909).firstNotNullOfOrNull { port ->
            runCatching { ServerSocket(port).apply { reuseAddress = true } }.getOrNull()
        } ?: return false
        token = randomToken()
        serverSocket = socket
        url = "http://$host:${socket.localPort}/i/$token/"
        thread(name = "uac-phone-import", isDaemon = true) {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { handle(client) }
                runCatching { client.close() }
            }
        }
        return true
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        url = null
        token = ""
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val headerBytes = readHeaders(input) ?: return
        val headerText = headerBytes.toString(StandardCharsets.US_ASCII)
        val requestLine = headerText.lineSequence().firstOrNull().orEmpty()
        val path = requestLine.split(' ').getOrElse(1) { "/" }
        val allowed = path == "/i/$token" || path == "/i/$token/" || path.startsWith("/i/$token?")
        if (!allowed) {
            write(client, 404, "<!doctype html><title>Not found</title>Not found")
            return
        }
        val isPost = requestLine.startsWith("POST ", ignoreCase = true)
        if (!isPost) {
            write(client, 200, formPage())
            return
        }
        val length = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        if (length <= 0 || length > MAX_BODY) {
            write(client, 200, formPage("No configuration text received."))
            return
        }
        val body = readExact(input, length).toString(StandardCharsets.UTF_8)
        val configs = PhoneImportLan.parseFormConfigs(body)
        if (configs.isBlank()) {
            write(client, 200, formPage("No configuration text received."))
            return
        }
        main.post { onConfigsReceived(configs) }
        write(client, 200, successPage())
    }

    private fun write(client: Socket, status: Int, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else "Not Found"
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val output = client.getOutputStream()
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun formPage(error: String = ""): String {
        val err = if (error.isBlank()) "" else "<p class=\"err\">${escape(error)}</p>"
        return """
            <!doctype html>
            <html lang="fa" dir="rtl">
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta charset="utf-8">
              <title>UAC SNI Spoofer</title>
              <style>
                body{font-family:Tahoma,Arial,sans-serif;background:#071018;color:#eef4fb;margin:0;padding:22px}
                h1{font-size:22px;margin:0 0 10px}
                p{color:#9aadc0;line-height:1.55;font-size:14px}
                textarea{box-sizing:border-box;width:100%;height:48vh;background:#101c29;color:#fff;border:1px solid #3d8dff;border-radius:10px;padding:14px;font-size:15px}
                button{width:100%;height:50px;margin-top:14px;border:0;border-radius:10px;background:#3d8dff;color:#02101c;font-size:17px;font-weight:700}
                .err{color:#ff7483}
              </style>
            </head>
            <body>
              <h1>UAC SNI Spoofer</h1>
              <p>کانفیگ‌های VLESS، Trojan یا VMess را بچسبان. هر خط یک کانفیگ.</p>
              $err
              <form method="post">
                <textarea name="configs" autofocus></textarea>
                <button type="submit">ارسال به برنامه</button>
              </form>
            </body>
            </html>
        """.trimIndent()
    }

    private fun successPage(): String = """
        <!doctype html>
        <html lang="fa" dir="rtl">
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta charset="utf-8">
          <title>UAC SNI Spoofer</title>
          <style>
            body{font-family:Tahoma,Arial,sans-serif;background:#071018;color:#eef4fb;margin:0;padding:28px;text-align:center}
            h1{font-size:22px;margin-top:18vh}
            a{color:#3d8dff}
          </style>
        </head>
        <body>
          <h1>کانفیگ‌ها ارسال شد</h1>
          <p>برگرد روی دستگاه. اگر خواستی باز هم اضافه کن.</p>
          <p><a href="./">افزودن کانفیگ دیگر</a></p>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val MAX_BODY = 512 * 1024
        private val HEX = "0123456789abcdef".toCharArray()

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }

        private fun escape(value: String): String =
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun readHeaders(input: BufferedInputStream): ByteArray? {
            val buffer = ByteArray(8 * 1024)
            var filled = 0
            while (filled < buffer.size) {
                val one = input.read()
                if (one < 0) break
                buffer[filled] = one.toByte()
                filled++
                if (filled >= 4 &&
                    buffer[filled - 4] == '\r'.code.toByte() &&
                    buffer[filled - 3] == '\n'.code.toByte() &&
                    buffer[filled - 2] == '\r'.code.toByte() &&
                    buffer[filled - 1] == '\n'.code.toByte()
                ) {
                    return buffer.copyOf(filled)
                }
            }
            return null
        }

        private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                if (read <= 0) break
                offset += read
            }
            return if (offset == length) body else body.copyOf(offset)
        }
    }
}
