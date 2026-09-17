package com.dxam.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 人形侦测服务。
 * v46：省电优化——分析分辨率降到 640x480；检测帧率限到约 5fps。
 */
class DetectService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var objectDetector: ObjectDetector? = null
    private var ts: TsRecorder? = null
    private var curFile: File? = null
    private var dummyTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var detecting = false
    @Volatile private var detectOnly = false
    @Volatile private var isRecording = false
    @Volatile private var lastPersonAt = 0L
    @Volatile private var personStreak = 0
    @Volatile private var recCount = 0
    @Volatile private var seenCount = 0
    @Volatile private var recStartRt = 0L
    @Volatile private var lastDetectAt = 0L
    @Volatile private var detectIntervalMs = 333L
    @Volatile private var cameraBound = false
    @Volatile private var segmentSwitching = false
    @Volatile private var streamOnly = false
    @Volatile private var blinkOn = false
    private val streamKeepMs = 15000L
    private val frameCount = AtomicInteger(0)

    @Volatile private var iconRec = false
    private fun applyRecIcon(rec: Boolean) {
        if (rec == iconRec) return
        iconRec = rec
        try {
            val pm = packageManager
            val nm = android.content.ComponentName(this, "$packageName.NormalIcon")
            val rc = android.content.ComponentName(this, "$packageName.RecIcon")
            val D = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val E = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val K = android.content.pm.PackageManager.DONT_KILL_APP
            pm.setComponentEnabledSetting(nm, if (rec) D else E, K)
            pm.setComponentEnabledSetting(rc, if (rec) E else D, K)
        } catch (_: Throwable) {}
    }


    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            PresenceState.frames = frameCount.get()
            PresenceState.recDurationMs = if (isRecording && recStartRt > 0) SystemClock.elapsedRealtime() - recStartRt else 0L
            PresenceState.writeAsync(this@DetectService, false)
            if (isRecording) blinkOn = !blinkOn
            applyRecIcon(isRecording)
            refreshNoti()
            main.postDelayed(this, if (isRecording) 500L else 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        ensureChannel()
        startAsForeground()
        initDetectors()
        PresenceState.detecting = false
        PresenceState.writeAsync(this, true)
        // v120: heartbeat removed for power saving (user request)
        Thread {
            try { TsToMp4.convertLeftovers(outDir()) } catch (_: Throwable) {}
            try { TsToMp4.scanAll(outDir()) } catch (_: Throwable) {}
        }.start()
        Log.i(TAG, "service created (resident, v46)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val act = intent?.action
        if (act == null) {
            if (exitMarkerExists()) {
                Log.i(TAG, "sticky restart but exit marked, stopSelf")
                try { stopSelf() } catch (_: Throwable) {}
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        clearExitMarker()
        when (act) {
            ACTION_STOP -> stopDetecting()
            ACTION_EXIT -> exitGracefully()
            ACTION_FORCE_STOP -> forceStopGracefully()
            ACTION_DETECT_FILE -> detectImageFile(intent?.getStringExtra("path"))
            ACTION_START_ONLY -> startDetecting(true); else -> startDetecting(false)
        }
        return START_STICKY
    }

    private fun exitMarkerFile() = java.io.File(filesDir, ".exit_requested")
    private fun exitMarkerExists() = exitMarkerFile().exists()
    private fun clearExitMarker() { try { exitMarkerFile().delete() } catch (_: Throwable) {} }



    private fun startDetecting(only: Boolean = false) {
        streamOnly = false
        detectOnly = only
        detecting = true
        detectIntervalMs = PerfPrefs.detectIntervalMs(this)
        Log.i(TAG, "perf: detect=" + PerfPrefs.detectW(this) + "x" + PerfPrefs.detectH(this) +
            " interval=" + detectIntervalMs + "ms rec=" + PerfPrefs.recW(this) + "x" +
            PerfPrefs.recH(this) + "@" + PerfPrefs.recBitrate(this))
        if (!cameraBound) initCamera()
        PresenceState.detecting = true
        refreshNoti()
        PresenceState.writeAsync(this, true)
        Log.i(TAG, "detect START")
    }

    private fun releaseCamera() {
        if (!cameraBound) return
        try { cameraProvider?.unbindAll() } catch (_: Throwable) {}
        try { dummySurface?.release() } catch (_: Throwable) {}
        try { dummyTexture?.release() } catch (_: Throwable) {}
        dummySurface = null; dummyTexture = null
        cameraBound = false
        Log.i(TAG, "camera released (paused)")
    }

    private fun stopDetecting() {
        detecting = false
        segmentSwitching = false
        stopRecording()
        releaseCamera()
        PresenceState.detecting = false
        PresenceState.recording = false
        PresenceState.present = false
        refreshNoti()
        PresenceState.writeAsync(this, true)
        Log.i(TAG, "detect STOP (service kept)")
    }

    /** EXIT：真正退出服务（不杀进程，无 root 可用）。 */
    private fun exitGracefully() {
        Log.i(TAG, "EXIT: begin")
        detecting = false
        stopRecording()
        PresenceState.detecting = false
        PresenceState.present = false
        PresenceState.recording = false
        PresenceState.recDurationMs = 0L
        PresenceState.writeAsync(this, true)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        try { stopSelf() } catch (_: Throwable) {}
        Log.i(TAG, "EXIT: stopSelf done")
    }

    private fun detectImageFile(pathArg: String?) {
        val imgPath = if (pathArg.isNullOrBlank()) "/sdcard/Download/dxam_test.jpg" else pathArg
        Thread {
            val sb = StringBuilder()
            fun line(t: String) { sb.append(t).append(System.lineSeparator()) }
            try {
                val f = java.io.File(imgPath)
                if (!f.exists()) {
                    line("ERROR: file not found: " + f.absolutePath)
                } else {
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp == null) {
                        line("ERROR: decode failed")
                    } else {
                        line("file=" + f.name + " size=" + bmp.width + "x" + bmp.height)
                        val od = objectDetector
                        if (od == null) {
                            line("ERROR: objectDetector null")
                        } else {
                            val mpImage = BitmapImageBuilder(bmp).build()
                            val result = od.detect(mpImage)
                            var person = false
                            var bestPersonScore = 0f
                            var idx = 0
                            for (d in result.detections()) {
                                idx++
                                val bb = d.boundingBox()
                                val sb2 = StringBuilder()
                                sb2.append("det#").append(idx)
                                   .append(" box=[").append(bb.left).append(",").append(bb.top)
                                   .append(",").append(bb.right).append(",").append(bb.bottom).append("]")
                                for (c in d.categories()) {
                                    sb2.append(" ").append(c.categoryName())
                                       .append("=").append(String.format("%.3f", c.score()))
                                    if (c.categoryName().equals("person", true)) {
                                        if (c.score() >= 0.3f) person = true
                                        if (c.score() > bestPersonScore) bestPersonScore = c.score()
                                    }
                                }
                                line(sb2.toString())
                            }
                            line("count=" + idx)
                            line("person_ge_0.3=" + person)
                            line("best_person_score=" + String.format("%.3f", bestPersonScore))
                        }
                    }
                }
            } catch (t: Throwable) {
                line("ERROR: " + t.toString())
                Log.e(TAG, "detectImageFile failed", t)
            }
            val text = sb.toString()
            Log.i(TAG, "=== IMG DETECT ===" + System.lineSeparator() + text + "=== END ===")
            try {
                java.io.File("/sdcard/Download/dxam_detect_result.txt").writeText(text)
            } catch (_: Throwable) {}
        }.start()
    }

    private fun forceStopGracefully() {
        Log.i(TAG, "FORCE_STOP(save+exit): begin")
        detecting = false
        var f: File? = null
        if (isRecording) {
            try { ts?.stop() } catch (_: Throwable) {}
            ts = null
            isRecording = false
            try { attachDummySurface() } catch (_: Throwable) {}
            f = curFile
            curFile = null
        }
        recStartRt = 0L
        PresenceState.recording = false
        try { stopService(Intent(this, RecordService::class.java)) } catch (_: Throwable) {}
        val toConvert = f
        if (toConvert != null && toConvert.name.endsWith(".mp4")) {
            try { MediaScannerConnection.scanFile(applicationContext, arrayOf(toConvert.absolutePath), arrayOf("video/mp4"), null) } catch (_: Throwable) {}
            Log.i(TAG, "FORCE_STOP: saved " + toConvert.name)
        }
        Thread {
            if (toConvert != null && toConvert.name.endsWith(".ts")) {
                try {
                    val mp4 = File(toConvert.parentFile, toConvert.name.removeSuffix(".ts") + ".mp4")
                    if (TsToMp4.convert(toConvert, mp4) && mp4.length() > 0) {
                        try { toConvert.delete() } catch (_: Throwable) {}
                        try { MediaScannerConnection.scanFile(applicationContext, arrayOf(mp4.absolutePath), arrayOf("video/mp4"), null) } catch (_: Throwable) {}
                        Log.i(TAG, "FORCE_STOP: saved " + mp4.name)
                    } else {
                        try { MediaScannerConnection.scanFile(applicationContext, arrayOf(toConvert.absolutePath), arrayOf("video/mp2t"), null) } catch (_: Throwable) {}
                        Log.w(TAG, "FORCE_STOP: convert failed, keep ts")
                    }
                } catch (t: Throwable) { Log.e(TAG, "FORCE_STOP convert err", t) }
            }
            main.post {
                PresenceState.detecting = false
                PresenceState.present = false
                PresenceState.recording = false
                PresenceState.recDurationMs = 0L
                PresenceState.writeAsync(this, true)
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                try { stopService(Intent(this, FloatService::class.java)) } catch (_: Throwable) {}
                try { stopSelf() } catch (_: Throwable) {}
                Thread {
                    try { Thread.sleep(400) } catch (_: Throwable) {}
                    val done = try {
                        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", "am force-stop com.dxam.rec"))
                        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        true
                    } catch (_: Throwable) { false }
                    Log.i(TAG, "FORCE_STOP: su done=" + done)
                    if (!done) {
                        try { java.io.File(filesDir, ".exit_requested").createNewFile() } catch (_: Throwable) {}
                        try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
                        try { System.exit(0) } catch (_: Throwable) {}
                    }
                }.start()
            }
        }.start()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            try {
                try { nm.deleteNotificationChannel("dxamrec_detect") } catch (_: Throwable) {}
                val old = nm.getNotificationChannel(CHANNEL_D)
                if (old != null && old.importance != NotificationManager.IMPORTANCE_HIGH) {
                    nm.deleteNotificationChannel(CHANNEL_D)
                    Log.i(TAG, "channel was imp=" + old.importance + ", deleted to recreate HIGH")
                }
            } catch (t: Throwable) { Log.e(TAG, "channel check failed", t) }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_D, "人形侦测", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun pi(rc: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(this, rc,
            Intent(this, ControlReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun fmtDur(ms: Long): String {
        if (ms < 0) return "00:00"
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss)
        else String.format("%02d:%02d", m, ss)
    }

    private fun buildNoti(): Notification {
        val startPi = pi(11, "com.dxam.rec.START_DETECT")
        val stopPi = pi(12, "com.dxam.rec.STOP_DETECT")
        val killPi = pi(13, "com.dxam.rec.FORCE_STOP")
        val title = when {
            isRecording -> "● 正在录像（检测到人）"
            detecting -> "○ 侦测中（等待目标）"
            else -> "⏸ 侦测已暂停（服务常驻）"
        }
        val text = when {
            isRecording -> {
                val dur = if (recStartRt > 0) fmtDur(SystemClock.elapsedRealtime() - recStartRt) else "00:00"
                "已录 $dur · 本次第 ${recCount} 段"
            }
            detecting -> "DxamRec · 分析 ${frameCount.get()} 帧 · 已识别 $seenCount 次"
            else -> "DxamRec · 待命，可用广播随时开启"
        }
        return NotificationCompat.Builder(this, CHANNEL_D)
            .setContentTitle(title).setContentText(text)
            .setSubText("DxamRec v" + BuildConfig.VERSION_NAME)
            .setSmallIcon(if (isRecording) (if (blinkOn) R.drawable.ic_rec_dot else R.drawable.ic_rec_ring) else R.drawable.ic_normal)
            .setExtras(android.os.Bundle().apply { putBoolean("oplus_smallicon_use_app_icon", false) })
            .setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .setSilent(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_play, "开始侦测", startPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止侦测", stopPi)
            .addAction(android.R.drawable.ic_lock_power_off, "彻底关闭", killPi)
            .build()
    }

    private fun startAsForeground() {
        val n = buildNoti()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI_D, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTI_D, n)
    }

    private fun refreshNoti() {
        try { NotificationManagerCompat.from(this).notify(NOTI_D, buildNoti()) } catch (_: Throwable) {}
    }

    private fun initDetectors() {
        try {
            val base = BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build()
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base).setScoreThreshold(0.3f).setMaxResults(5)
                .setRunningMode(RunningMode.IMAGE).build()
            objectDetector = ObjectDetector.createFromOptions(this, opts)
            Log.i(TAG, "object detector ready")
        } catch (t: Throwable) { Log.e(TAG, "initObjectDetector failed", t) }
    }

    private fun initCamera() {
        if (cameraBound) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val pv = Preview.Builder().build()
                preview = pv
                val anBuilder = ImageAnalysis.Builder()
                    .setTargetResolution(Size(PerfPrefs.detectW(this), PerfPrefs.detectH(this)))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                try {
                    Camera2Interop.Extender(anBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range.create(1, 2))
                } catch (_: Throwable) {}
                val an = anBuilder.build()
                an.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                analysis = an
                bindWithRetry(provider, pv, an, 0)
            } catch (t: Throwable) { Log.e(TAG, "initCamera failed", t) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindWithRetry(provider: ProcessCameraProvider, pv: Preview, an: ImageAnalysis, attempt: Int) {
        main.postDelayed({
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, buildCamSelector(), pv, an)
                attachDummySurface()
                cameraBound = true
                Log.i(TAG, "camera bound (attempt=" + attempt + ")")
            } catch (t: Throwable) {
                Log.e(TAG, "bind attempt " + attempt + " failed", t)
                if (attempt < 3) bindWithRetry(provider, pv, an, attempt + 1)
                else Log.e(TAG, "bind gave up")
            }
        }, if (attempt == 0) 300L else 600L)
    }

    private fun buildCamSelector(): CameraSelector {
        val wantId = PerfPrefs.camId(this).toString()
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facing = cm.getCameraCharacteristics(wantId).get(CameraCharacteristics.LENS_FACING)
            val lf = if (facing == CameraCharacteristics.LENS_FACING_FRONT)
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

    private fun attachDummySurface() = main.post {
        try {
            val tex = SurfaceTexture(10)
            dummyTexture = tex
            val s = Surface(tex)
            dummySurface = s
            preview?.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { req ->
                req.provideSurface(s, ContextCompat.getMainExecutor(this)) {}
            }
        } catch (t: Throwable) { Log.e(TAG, "attachDummy failed", t) }
    }

    private fun attachRecordingSurface(s: Surface) = main.post {
        try {
            preview?.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { req ->
                req.provideSurface(s, ContextCompat.getMainExecutor(this)) {}
            }
            main.postDelayed({
                val ok = ts?.start() ?: false
                Log.i(TAG, "TS start result=$ok")
            }, 400L)
        } catch (t: Throwable) { Log.e(TAG, "attachSurface failed", t) }
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

    private fun proxyToBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            val plane = proxy.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * proxy.width
            val bmpW = proxy.width + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(bmpW, proxy.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, proxy.width, proxy.height)
            val rot = proxy.imageInfo.rotationDegrees
            if (rot == 0) cropped else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
            }
        } catch (t: Throwable) { Log.e(TAG, "proxyToBitmap failed", t); null }
    }

    private fun analyze(proxy: ImageProxy) {
        if (!detecting && !streamOnly) { proxy.close(); return }
        val nowMs = SystemClock.elapsedRealtime()
        val interval = if (streamOnly) 100L else detectIntervalMs
        if (nowMs - lastDetectAt < interval) { proxy.close(); return }
        lastDetectAt = nowMs

        var person = false
        try {
            val bmp = proxyToBitmap(proxy)
            if (bmp != null) {
                if (!streamOnly) {
                    val od = objectDetector
                    if (od != null) {
                        val mpImage = BitmapImageBuilder(bmp).build()
                        val result = od.detect(mpImage)
                        for (d in result.detections()) {
                            for (c in d.categories()) {
                                if (c.categoryName().equals("person", true) && c.score() >= 0.3f) { person = true; break }
                            }
                            if (person) break
                        }
                    }
                }
            }
        } catch (t: Throwable) { Log.e(TAG, "object detect err", t) }
        proxy.close()
        if (!streamOnly) finishFrame(person)
    }

    private fun finishFrame(person: Boolean) {
        if (!detecting) return
        val n = frameCount.incrementAndGet()
        PresenceState.frames = n
        val nowRt = SystemClock.elapsedRealtime()
        if (person) {
            personStreak++
            if (personStreak >= ENTER_STREAK) {
                lastPersonAt = nowRt
                PresenceState.lastSeenAt = System.currentTimeMillis()
                if (!PresenceState.present) {
                    PresenceState.present = true
                    PresenceState.writeAsync(this, true)
                    refreshNoti()
                    Log.i(TAG, "person ENTER")
                }
                if (!detectOnly && !isRecording && !segmentSwitching) { seenCount++; PresenceState.seenCount = seenCount; startRecording() }
            }
        } else {
            personStreak = 0
            if (PresenceState.present && nowRt - lastPersonAt > NO_PERSON_STOP_MS) {
                PresenceState.present = false
                PresenceState.writeAsync(this, true)
                refreshNoti()
                Log.i(TAG, "person EXIT")
            }
            if (isRecording && nowRt - lastPersonAt > NO_PERSON_STOP_MS) stopRecording()
        }
        if (n % 30 == 0) Log.i(TAG, "frame#$n person=$person")
    }

    private fun outDir(): File {
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DxamRec")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private val segmentRotateRunnable = object : Runnable {
        override fun run() {
            if (!isRecording || !detecting) return
            Log.i(TAG, "segment rotate")
            segmentSwitching = true
            try { ts?.stop() } catch (_: Throwable) {}
            ts = null
            isRecording = false
            val f = curFile
            curFile = null
            if (f != null) finishFile(f)
            attachDummySurface()
            main.postDelayed({
                segmentSwitching = false
                if (detecting && !isRecording) startRecordingInternal(true)
            }, 1200L)
        }
    }

    private fun startRecording() {
        if (!detecting) return
        startRecordingInternal(false)
    }

    private fun startRecordingInternal(isSegment: Boolean) {
        val file = File(outDir(), "det_" + System.currentTimeMillis() + ".mp4")
        val rec = TsRecorder(this, PerfPrefs.recW(this), PerfPrefs.recH(this), rotationHint(), PerfPrefs.recBitrate(this), false)
        if (!rec.prepare(file)) { Log.e(TAG, "MP4 prepare failed"); return }
        val s = rec.surface
        if (s == null) { Log.e(TAG, "MP4 surface null"); rec.stop(); return }
        ts = rec
        curFile = file
        attachRecordingSurface(s)
        isRecording = true; recCount++; recStartRt = SystemClock.elapsedRealtime()
        PresenceState.recording = true; PresenceState.recCount = recCount
        refreshNoti()
        PresenceState.writeAsync(this, true)
        Log.i(TAG, "det record start #" + recCount + " -> " + file.absolutePath)
        if (!isSegment) Pusher.notifyRecordStart(this)
        main.removeCallbacks(segmentRotateRunnable)
        if (PerfPrefs.recFormat(this) == 1) {
            main.postDelayed(segmentRotateRunnable, PerfPrefs.segMinutes(this) * 60_000L)
        }
    }

    private fun stopRecording() {
        main.removeCallbacks(segmentRotateRunnable)
        if (isRecording) {
            val r = ts
            ts = null
            isRecording = false
            try { r?.stop() } catch (_: Throwable) {}
            attachDummySurface()
            val f = curFile
            curFile = null
            if (f != null) finishFile(f)
        }
        recStartRt = 0L
        PresenceState.recording = false; refreshNoti()
        PresenceState.writeAsync(this, true)
        Log.i(TAG, "det record stop, total=" + recCount)
    }

    private fun finishFile(f: File) {
        if (f.name.endsWith(".ts")) convertAsync(f) else scanVideo(f)
    }

    private fun scanVideo(f: File) {
        try {
            MediaScannerConnection.scanFile(applicationContext, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
            Log.i(TAG, "scanned " + f.name)
        } catch (_: Throwable) {}
    }

    private fun convertAsync(tsFile: File) {
        Thread {
            try {
                val dir = tsFile.parentFile ?: return@Thread
                val mp4 = File(dir, tsFile.name.removeSuffix(".ts") + ".mp4")
                if (TsToMp4.convert(tsFile, mp4) && mp4.length() > 0) {
                    try { tsFile.delete() } catch (_: Throwable) {}
                    scanVideo(mp4)
                    Log.i(TAG, "det ts->mp4 done: " + mp4.name)
                }
            } catch (t: Throwable) { Log.e(TAG, "convertAsync failed", t) }
        }.start()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: finalize segment & stop")
        try {
            main.removeCallbacks(segmentRotateRunnable)
            if (isRecording) {
                try { ts?.stop() } catch (_: Throwable) {}
                ts = null
                isRecording = false
                val f = curFile
                curFile = null
                if (f != null) finishFile(f)
            }
            recStartRt = 0L
            PresenceState.recording = false
            PresenceState.detecting = false
            PresenceState.present = false
            PresenceState.recDurationMs = 0L
            PresenceState.writeAsync(this, true)
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            try { stopSelf() } catch (_: Throwable) {}
        } catch (t: Throwable) { Log.e(TAG, "onTaskRemoved err", t) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacks(heartbeatRunnable)
        streamOnly = false
        stopRecording()
        try { objectDetector?.close() } catch (_: Throwable) {}
        try { dummySurface?.release() } catch (_: Throwable) {}
        try { dummyTexture?.release() } catch (_: Throwable) {}
        cameraProvider?.unbindAll()
        cameraBound = false
        PresenceState.detecting = false
        PresenceState.recording = false
        PresenceState.present = false
        PresenceState.writeAsync(this, true)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_D = "dxamrec_detect3"
        const val NOTI_D = 1003
        const val TAG = "DxamRec"
        const val NO_PERSON_STOP_MS = 5000L
        const val ENTER_STREAK = 2
        const val DETECT_INTERVAL_MS = 333L
        const val DETECT_W = 480
        const val DETECT_H = 360
        const val REC_W = 1280
        const val REC_H = 720
        const val REC_BITRATE = 6_000_000
        const val ACTION_START = "com.dxam.rec.ACTION_START"
        const val ACTION_START_ONLY = "com.dxam.rec.ACTION_START_ONLY"
        const val ACTION_STOP = "com.dxam.rec.ACTION_STOP"
        const val ACTION_FORCE_STOP = "com.dxam.rec.ACTION_FORCE_STOP"
        const val ACTION_DETECT_FILE = "com.dxam.rec.ACTION_DETECT_FILE"
        const val ACTION_EXIT = "com.dxam.rec.ACTION_EXIT"
        @Volatile var appCtx: Context? = null
    }
}
