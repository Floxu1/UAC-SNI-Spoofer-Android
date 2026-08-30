package com.uacspoofer.mobile.profiles

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object PhoneImportQr {
    fun bitmap(text: String, size: Int = 720): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { index ->
            if (matrix.get(index % size, index / size)) 0xFF02101C.toInt() else 0xFFFFFFFF.toInt()
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }.getOrNull()
}
