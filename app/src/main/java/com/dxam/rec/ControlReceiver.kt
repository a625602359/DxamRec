package com.dxam.rec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * v36：FORCE_STOP 不再直接 force-stop，改为交给 DetectService 优雅退出
 * （先停录、等 Finalize 落盘，再 force-stop），避免最后一段损坏。
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.dxam.rec.START_REC" -> {
                try { ContextCompat.startForegroundService(context, Intent(context, RecordService::class.java)) } catch (_: Throwable) {}
            }
            "com.dxam.rec.STOP_REC" -> {
                try { context.stopService(Intent(context, RecordService::class.java)) } catch (_: Throwable) {}
            }
            "com.dxam.rec.START_DETECT_ONLY" -> {
                try {
                    val i = Intent(context, DetectService::class.java).setAction(DetectService.ACTION_START_ONLY)
                    ContextCompat.startForegroundService(context, i)
                } catch (_: Throwable) {}
            }
            "com.dxam.rec.START_DETECT" -> {
                try {
                    val i = Intent(context, DetectService::class.java).setAction(DetectService.ACTION_START)
                    ContextCompat.startForegroundService(context, i)
                } catch (_: Throwable) {}
            }
            "com.dxam.rec.STOP_DETECT" -> {
                try {
                    val i = Intent(context, DetectService::class.java).setAction(DetectService.ACTION_STOP)
                    ContextCompat.startForegroundService(context, i)
                } catch (_: Throwable) {}
            }
            "com.dxam.rec.EXIT_DETECT" -> {
                try {
                    val i = Intent(context, DetectService::class.java).setAction(DetectService.ACTION_EXIT)
                    ContextCompat.startForegroundService(context, i)
                } catch (_: Throwable) {}
            }
            "com.dxam.rec.DETECT_FILE" -> {
                try {
                    val i = Intent(context, DetectService::class.java).setAction(DetectService.ACTION_DETECT_FILE)
                    intent.getStringExtra("path")?.let { i.putExtra("path", it) }
                    ContextCompat.startForegroundService(context, i)
                } catch (_: Throwable) {}
            }
            "com.dxam.rec.QUERY_PRESENCE" -> PresenceState.writeAsync(context, true)
            "com.dxam.rec.FORCE_STOP" -> {
                // 交给 DetectService 优雅退出；若服务不在，退回直接强停
                try {
                    val i = Intent(context, DetectService::class.java).setAction(DetectService.ACTION_FORCE_STOP)
                    ContextCompat.startForegroundService(context, i)
                } catch (_: Throwable) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c",
                            "setsid sh -c 'am force-stop com.dxam.rec' >/dev/null 2>&1 &"))
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
