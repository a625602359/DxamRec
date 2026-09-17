package com.dxam.rec

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * v98: remote shell exec.
 * Probe multiple su paths; su -c if root granted, else sh -c fallback.
 */
object ShellExec {
    private const val TAG = "DxamRec"

    private val SU_CANDIDATES = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "su"
    )

    private val suPath: String? by lazy {
        for (p in SU_CANDIDATES) {
            try {
                val r = ProcessBuilder(p, "-c", "id").redirectErrorStream(true).start()
                val out = r.inputStream.bufferedReader().readText()
                val fin = r.waitFor(5, TimeUnit.SECONDS)
                if (fin && r.exitValue() == 0 && out.contains("uid=0")) {
                    Log.i(TAG, "shell: root via " + p)
                    return@lazy p
                }
            } catch (_: Throwable) {}
        }
        Log.w(TAG, "shell: no root su available")
        null
    }

    fun isRoot(): Boolean = suPath != null

    /** 供需要持有 Process 的调用方使用（如 screenrecord 长任务）。 */
    fun start(cmd: String): Process {
        val sp = suPath
        val argv = if (sp != null) arrayOf(sp, "-c", cmd) else arrayOf("sh", "-c", cmd)
        return ProcessBuilder(*argv).redirectErrorStream(true).start()
    }

    fun run(cmd: String, timeoutMs: Long = 15000): String {
        val sp = suPath
        val argv = if (sp != null) arrayOf(sp, "-c", cmd) else arrayOf("sh", "-c", cmd)
        return try {
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val rd = BufferedReader(InputStreamReader(p.inputStream))
            val reader = Thread {
                try {
                    var l = rd.readLine()
                    while (l != null) { sb.append(l).append('\n'); l = rd.readLine() }
                } catch (_: Throwable) {}
            }
            reader.isDaemon = true
            reader.start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                try { p.destroyForcibly() } catch (_: Throwable) {}
                try { reader.join(800) } catch (_: Throwable) {}
                return sb.toString().takeLast(20000) + "\n[timeout " + timeoutMs + "ms]"
            }
            try { reader.join(800) } catch (_: Throwable) {}
            sb.toString().takeLast(20000)
        } catch (t: Throwable) {
            Log.e(TAG, "shell exec fail", t)
            "ERR: " + (t.message ?: "unknown")
        }
    }
}
