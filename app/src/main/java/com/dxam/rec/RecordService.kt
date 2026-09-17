package com.dxam.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File

/**
 * 手动录像服务。
 * v44：改用 MPEG-TS（同 DetectService），强杀不丢；停止后自动扫描进相册。
 */
class RecordService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var ts: TsRecorder? = null
    private var restarting = false
    private var recCount = 0
    private var curFile: File? = null

    @Volatile private var isRecording = false
    @Volatile private var segmentSwitching = false
    @Volatile private var recStartRt = 0L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var blinkOn = false

    private fun buildNoti(): Notification {
        val stopPi = PendingIntent.getBroadcast(
            this, 12,
            Intent(this, ControlReceiver::class.java).setAction("com.dxam.rec.STOP_REC"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DxamRec 后台录像")
            .setContentText("录像服务运行中（TS）")
            .setSmallIcon(if (blinkOn) R.drawable.ic_rec_dot else R.drawable.ic_rec_ring)
            .setExtras(android.os.Bundle().apply { putBoolean("oplus_smallicon_use_app_icon", false) })
            .setOngoing(true)
            .setShowWhen(false)
            .setLights(android.graphics.Color.RED, 500, 500)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止录像", stopPi)
            .build()
    }

    private val notiHeartbeat = object : Runnable {
        override fun run() {
            if (isRecording) blinkOn = !blinkOn
            try { getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNoti()) } catch (_: Throwable) {}
            main.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        main.post(notiHeartbeat)
        initCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!restarting) {
            restarting = true
            try {
                val restart = Intent(applicationContext, RecordService::class.java)
                val pi = PendingIntent.getService(this, 1, restart,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
                val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
                alarm.set(android.app.AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000, pi)
            } catch (t: Throwable) { Log.e(TAG, "restart failed", t) }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            try { mgr.deleteNotificationChannel(CHANNEL_ID) } catch (_: Throwable) {}
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "录像", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableLights(true); lightColor = android.graphics.Color.RED
                    enableVibration(false); setSound(null, null); setShowBadge(false)
                }
            )
        }
        val noti = buildNoti()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    private fun buildCamSelector(): CameraSelector {
        val wantId = PerfPrefs.camId(this).toString()
        return try {
            val cm = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val facing = cm.getCameraCharacteristics(wantId).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            val lf = if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            CameraSelector.Builder()
                .requireLensFacing(lf)
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == wantId } }
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "buildCamSelector failed, fallback back", t)
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private fun initCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val pv = Preview.Builder().build()
                preview = pv
                provider.unbindAll()
                provider.bindToLifecycle(this, buildCamSelector(), pv)
                startRecording()
            } catch (t: Throwable) { Log.e(TAG, "initCamera failed", t) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotationHint(): Int = try {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 90
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    } catch (_: Throwable) { 90 }

    private fun outDir(): File {
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DxamRec")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private val segmentRotateRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            Log.i(TAG, "rec segment rotate")
            segmentSwitching = true
            try { ts?.stop() } catch (_: Throwable) {}
            ts = null
            isRecording = false
            val f = curFile
            curFile = null
            if (f != null) scan(f)
            main.postDelayed({
                segmentSwitching = false
                if (!isRecording) startRecordingInternal()
            }, 1200L)
        }
    }

    private fun startRecording() {
        startRecordingInternal()
    }

    private fun startRecordingInternal() {
        val file = File(outDir(), "rec_" + System.currentTimeMillis() + ".mp4")
        val rec = TsRecorder(this, PerfPrefs.recW(this), PerfPrefs.recH(this), rotationHint(), PerfPrefs.recBitrate(this), false)
        if (!rec.prepare(file)) { Log.e(TAG, "rec MP4 prepare failed"); return }
        val s = rec.surface
        if (s == null) { Log.e(TAG, "rec MP4 surface null"); rec.stop(); return }
        ts = rec
        curFile = file
        preview?.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { req ->
            req.provideSurface(s, ContextCompat.getMainExecutor(this)) {}
        }
        main.postDelayed({
            val ok = ts?.start() ?: false
            Log.i(TAG, "rec MP4 start result=" + ok)
        }, 400L)
        isRecording = true; recCount++; recStartRt = SystemClock.elapsedRealtime()
        Log.i(TAG, "rec record start #" + recCount + " -> " + file.absolutePath)
        main.removeCallbacks(segmentRotateRunnable)
        if (PerfPrefs.recFormat(this) == 1) {
            main.postDelayed(segmentRotateRunnable, PerfPrefs.segMinutes(this) * 60_000L)
        }
    }

    private fun stopRecording() {
        main.removeCallbacks(segmentRotateRunnable)
        if (isRecording) {
            main.post { try { ts?.stop() } catch (_: Throwable) {} }
            ts = null
            isRecording = false
            curFile?.let { scan(it) }
        }
        recStartRt = 0L
    }

    /** 停止后把文件注册进媒体库，相册可见。 */
    private fun scan(f: File) {
        try {
            val mime = if (f.name.endsWith(".ts")) "video/mp2t" else "video/mp4"
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf(mime), null)
            Log.i(TAG, "rec scanned " + f.name)
        } catch (t: Throwable) { Log.e(TAG, "rec scan failed", t) }
    }

    override fun onDestroy() {
        main.removeCallbacks(notiHeartbeat)
        stopRecording()
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "dxamrec_channel"
        const val NOTI_ID = 1001
        const val TAG = "DxamRec"
        @Volatile var pendingFinalize = false
    }
}
