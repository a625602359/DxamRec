package com.dxam.rec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 开机自启（v32）：只拉起常驻的人形侦测服务并开始侦测。
 * 有人出现才录像，平时不录（不再拉 RecordService 做无脑录像）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("DxamRec", "BootReceiver: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            try {
                val i = Intent(context, DetectService::class.java)
                    .setAction(DetectService.ACTION_START)
                ContextCompat.startForegroundService(context, i)
                Log.i("DxamRec", "BootReceiver started DetectService(START)")
            } catch (t: Throwable) {
                Log.e("DxamRec", "BootReceiver failed", t)
            }
        }
    }
}
