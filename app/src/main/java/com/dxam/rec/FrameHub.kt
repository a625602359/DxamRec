package com.dxam.rec

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * v94：帧中转站。
 * DetectService 的分析器把每帧压成 JPEG 存这里，
 * HttpApiServer 的 /snapshot 和 /stream 从这里取。
 */
object FrameHub {
    @Volatile private var jpeg: ByteArray? = null
    @Volatile var lastAt = 0L
        private set
    @Volatile var w = 0
        private set
    @Volatile var h = 0
        private set

    fun publish(bmp: Bitmap) {
        try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 60, baos)
            jpeg = baos.toByteArray()
            lastAt = System.currentTimeMillis()
            w = bmp.width
            h = bmp.height
        } catch (_: Throwable) {}
    }

    @Volatile var lastRequestAt = 0L
    fun touch() { lastRequestAt = System.currentTimeMillis() }

    fun latest(): ByteArray? = jpeg
}
