package com.hesier.lumy.ui.login

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 白底黑码，ImageView 再按自身尺寸缩放 */
internal fun qrBitmap(text: String, size: Int = 660): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
