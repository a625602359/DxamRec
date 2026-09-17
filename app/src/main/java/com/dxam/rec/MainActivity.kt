package com.dxam.rec

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val CMD_START = "am broadcast -a com.dxam.rec.START_REC -p com.dxam.rec -f 0x20"
    private val CMD_STOP = "am broadcast -a com.dxam.rec.STOP_REC -p com.dxam.rec -f 0x20"
    private val CMD_DET_ON = "am broadcast -a com.dxam.rec.START_DETECT -p com.dxam.rec -f 0x20"
    private val CMD_DET_OFF = "am broadcast -a com.dxam.rec.STOP_DETECT -p com.dxam.rec -f 0x20"
    private val CMD_QUERY = "am broadcast -a com.dxam.rec.QUERY_PRESENCE -p com.dxam.rec -f 0x20"
    private val CMD_READ = "cat /sdcard/Download/presence_state.json"
    private val CMD_DET_ONLY = "am broadcast -a com.dxam.rec.START_DETECT_ONLY -p com.dxam.rec -f 0x20"
    private val CMD_EXIT = "am broadcast -a com.dxam.rec.EXIT_DETECT -p com.dxam.rec -f 0x20"
    private val CMD_FORCE = "am broadcast -a com.dxam.rec.FORCE_STOP -p com.dxam.rec -f 0x20"

    private lateinit var statusTv: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateStatusCard()
            handler.postDelayed(this, 1500)
        }
    }

    private lateinit var dp: (Int) -> Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)

        val d = resources.displayMetrics.density
        dp = { v -> (d * v).toInt() }
        fun idxOf(arr: IntArray, v: Int, def: Int): Int {
            val i = arr.indexOf(v); return if (i < 0) def else i
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        content.addView(TextView(this).apply {
            text = "DxamRec"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
        })
        content.addView(TextView(this).apply {
            text = "后台录像 · 人形侦测 · v" + BuildConfig.VERSION_NAME
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(2), 0, dp(14))
        })

        statusTv = TextView(this).apply {
            text = "状态：读取中…"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        content.addView(card(statusTv))

        content.addView(sectionTitle("摄像头"))
        val camMgr = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val camIds = camMgr.cameraIdList
        val camLabels = camIds.map { id ->
            val fc = try { camMgr.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) } catch (_: Throwable) { null }
            val f = if (fc == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"
            f + " · ID " + id
        }.toTypedArray()
        val camIdInts = camIds.map { it.toIntOrNull() ?: 0 }.toIntArray()
        val r7 = spinnerRow("选择摄像头", camLabels, idxOf(camIdInts, PerfPrefs.camId(this), 0))
        content.addView(r7.first); content.addView(r7.second)

        content.addView(sectionTitle("快捷操作"))
        content.addView(primaryBtn("👁  打开预览") { openPreview() })
        content.addView(primaryBtn("▷  开启人形侦测") { startDetect() })
        content.addView(primaryBtn("■  关闭人形侦测") { stopDetect() })
        content.addView(primaryBtn("💾  保存视频并退出") { saveAndExit() })

        val autoStartCb = CheckBox(this).apply {
            text = "启动 App 时自动开启侦测"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            isChecked = getSharedPreferences("dxam_pref", Context.MODE_PRIVATE)
                .getBoolean("auto_start_detect", true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("dxam_pref", Context.MODE_PRIVATE)
                    .edit().putBoolean("auto_start_detect", checked).apply()
            }
        }
        content.addView(autoStartCb)

        content.addView(sectionTitle("网络"))
        val apiStatusTv = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        fun refreshApiStatus() {
            apiStatusTv.text = "HTTP API：" + (if (HttpApiServer.isRunning()) "运行中" else "已停止") +
                "  端口 " + HttpApiServer.port(this@MainActivity) +
                "\n实时画面：http://<server>:18080/stream" +
                "\n状态查询：http://<server>:18080/state"
        }
        refreshApiStatus()
        content.addView(apiStatusTv)

        content.addView(normalBtn("刷新网络状态") { refreshApiStatus() })
        content.addView(TextView(this).apply {
            text = "状态文件：/sdcard/Download/presence_state.json\n字段：有人 / 侦测中 / 录像中 / 距今毫秒 / 时间戳"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(14), 0, 0)
        })

        content.addView(sectionTitle("性能设置"))

        val detResOpts = arrayOf("160x120 极省", "176x144 最省", "320x240 省电", "480x360 均衡", "640x480 高清")
        val detResW = intArrayOf(160, 176, 320, 480, 640)
        val detResH = intArrayOf(120, 144, 240, 360, 480)
        val recResOpts = arrayOf("1280x720", "1920x1080")
        val recResW = intArrayOf(1280, 1920)
        val recResH = intArrayOf(720, 1080)
        val recBrOpts = arrayOf("4 Mbps", "6 Mbps", "8 Mbps", "10 Mbps")
        val recBrVal = intArrayOf(4000000, 6000000, 8000000, 10000000)

        val r1 = spinnerRow("检测分辨率", detResOpts, idxOf(detResW, PerfPrefs.detectW(this), 1))
        val r2 = editRow("检测间隔 (100-5000ms)", PerfPrefs.detectIntervalMs(this).toInt().toString())
        val r3 = spinnerRow("录像分辨率", recResOpts, idxOf(recResW, PerfPrefs.recW(this), 0))
        val r4 = spinnerRow("录像码率", recBrOpts, idxOf(recBrVal, PerfPrefs.recBitrate(this), 1))
        val fmtOpts = arrayOf("单文件 MP4", "分段 MP4")
        val fmtVal = intArrayOf(0, 1)
        val segOpts = arrayOf("1 分钟", "2 分钟", "3 分钟", "5 分钟", "10 分钟")
        val segVal = intArrayOf(1, 2, 3, 5, 10)
        val r5 = spinnerRow("录像格式", fmtOpts, idxOf(fmtVal, PerfPrefs.recFormat(this), 0))
        val r6 = spinnerRow("分段时长（分段MP4用）", segOpts, idxOf(segVal, PerfPrefs.segMinutes(this), 2))
        val pvOpts = arrayOf("源码原始(高清)", "跟随检测分辨率")
        val pvVal = intArrayOf(1, 0)
        val rPV = spinnerRow("预览画面", pvOpts, idxOf(pvVal, PerfPrefs.previewHi(this), 0))

        content.addView(r1.first); content.addView(r1.second)
        content.addView(r2.first); content.addView(r2.second)
        content.addView(r3.first); content.addView(r3.second)
        content.addView(r4.first); content.addView(r4.second)
        content.addView(r5.first); content.addView(r5.second)
        content.addView(r6.first); content.addView(r6.second)
        content.addView(rPV.first); content.addView(rPV.second)
        var uiReady = false
        handler.postDelayed({ uiReady = true }, 800)
        val saveAll: () -> Unit = {
            val i1 = r1.second.selectedItemPosition
            val intervalMs = (r2.second.text.toString().trim().toIntOrNull() ?: 333).coerceIn(100, 5000)
            val i3 = r3.second.selectedItemPosition
            val i4 = r4.second.selectedItemPosition
            val i5 = r5.second.selectedItemPosition
            val i6 = r6.second.selectedItemPosition
            val iPV = rPV.second.selectedItemPosition
            val i7 = r7.second.selectedItemPosition
            PerfPrefs.save(this, detResW[i1], detResH[i1], intervalMs,
                recResW[i3], recResH[i3], recBrVal[i4], fmtVal[i5], segVal[i6],
                camIdInts[i7.coerceIn(0, camIdInts.size - 1)])
            PerfPrefs.setPreviewHi(this, pvVal[iPV])
            sendBroadcast(Intent("com.dxam.rec.STOP_DETECT").setPackage(packageName))
            handler.postDelayed({
                sendBroadcast(Intent("com.dxam.rec.START_DETECT").setPackage(packageName))
            }, 900)
            Toast.makeText(this, "参数已应用", Toast.LENGTH_SHORT).show()
        }
        val autoApply: (Spinner) -> Unit = { sp ->
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!uiReady) return
                    saveAll()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        autoApply(r7.second)
        autoApply(r1.second)
        autoApply(r3.second)
        autoApply(r4.second)
        autoApply(r5.second)
        autoApply(r6.second)
        autoApply(rPV.second)
        r2.second.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && uiReady) saveAll() }
        content.addView(TextView(this).apply {
            text = "参数修改后自动生效。录像格式：单文件/分段 MP4（不再转码）。"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(4), 0, dp(8))
        })

        content.addView(sectionTitle("遥控命令"))
        val cmdBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        cmdBox.addView(cmdRow("开始录像", CMD_START))
        cmdBox.addView(cmdRow("停止录像", CMD_STOP))
        cmdBox.addView(cmdRow("开启侦测", CMD_DET_ON))
        cmdBox.addView(cmdRow("关闭侦测", CMD_DET_OFF))
        cmdBox.addView(cmdRow("刷新状态", CMD_QUERY))
        cmdBox.addView(cmdRow("读取状态文件", CMD_READ))
        cmdBox.addView(cmdRow("只侦测不录像", CMD_DET_ONLY))
        cmdBox.addView(cmdRow("只退出(真正退出)", CMD_EXIT))
        cmdBox.addView(cmdRow("彻底关闭(杀进程)", CMD_FORCE))

        val cmdToggle = CheckBox(this).apply {
            text = "显示遥控命令（点命令即复制）"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setOnCheckedChangeListener { _, checked ->
                cmdBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        content.addView(cmdToggle)
        content.addView(cmdBox)

        content.addView(sectionTitle("权限与保活"))
        content.addView(normalBtn("1. 授权（相机 / 通知）") { requestPerms() })
        content.addView(normalBtn("2. 申请忽略电池优化") { requestIgnoreBattery() })
        content.addView(normalBtn("3. 授权悬浮窗") { requestOverlay() })

        content.addView(sectionTitle("企业微信推送"))
        val webhookEt = EditText(this).apply {
            hint = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
            textSize = 13f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
            setText(
                getSharedPreferences(Pusher.PREF, Context.MODE_PRIVATE)
                    .getString(Pusher.KEY_WEBHOOK, "") ?: ""
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(webhookEt)
        content.addView(TextView(this).apply {
            text = "检测到人时推送通知。留空保存＝关闭推送。"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(4), 0, dp(8))
        })
        content.addView(normalBtn("保存推送地址") {
            val v = webhookEt.text.toString().trim()
            getSharedPreferences(Pusher.PREF, Context.MODE_PRIVATE)
                .edit().putString(Pusher.KEY_WEBHOOK, v).apply()
            Toast.makeText(this,
                if (v.isEmpty()) "已清空，推送关闭" else "已保存推送地址",
                Toast.LENGTH_SHORT).show()
        })
        content.addView(normalBtn("发送测试推送") {
            if (webhookEt.text.toString().trim().isEmpty()) {
                Toast.makeText(this, "请先填地址并保存", Toast.LENGTH_SHORT).show()
            } else {
                Pusher.test(this@MainActivity)
                Toast.makeText(this, "已发送（若 60 秒内推过会被节流）", Toast.LENGTH_SHORT).show()
            }
        })


        // v96: 折叠低频区块（点击标题展开/收起）
        fun collapseSec(name: String) {
            var idx = -1
            for (i in 0 until content.childCount) {
                val v = content.getChildAt(i)
                if (v is TextView && v.tag == "dxam_sec" && v.text == name) { idx = i; break }
            }
            if (idx < 0) return
            var end = idx + 1
            while (end < content.childCount) {
                val v = content.getChildAt(end)
                if (v is TextView && v.tag == "dxam_sec") break
                end++
            }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val moving = mutableListOf<View>()
            for (i in idx + 1 until end) moving.add(content.getChildAt(i))
            for (v in moving) { content.removeView(v); body.addView(v) }
            body.visibility = View.GONE
            content.addView(body, idx + 1)
            val tv = content.getChildAt(idx) as TextView
            tv.text = "▶  " + name
            tv.setOnClickListener {
                if (body.visibility == View.GONE) {
                    body.visibility = View.VISIBLE
                    tv.text = "▼  " + name
                } else {
                    body.visibility = View.GONE
                    tv.text = "▶  " + name
                }
            }
        }
        collapseSec("性能设置")
        collapseSec("遥控命令")
        collapseSec("权限与保活")
        collapseSec("企业微信推送")

        val scroll = ScrollView(this).apply {
            addView(content)
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        setContentView(scroll)

        // v122: HTTP API / FRP 已停用（省电）。界面状态刷新保留。
        refreshApiStatus()

        // 打开 App 自动开始侦测（走广播，服务常驻，不杀服务）
        val autoStart = getSharedPreferences("dxam_pref", Context.MODE_PRIVATE)
            .getBoolean("auto_start_detect", true)
        // v120: wakeup alarm removed for power saving
        if (autoStart) {
            sendBroadcast(Intent("com.dxam.rec.START_DETECT").setPackage(packageName))
        }
    }

    private fun editRow(label: String, value: String): Pair<TextView, EditText> {
        val t = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, dp(6), 0, dp(2))
        }
        val et = EditText(this).apply {
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        return Pair(t, et)
    }

    private fun spinnerRow(label: String, items: Array<String>, sel: Int): Pair<TextView, Spinner> {
        val t = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, dp(6), 0, dp(2))
        }
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(sel.coerceIn(0, items.size - 1))
        }
        return Pair(t, sp)
    }

    private fun card(child: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        box.addView(child)
        return box
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        tag = "dxam_sec"
        text = t
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#666666"))
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun primaryBtn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2F6BFF"))
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

    private fun normalBtn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.WHITE)
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }

    private fun cmdRow(label: String, cmd: String): TextView =
        TextView(this).apply {
            text = "• $label\n$cmd"
            textSize = 12f
            setTextColor(Color.parseColor("#555555"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#EFEFF2"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            layoutParams = lp
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cmd", cmd))
                Toast.makeText(this@MainActivity, "已复制：$label", Toast.LENGTH_SHORT).show()
            }
        }

    private fun updateStatusCard() {
        try {
            val f = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), PresenceState.FILE_NAME)
            if (!f.exists()) { statusTv.text = "状态：尚未运行（无状态文件）"; return }
            val o = JSONObject(f.readText())
            val present = o.optBoolean("有人", o.optBoolean("present"))
            val det = o.optBoolean("侦测中", o.optBoolean("detecting"))
            val rec = o.optBoolean("录像中", o.optBoolean("recording"))
            val ago = o.optLong("距今毫秒", o.optLong("last_seen_ago_ms", -1))
            val agoStr = if (ago < 0) "—" else "${ago / 1000}s 前"
            val recDurMs = o.optLong("录像时长毫秒", o.optLong("rec_duration_ms", 0))
            val recSec = recDurMs / 1000
            val recDurStr = String.format("%02d:%02d", recSec / 60, recSec % 60)
            statusTv.text = buildString {
                append("当前状态：")
                append(if (present) "当前有人 🟢" else "当前无人 ⚪")
                append("\n侦测服务：")
                append(if (det) "运行中" else "已停止")
                append("　录像：")
                if (rec) append("录制中 " + recDurStr) else append("空闲")
                append("\n最近有人：")
                append(agoStr)
            }
            statusTv.setTextColor(if (present) Color.parseColor("#1B8A3A") else Color.parseColor("#333333"))
        } catch (_: Throwable) { statusTv.text = "状态：读取失败" }
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun requestPerms() {
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) { Toast.makeText(this, "权限已全部授予", Toast.LENGTH_SHORT).show(); return }
        ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
    }

    private fun requestIgnoreBattery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            } else Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }

    private fun requestOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            } else Toast.makeText(this, "已授予悬浮窗", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }

    private fun startDetect() {
        sendBroadcast(Intent("com.dxam.rec.START_DETECT").setPackage(packageName))
        Toast.makeText(this, "人形侦测已开启", Toast.LENGTH_SHORT).show()
    }

    private fun stopDetect() {
        sendBroadcast(Intent("com.dxam.rec.STOP_DETECT").setPackage(packageName))
        Toast.makeText(this, "人形侦测已停止（服务常驻）", Toast.LENGTH_SHORT).show()
    }

    private fun saveAndExit() {
        sendBroadcast(Intent("com.dxam.rec.FORCE_STOP").setPackage(packageName))
        Toast.makeText(this, "正在保存视频并退出...", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ try { finishAffinity() } catch (_: Throwable) {} }, 2500)
    }

    private fun openPreview() { startActivity(Intent(this, PreviewActivity::class.java)) }
}
