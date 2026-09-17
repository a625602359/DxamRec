package com.dxam.rec

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 企业微信推送。
 * webhook 来源（App 设置优先，其次配置文件）：
 *   1. App 内「推送设置」保存的值（SharedPreferences）
 *   2. /sdcard/Download/dxam_push.txt 里以 webhook= 开头的一行
 *
 * v33：触发点改为「每次开始录像」，节流 5 秒；测试推送不受节流影响。
 */
object Pusher {
    private const val TAG = "DxamRec"
    private const val CFG = "/sdcard/Download/dxam_push.txt"
    private const val THROTTLE_MS = 5_000L

    const val PREF = "dxam_pref"
    const val KEY_WEBHOOK = "webhook"

    @Volatile private var lastPushAt = 0L

    private fun fromPrefs(ctx: Context?): String? = try {
        ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            ?.getString(KEY_WEBHOOK, null)
            ?.trim()
            ?.takeIf { it.startsWith("http") }
    } catch (_: Throwable) { null }

    private fun fromFile(): String? = try {
        val f = File(CFG)
        if (!f.exists()) null
        else f.readLines().firstOrNull { it.startsWith("webhook=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.startsWith("http") }
    } catch (t: Throwable) {
        Log.w(TAG, "push: read cfg failed: " + t.message); null
    }

    private fun webhookUrl(ctx: Context?): String? = fromPrefs(ctx) ?: fromFile()

    /**
     * 每次「开始录像」时调用，带 5 秒节流防止极端频繁。
     */
    fun notifyRecordStart(ctx: Context?) {
        val now = System.currentTimeMillis()
        if (now - lastPushAt < THROTTLE_MS) {
            Log.i(TAG, "push: throttled, skip")
            return
        }
        lastPushAt = now
        send(ctx, "🔔 守影：检测到人，开始录像")
    }

    /**
     * 测试推送：不受节流影响，也不占用真人推送的额度。
     */
    fun test(ctx: Context?) {
        send(ctx, "🔔 守影：测试推送（手动触发）")
    }

    private fun send(ctx: Context?, content: String) {
        val url = webhookUrl(ctx) ?: run {
            Log.i(TAG, "push: no webhook configured"); return
        }

        Thread {
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                val body = JSONObject()
                    .put("msgtype", "text")
                    .put("text", JSONObject().put("content", content + "\n时间：" + ts))
                    .toString()

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val resp = try { conn.inputStream.bufferedReader().readText() } catch (_: Throwable) { "" }
                conn.disconnect()
                Log.i(TAG, "push: code=" + code + " resp=" + resp)
            } catch (t: Throwable) {
                Log.e(TAG, "push: failed", t)
            }
        }.start()
    }
}
