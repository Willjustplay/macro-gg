package com.example.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

fun Bitmap.toFrame(): Frame {
    val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    if (src !== this) src.recycle()
    return Frame(w, h, pixels)
}

fun File.decodeFrame(): Frame? {
    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val bmp = BitmapFactory.decodeFile(absolutePath, opts) ?: return null
    return try {
        bmp.toFrame()
    } finally {
        bmp.recycle()
    }
}

fun Bitmap.savePng(file: File) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
    }
}
