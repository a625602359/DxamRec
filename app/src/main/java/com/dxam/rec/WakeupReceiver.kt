package com.dxam.rec

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * v99: 定时自唤醒，对抗 ColorOS cached_apps_freezer。
 * AlarmManager 是系统级的，App 被冻结时仍会触发；
 * 系统投递广播前会先解冻目标进程，从而让 HTTP API / frpc 恢复响应。
 */
class WakeupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "WAKEUP tick at " + System.currentTimeMillis())
        // 排下一次
        schedule(context)
    }

    companion object {
        private const val TAG = "DxamRec"
        private const val ACTION = "com.dxam.rec.WAKEUP"
        private const val RC = 1001
        const val INTERVAL_MS = 5 * 60 * 1000L  // 5 分钟

        fun schedule(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    ctx, RC,
                    Intent(ctx, WakeupReceiver::class.java).setAction(ACTION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } catch (_: Throwable) {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
                Log.i(TAG, "wakeup scheduled in " + (INTERVAL_MS / 1000) + "s")
            } catch (t: Throwable) {
                Log.e(TAG, "wakeup schedule failed", t)
            }
        }

        fun cancel(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    ctx, RC,
                    Intent(ctx, WakeupReceiver::class.java).setAction(ACTION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                am.cancel(pi)
            } catch (_: Throwable) {}
        }
    }
}
