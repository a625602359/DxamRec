package com.dxam.rec

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * 录像器（系统 MediaRecorder）。
 * v61：默认输出 MP4（useTs=false），不再需要转换。
 *      保留 useTs=true 分支仅为兼容旧逻辑。
 * 使用顺序：prepare(file) → 拿到 surface 接上相机 → start()
 */
class TsRecorder(
    private val ctx: Context,
    private val width: Int,
    private val height: Int,
    private val rotation: Int,
    private val videoBitrate: Int = 8_000_000,
    private val useTs: Boolean = false
) {
    private var mr: MediaRecorder? = null
    @Volatile private var started = false

    val surface: Surface?
        get() = try { mr?.surface } catch (_: Throwable) { null }

    val isPrepared: Boolean get() = mr != null
    val isStarted: Boolean get() = started

    fun prepare(file: File): Boolean {
        release()
        val r = try {
            if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
            else @Suppress("DEPRECATION") MediaRecorder()
        } catch (t: Throwable) {
            Log.e(TAG, "recorder create failed", t); return false
        }
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(if (useTs) MediaRecorder.OutputFormat.MPEG_2_TS else MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(videoBitrate)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setOrientationHint(rotation)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            mr = r
            started = false
            Log.i(TAG, "recorder prepared: " + file.name + " " + width + "x" + height + " rot=" + rotation)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "recorder prepare failed", t)
            try { r.release() } catch (_: Throwable) {}
            mr = null
            false
        }
    }

    fun start(): Boolean {
        val r = mr ?: return false
        return try {
            r.start()
            started = true
            Log.i(TAG, "recorder started")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "recorder start failed", t)
            false
        }
    }

    fun stop() {
        val r = mr ?: return
        try { if (started) r.stop() } catch (t: Throwable) { Log.e(TAG, "recorder stop err", t) }
        try { r.release() } catch (_: Throwable) {}
        mr = null; started = false
        Log.i(TAG, "recorder stopped")
    }

    fun release() {
        try { if (started) mr?.stop() } catch (_: Throwable) {}
        try { mr?.reset() } catch (_: Throwable) {}
        try { mr?.release() } catch (_: Throwable) {}
        mr = null; started = false
    }

    companion object { const val TAG = "DxamRec" }
}
