package com.dxam.rec

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 人形侦测状态 -> JSON 文件，供 ShortX / MacroDroid 等外部脚本读取。
 * 主路径：/sdcard/Download/presence_state.json
 *
 * 写文件带节流：状态变化立即写，稳定时每 ~1s 心跳写一次，
 * 避免外部脚本读到频繁跳变的值。
 */
object PresenceState {
    const val TAG = "DxamRec"
    const val FILE_NAME = "presence_state.json"
    private const val HEARTBEAT_MS = 1000L

    @Volatile var present = false
    @Volatile var detecting = false
    @Volatile var recording = false
    @Volatile var lastSeenAt = 0L
    @Volatile var frames = 0
    @Volatile var seenCount = 0
    @Volatile var recCount = 0
    @Volatile var recDurationMs = 0L

    @Volatile private var lastWriteAt = 0L
    @Volatile private var lastPresent = false
    @Volatile private var lastDetecting = false
    @Volatile private var lastRecording = false
    @Volatile private var wroteOnce = false

    @Synchronized
    fun write(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        val changed = !wroteOnce || present != lastPresent ||
                detecting != lastDetecting || recording != lastRecording
        if (!force && !changed && now - lastWriteAt < HEARTBEAT_MS) return

        val json = JSONObject().apply {
            put("有人", present)
            put("侦测中", detecting)
            put("录像中", recording)
            put("最后见到时间", lastSeenAt)
            put("距今毫秒", if (lastSeenAt == 0L) -1L else now - lastSeenAt)
            put("帧数", frames)
            put("识别次数", seenCount)
            put("录像段数", recCount)
            put("录像时长毫秒", recDurationMs)
            put("时间戳", now)
            put("版本", BuildConfig.VERSION_NAME)
        }.toString()

        var ok = false
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_NAME).writeText(json)
            ok = true
        } catch (t: Throwable) { Log.w(TAG, "write Download failed: " + t.message) }
        if (!ok) {
            try {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                if (!dir.exists()) dir.mkdirs()
                File(dir, FILE_NAME).writeText(json)
                ok = true
            } catch (t: Throwable) { Log.e(TAG, "write ext failed", t) }
        }
        if (ok) {
            lastWriteAt = now; lastPresent = present
            lastDetecting = detecting; lastRecording = recording
            wroteOnce = true
        }
    }

    fun writeAsync(ctx: Context, force: Boolean = false) {
        Thread { try { write(ctx, force) } catch (_: Throwable) {} }.start()
    }
}
