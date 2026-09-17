package com.dxam.rec

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.UUID

/**
 * v98: HTTP API. Added /shell /screenshot /screenrec /install /upload.
 * Auth: reuse api_token for everything except /health.
 */
object HttpApiServer {
    private const val TAG = "DxamRec"
    private const val PREF = "dxam_pref"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_PORT = "api_port"
    const val DEF_PORT = 8686
    private const val UP_NAME = "dxam_upload.apk"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun isRunning() = running

    fun port(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_PORT, DEF_PORT)

    fun token(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var t = sp.getString(KEY_TOKEN, null)
        if (t.isNullOrBlank()) {
            t = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            sp.edit().putString(KEY_TOKEN, t).apply()
        }
        return t
    }

    fun start(ctx: Context) {
        if (running) return
        val p = port(ctx)
        try {
            val ss = ServerSocket(p)
            server = ss
            running = true
            thread = Thread {
                Log.i(TAG, "http api listen :" + p)
                while (running) {
                    try {
                        val s = ss.accept()
                        Thread { handle(ctx, s) }.start()
                    } catch (t: Throwable) {
                        if (running) Log.w(TAG, "accept: " + t.message)
                    }
                }
            }.also { it.start() }
        } catch (t: Throwable) {
            Log.e(TAG, "http start fail", t)
            running = false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        thread = null
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        var c = ins.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = ins.read()
        }
        return sb.toString()
    }

    private fun handle(ctx: Context, s: Socket) {
        try {
            s.soTimeout = 30000
            val ins = BufferedInputStream(s.getInputStream())
            val line = readLine(ins) ?: return
            var contentLength = 0
            var ctype = ""
            while (true) {
                val h = readLine(ins) ?: break
                if (h.isEmpty()) break
                val lo = h.lowercase()
                if (lo.startsWith("content-length:")) {
                    contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                if (lo.startsWith("content-type:")) ctype = h.substringAfter(":").trim()
            }
            val parts = line.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val raw = parts[1]
            val route = raw.substringBefore("?")
            val query = if (raw.contains("?")) raw.substringAfter("?") else ""
            val p = parseQuery(query)

            if (route == "/health") { respond(s, 200, "text/plain", "OK"); return }

            when (route) {
                "/state" -> respond(s, 200, "application/json", readState())
                "/videos" -> respond(s, 200, "application/json", listVideos())
                "/info" -> respond(s, 200, "application/json", infoJson(ctx))
                "/snapshot" -> { FrameHub.touch(); respondJpeg(s, FrameHub.latest()) }
                "/stream" -> { FrameHub.touch(); streamMjpeg(s) }
                "/cmd" -> respond(s, 200, "application/json", handleCmd(ctx, p))
                "/shell" -> respond(s, 200, "application/json", handleShell(p))
                "/screenshot" -> handleScreenshot(s)
                "/screenrec" -> respond(s, 200, "application/json", handleScreenRec(p))
                "/install" -> respond(s, 200, "application/json", handleInstall(ctx, p))
                "/upload" -> respond(s, 200, "application/json", handleUpload(ctx, ins, contentLength))
                else -> respond(s, 404, "application/json", notFoundJson())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "http handle: " + t.message)
        } finally {
            try { s.close() } catch (_: Throwable) {}
        }
    }

    private fun unauthorizedJson(): String = JSONObject().put("error", "unauthorized").toString()
    private fun notFoundJson(): String = JSONObject().put("error", "not_found").toString()

    /** 远程执行 shell，返回 stdout/stderr 合并文本。 */
    private fun handleShell(p: Map<String, String>): String {
        val cmd = p["cmd"] ?: return JSONObject().put("ok", false)
            .put("error", "no_cmd").toString()
        val to = (p["timeout"]?.toLongOrNull() ?: 15000L).coerceIn(1000L, 28000L)
        val out = ShellExec.run(cmd, to)
        return JSONObject().put("ok", true).put("root", ShellExec.isRoot())
            .put("cmd", cmd).put("output", out).toString()
    }

    /** 截屏，直接回 PNG。 */
    private fun handleScreenshot(s: Socket) {
        val f = ScreenCap.screenshot()
        if (f == null) {
            respond(s, 503, "application/json",
                JSONObject().put("ok", false).put("error", "screenshot_failed").toString())
            return
        }
        val data = f.readBytes()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " +
            data.size + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun handleScreenRec(p: Map<String, String>): String {
        val act = p["action"] ?: "status"
        return when (act) {
            "start" -> {
                val dur = p["dur"]?.toIntOrNull() ?: 0
                val r = ScreenCap.startRecord(dur)
                JSONObject().put("ok", !r.startsWith("ERR")).put("result", r).toString()
            }
            "stop" -> JSONObject().put("ok", true).put("result", ScreenCap.stopRecord()).toString()
            else -> JSONObject().put("ok", true)
                .put("recording", ScreenCap.isRecording())
                .put("file", ScreenCap.currentFile() ?: "").toString()
        }
    }

    /** 从 URL 下载 APK 并安装。 */
    private fun handleInstall(ctx: Context, p: Map<String, String>): String {
        val url = p["url"] ?: return JSONObject().put("ok", false)
            .put("error", "no_url").toString()
        return try {
            val f = File(ctx.filesDir, UP_NAME)
            URL(url).openStream().use { ins ->
                FileOutputStream(f).use { outs -> ins.copyTo(outs) }
            }
            val cmd = "cp " + f.absolutePath + " /data/local/tmp/dxam_up2.apk; chmod 644 /data/local/tmp/dxam_up2.apk; pm install -r -g /data/local/tmp/dxam_up2.apk"
            val log = ShellExec.run(cmd, 28000)
            JSONObject().put("ok", true).put("size", f.length())
                .put("result", log).toString()
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "err").toString()
        }
    }

    /** 接收 POST 上传的 APK 字节并安装。 */
    private fun handleUpload(ctx: Context, ins: InputStream, len: Int): String {
        if (len <= 0) return JSONObject().put("ok", false).put("error", "no_body").toString()
        return try {
            val f = File(ctx.filesDir, UP_NAME)
            FileOutputStream(f).use { outs ->
                val buf = ByteArray(65536)
                var got = 0
                while (got < len) {
                    val n = ins.read(buf, 0, minOf(buf.size, len - got))
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                    got += n
                }
            }
            val log = ShellExec.run("cp " + f.absolutePath + " /data/local/tmp/dxam_up2.apk && pm install -r -g /data/local/tmp/dxam_up2.apk", 28000)
            JSONObject().put("ok", true).put("size", f.length())
                .put("result", log).toString()
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "err").toString()
        }
    }

    /** 设置启动App时自动开启侦测开关。 */
    private fun setAutoStart(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_start_detect", on).apply()
    }

    private fun handleCmd(ctx: Context, p: Map<String, String>): String {
        val act = p["action"] ?: return JSONObject().put("ok", false)
            .put("error", "no_action").toString()
        return try {
            when (act) {
                "start_detect" -> fgs(ctx, DetectService::class.java, DetectService.ACTION_START)
                "stop_detect" -> fgs(ctx, DetectService::class.java, DetectService.ACTION_STOP)
                "detect_only" -> fgs(ctx, DetectService::class.java, DetectService.ACTION_START_ONLY)
                "exit" -> fgs(ctx, DetectService::class.java, DetectService.ACTION_EXIT)
                "force_stop" -> fgs(ctx, DetectService::class.java, DetectService.ACTION_FORCE_STOP)
                "start_rec" -> fgs(ctx, RecordService::class.java, null)
                "stop_rec" -> ctx.stopService(Intent(ctx, RecordService::class.java))
                "auto_on" -> setAutoStart(ctx, true)
                "auto_off" -> setAutoStart(ctx, false)
                "query" -> PresenceState.writeAsync(ctx, true)
                else -> return JSONObject().put("ok", false)
                    .put("error", "unknown_action").toString()
            }
            JSONObject().put("ok", true).put("action", act).toString()
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "err").toString()
        }
    }

    /** 直接对前台服务投递 intent（绕开广播，ColorOS 下可靠）。 */
    private fun fgs(ctx: Context, cls: Class<*>, action: String?) {
        val i = Intent(ctx, cls)
        if (action != null) i.action = action
        androidx.core.content.ContextCompat.startForegroundService(ctx, i)
    }

    private fun respondJpeg(s: Socket, data: ByteArray?) {
        val out: OutputStream = s.getOutputStream()
        if (data == null || data.isEmpty()) {
            val msg = "no frame".toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\nContent-Length: " + msg.size + "\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(msg)
        } else {
            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + data.size + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(data)
        }
        out.flush()
    }

    private fun streamMjpeg(s: Socket) {
        val out: OutputStream = s.getOutputStream()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.flush()
        var lastSent = 0L
        try {
            while (running && !s.isClosed) {
                FrameHub.touch()
                val data = FrameHub.latest()
                if (data != null && FrameHub.lastAt != lastSent) {
                    lastSent = FrameHub.lastAt
                    val part = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + data.size + "\r\n\r\n").toByteArray(Charsets.UTF_8)
                    out.write(part)
                    out.write(data)
                    out.write("\r\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                Thread.sleep(200)
            }
        } catch (_: Throwable) {
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val m = HashMap<String, String>()
        for (kv in q.split("&")) {
            val i = kv.indexOf("=")
            if (i > 0) {
                try {
                    m[URLDecoder.decode(kv.substring(0, i), "UTF-8")] =
                        URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                } catch (_: Throwable) {}
            }
        }
        return m
    }

    private fun readState(): String = try {
        val f = File("/sdcard/Download/presence_state.json")
        if (f.exists()) f.readText() else "{}"
    } catch (_: Throwable) { "{}" }

    private fun listVideos(): String {
        val arr = JSONArray()
        try {
            val fs = File("/sdcard/Movies/DxamRec").listFiles { f -> f.name.endsWith(".mp4") }
            fs?.sortedByDescending { it.lastModified() }?.take(200)?.forEach { f ->
                arr.put(JSONObject().put("name", f.name).put("size", f.length())
                    .put("mtime", f.lastModified()))
            }
        } catch (_: Throwable) {}
        return arr.toString()
    }

    private fun infoJson(ctx: Context): String = JSONObject()
        .put("port", port(ctx)).put("token", token(ctx))
        .put("running", running).put("root", ShellExec.isRoot())
        .put("screenRecording", ScreenCap.isRecording())
        .put("autoStartDetect", ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("auto_start_detect", true)).toString()

    private fun respond(s: Socket, code: Int, ctype: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val out: OutputStream = s.getOutputStream()
        val head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + ctype +
            "; charset=utf-8\r\nContent-Length: " + b.size +
            "\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(b)
        out.flush()
    }
}
