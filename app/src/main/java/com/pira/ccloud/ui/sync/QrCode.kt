package com.pira.ccloud.ui.sync

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for [payload], drawn black on white (scanners need the contrast, also in dark theme)
 * with sharp edges at any size.
 */
@Composable
fun QrCode(payload: String, contentDescription: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { qrBitmap(payload) }
    Box(modifier.background(Color.White).padding(12.dp)) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            filterQuality = FilterQuality.None
        )
    }
}

/** One pixel per QR module; the Image scales it up. */
private fun qrBitmap(payload: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    )
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
