package com.dxam.rec

import android.content.Context

/**
 * 性能/录像参数，存 SharedPreferences。
 * v61：新增录像格式（单文件MP4 / 分段MP4）+ 分段时长。
 */
object PerfPrefs {
    const val PREF = "dxam_perf"
    private const val K_DW = "detect_w"
    private const val K_DH = "detect_h"
    private const val K_DINT = "detect_interval_ms"
    private const val K_RW = "rec_w"
    private const val K_RH = "rec_h"
    private const val K_RBR = "rec_bitrate"
    private const val K_FMT = "rec_format"
    private const val K_SEG = "seg_minutes"
    private const val K_CAM = "camera_id"
    private const val K_PVHI = "preview_hi"

    const val DEF_DW = 176
    const val DEF_DH = 144
    const val DEF_DINT = 5000
    const val DEF_RW = 1280
    const val DEF_RH = 720
    const val DEF_RBR = 6_000_000
    const val DEF_FMT = 0
    const val DEF_SEG = 3
    const val DEF_CAM = 0
    const val DEF_PVHI = 1

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun detectW(ctx: Context) = sp(ctx).getInt(K_DW, DEF_DW)
    fun detectH(ctx: Context) = sp(ctx).getInt(K_DH, DEF_DH)
    fun detectIntervalMs(ctx: Context) = sp(ctx).getInt(K_DINT, DEF_DINT).toLong()
    fun recW(ctx: Context) = sp(ctx).getInt(K_RW, DEF_RW)
    fun recH(ctx: Context) = sp(ctx).getInt(K_RH, DEF_RH)
    fun recBitrate(ctx: Context) = sp(ctx).getInt(K_RBR, DEF_RBR)
    fun recFormat(ctx: Context) = sp(ctx).getInt(K_FMT, DEF_FMT)
    fun segMinutes(ctx: Context) = sp(ctx).getInt(K_SEG, DEF_SEG)
    fun camId(ctx: Context) = sp(ctx).getInt(K_CAM, DEF_CAM)
    fun previewHi(ctx: Context) = sp(ctx).getInt(K_PVHI, DEF_PVHI)

    fun setPreviewHi(ctx: Context, v: Int) {
        sp(ctx).edit().putInt(K_PVHI, v).apply()
    }

    fun save(ctx: Context, dw: Int, dh: Int, dint: Int, rw: Int, rh: Int, rbr: Int, fmt: Int, seg: Int, cam: Int) {
        sp(ctx).edit()
            .putInt(K_DW, dw).putInt(K_DH, dh).putInt(K_DINT, dint)
            .putInt(K_RW, rw).putInt(K_RH, rh).putInt(K_RBR, rbr)
            .putInt(K_FMT, fmt).putInt(K_SEG, seg).putInt(K_CAM, cam)
            .apply()
    }
}
