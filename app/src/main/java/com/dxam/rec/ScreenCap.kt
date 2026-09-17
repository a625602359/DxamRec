package com.dxam.rec

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v98: screen capture + screen record via /system/bin/screencap & screenrecord (root).
 */
object ScreenCap {
    private const val TAG = "DxamRec"
    private const val SHOT = "/sdcard/Download/dxam_shot.png"
    private const val DIR = "/sdcard/Movies/DxamRec"

    @Volatile private var recProc: Process? = null
    @Volatile private var recFile: String? = null

    fun screenshot(): File? {
        return try {
            val f = File(SHOT)
            try { f.delete() } catch (_: Throwable) {}
            val out = ShellExec.run("/system/bin/screencap -p " + SHOT, 12000)
            Log.i(TAG, "screencap done: " + out.trim())
            if (f.exists() && f.length() > 0) f else null
        } catch (t: Throwable) {
            Log.e(TAG, "screenshot fail", t)
            null
        }
    }

    fun isRecording(): Boolean = recProc?.isAlive == true

    fun currentFile(): String? = recFile

    @Synchronized
    fun startRecord(durationSec: Int): String {
        if (isRecording()) return "already_recording"
        return try {
            try { File(DIR).mkdirs() } catch (_: Throwable) {}
            val f = File(DIR, "screen_" + System.currentTimeMillis() + ".mp4")
            val lim = if (durationSec in 1..180) "--time-limit " + durationSec + " " else ""
            val cmd = "/system/bin/screenrecord " + lim + "--bit-rate 6000000 " + f.absolutePath
            val p = ShellExec.start(cmd)
            recProc = p
            recFile = f.absolutePath
            Thread {
                try {
                    val rd = p.inputStream.bufferedReader()
                    var l = rd.readLine()
                    while (l != null) { Log.i(TAG, "screenrecord: " + l); l = rd.readLine() }
                } catch (_: Throwable) {}
            }.start()
            Log.i(TAG, "screenrecord start: " + cmd)
            "started:" + f.name
        } catch (t: Throwable) {
            Log.e(TAG, "screenrecord start fail", t)
            recProc = null; recFile = null
            "ERR:" + (t.message ?: "unknown")
        }
    }

    @Synchronized
    fun stopRecord(): String {
        val p = recProc ?: return "not_recording"
        try { ShellExec.run("pkill -INT screenrecord", 5000) } catch (_: Throwable) {}
        try { p.waitFor(6, TimeUnit.SECONDS) } catch (_: Throwable) {}
        try { p.destroyForcibly() } catch (_: Throwable) {}
        recProc = null
        val f = recFile
        recFile = null
        val name = f?.let { File(it).name } ?: "?"
        Log.i(TAG, "screenrecord stopped: " + name)
        return "stopped:" + name
    }
}
