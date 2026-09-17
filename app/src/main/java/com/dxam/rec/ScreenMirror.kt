package com.dxam.rec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v120: 屏幕投屏。root 下 screencap 循环抓屏 -> JPEG -> 供 /screen MJPEG 推流。
 * 不依赖 MediaProjection，无需授权弹窗。
 */
object ScreenMirror {
    private const val TAG = "DxamRec"

    @Volatile private var running = false
    private var thread: Thread? = null

    /** 最新一帧 JPEG 字节 */
    @Volatile var lastFrame: ByteArray? = null
    /** 最新一帧的时间戳 */
    @Volatile var lastAt = 0L
    /** 目标帧率 1~5 */
    @Volatile var fps = 2
    /** JPEG 质量 30~90 */
    @Volatile var quality = 50

    private var suPath: String? = null
    private var probed = false

    fun isRunning() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            Log.i(TAG, "screen mirror start fps=" + fps)
            while (running) {
                val t0 = System.currentTimeMillis()
                try { grabOnce() } catch (t: Throwable) {
                    Log.w(TAG, "screen grab: " + t.message)
                }
                val dt = System.currentTimeMillis() - t0
                val wait = 1000L / fps - dt
                if (wait > 0) try { Thread.sleep(wait) } catch (_: Throwable) {}
            }
            Log.i(TAG, "screen mirror stop")
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread = null
        lastFrame = null
    }

    private fun grabOnce() {
        val su = findSu() ?: return
        val pb = ProcessBuilder(su, "-c", "screencap -p")
        val p = pb.start()
        val png = p.inputStream.readBytes()
        p.waitFor()
        if (png.size < 100) return
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bmp.recycle()
        lastFrame = bos.toByteArray()
        lastAt = System.currentTimeMillis()
    }

    private fun findSu(): String? {
        if (probed) return suPath
        probed = true
        val cands = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/debug_ramdisk/su", "su"
        )
        for (c in cands) {
            try {
                if (c == "su") { suPath = "su"; return suPath }
                val f = File(c)
                if (f.exists() && f.canExecute()) { suPath = c; return c }
            } catch (_: Throwable) {}
        }
        return null
    }
}
