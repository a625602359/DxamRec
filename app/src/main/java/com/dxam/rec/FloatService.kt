package com.dxam.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * 悬浮窗保活服务：屏幕常驻一个小窗，系统视角里 App 一直可见，
 * 同时提供“停止录像”快捷入口。
 */
class FloatService : Service() {

    private var wm: WindowManager? = null
    private var floatView: View? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNoti()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，停止")
            stopSelf()
            return
        }
        addFloatView()
    }

    private fun startForegroundNoti() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_F, "悬浮窗保活", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_F)
            .setContentTitle("DxamRec 悬浮窗保活中")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_F, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_F, n)
        }
    }

    private fun addFloatView() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding((d * 10).toInt(), (d * 6).toInt(), (d * 10).toInt(), (d * 6).toInt())
        }

        val title = TextView(this).apply {
            text = "DxamRec v" + BuildConfig.VERSION_NAME + " 录制中"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val stop = TextView(this).apply {
            text = "点击停止"
            setTextColor(Color.parseColor("#FF6666"))
            textSize = 13f
            setPadding(0, (d * 4).toInt(), 0, 0)
            setOnClickListener {
                try { stopService(Intent(this@FloatService, RecordService::class.java)) } catch (_: Throwable) {}
            }
        }
        box.addView(title)
        box.addView(stop)

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (d * 8).toInt()
            y = (d * 120).toInt()
        }

        // 拖动
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    try { wm?.updateViewLayout(floatView, lp) } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        }

        floatView = box
        try {
            wm?.addView(floatView, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed", t)
        }
    }

    override fun onDestroy() {
        try { floatView?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        floatView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_F = "dxamrec_float"
        const val NOTI_F = 1002
        const val TAG = "DxamRec"
    }
}
