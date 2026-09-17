package com.dxam.rec

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.Executors

/**
 * 预览界面 v19：改用 MediaPipe ObjectDetector（efficientdet_lite0，与 DetectService 一致），
 * 大腿/局部身体识别稳定，绿框跟人。
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var overlay: OverlayView

    private var cameraProvider: ProcessCameraProvider? = null
    private var objectDetector: ObjectDetector? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var present = false
    @Volatile private var personStreak = 0
    @Volatile private var lastPersonAt = 0L
    private var retryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)
        overlay = OverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(overlay)

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 16, 24, 16)
            text = "检测：正在启动相机…"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 80
            }
        }
        root.addView(statusText)
        setContentView(root)

        initDetector()
        try { stopService(Intent(this, DetectService::class.java)) } catch (_: Throwable) {}
        sendBroadcast(Intent("com.dxam.rec.STOP_DETECT").setPackage(packageName))
        handler.postDelayed({ initCamera() }, 1500)
    }

    private fun initDetector() {
        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("efficientdet_lite0.tflite")
                .build()
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setScoreThreshold(0.3f)
                .setMaxResults(5)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            objectDetector = ObjectDetector.createFromOptions(this, opts)
            Log.i(TAG, "preview object detector ready")
        } catch (t: Throwable) { Log.e(TAG, "preview detector failed", t) }
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
                val pvB = Preview.Builder()
                if (PerfPrefs.previewHi(this) == 0) {
                    val rss = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                            Size(PerfPrefs.detectW(this), PerfPrefs.detectH(this)),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                    pvB.setResolutionSelector(rss)
                }
                val preview = pvB.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(Size(PerfPrefs.detectW(this), PerfPrefs.detectH(this)))
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, buildCamSelector(), preview, analysis)
                handler.postDelayed({
                    try { Log.i(TAG, "PV_RES=" + preview.resolutionInfo?.resolution + " want=" + PerfPrefs.detectW(this) + "x" + PerfPrefs.detectH(this)) } catch (_: Throwable) {}
                }, 1500)
                retryCount = 0
            } catch (t: Throwable) {
                Log.e(TAG, "preview camera failed (retry=$retryCount)", t)
                if (retryCount < 5) {
                    retryCount++
                    handler.postDelayed({ initCamera() }, 800)
                } else {
                    statusText.text = "相机被占用，请先关闭侦测/录像后重试"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

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
        } catch (t: Throwable) { Log.e(TAG, "preview proxyToBitmap failed", t); null }
    }

    private fun analyze(proxy: ImageProxy) {
        val od = objectDetector
        if (od == null) { proxy.close(); return }
        var person = false
        var box: RectF? = null
        var conf = 0f
        try {
            val bmp = proxyToBitmap(proxy)
            if (bmp != null) {
                val mpImage = BitmapImageBuilder(bmp).build()
                val result = od.detect(mpImage)
                for (d in result.detections()) {
                    for (c in d.categories()) {
                        if (c.categoryName().equals("person", true) && c.score() >= 0.3f) {
                            person = true
                            if (c.score() > conf) {
                                conf = c.score()
                                val b = d.boundingBox()
                                box = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) { Log.e(TAG, "preview object detect err", t) }
        proxy.close()

        val now = System.currentTimeMillis()
        if (person) {
            personStreak++
            if (personStreak >= 2) { present = true; lastPersonAt = now }
        } else {
            personStreak = 0
            if (now - lastPersonAt > 1500) present = false
        }
        val showBox = if (present) box else null
        val pct = (conf * 100).toInt()
        runOnUiThread {
            overlay.setBox(showBox, previewView.width, previewView.height, pct)
            statusText.text = if (present) "检测：当前有人" else "检测：当前无人"
        }
    }

    override fun onStop() {
        super.onStop()
        try { cameraProvider?.unbindAll() } catch (_: Throwable) {}
        sendBroadcast(Intent("com.dxam.rec.START_DETECT").setPackage(packageName))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { objectDetector?.close() } catch (_: Throwable) {}
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object { const val TAG = "DxamRec" }
}
