package com.dxam.rec

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * TS -> MP4 转换器（纯系统 API，无需 ffmpeg）。
 *
 * v60 可靠性加固：
 *   1) 先写 .tmp，成功才 rename 成正式 mp4（原子替换）
 *      -> 转换中途失败不会留下半截 mp4
 *   2) convertLeftovers：只要还有 .ts 就（重新）转换
 *      -> 半截/坏 mp4 不会再挡住真正的 TS 重转
 *   3) 启动清理 0 字节 mp4
 *   4) 转换失败保留 TS 并扫入媒体库（绝不丢数据）
 */
object TsToMp4 {
    private const val TAG = "DxamRec"

    /**
     * 把 ts 转成 mp4。成功返回 true。
     * 失败时不动源文件，也不破坏已存在的 mp4（写 tmp 原子替换）。
     */
    fun convert(tsFile: File, mp4File: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        var success = false
        val tmp = File(mp4File.parentFile, mp4File.name + ".tmp")
        try { if (tmp.exists()) tmp.delete() } catch (_: Throwable) {}
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(tsFile.absolutePath)
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    map[i] = muxer.addTrack(fmt)
                }
            }
            if (map.isEmpty()) {
                Log.e(TAG, "ts->mp4: no track")
            } else {
                muxer.start()
                started = true
                val buf = ByteBuffer.allocate(1 shl 20)
                val info = MediaCodec.BufferInfo()
                for ((et, mt) in map) {
                    extractor.selectTrack(et)
                    while (true) {
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) break
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = extractor.sampleTime
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(mt, buf, info)
                        extractor.advance()
                    }
                    extractor.unselectTrack(et)
                }
                muxer.stop()
                started = false
                if (tmp.length() > 0) {
                    if (mp4File.exists()) { try { mp4File.delete() } catch (_: Throwable) {} }
                    success = tmp.renameTo(mp4File)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ts->mp4 failed: " + tsFile.name, t)
        } finally {
            if (started) { try { muxer?.stop() } catch (_: Throwable) {} }
            try { extractor?.release() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            if (!success) { try { tmp.delete() } catch (_: Throwable) {} }
        }
        if (success) Log.i(TAG, "ts->mp4 ok: " + tsFile.name + " (" + mp4File.length() + ")")
        return success
    }

    /**
     * 扫描目录，处理残留文件：
     *   - 清 0 字节 mp4
     *   - 每个 .ts 都尝试转 mp4（成功删 ts；失败保留 ts 并扫入媒体库）
     * 返回成功转换数量。
     */
    fun convertLeftovers(dir: File): Int {
        if (!dir.exists()) return 0
        // 1) 清 0 字节 mp4（历史垃圾，会干扰判断）
        try {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") && f.length() == 0L }
                ?.forEach { try { it.delete() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        // 2) 有 .ts 就转换。正常成功后会删 ts，所以 ts 存在=没转完。
        val list = dir.listFiles { f -> f.isFile && f.name.endsWith(".ts") } ?: return 0
        var ok = 0
        for (ts in list) {
            if (ts.length() <= 0L) { try { ts.delete() } catch (_: Throwable) {}; continue }
            val base = ts.name.removeSuffix(".ts")
            val mp4 = File(dir, base + ".mp4")
            if (convert(ts, mp4) && mp4.length() > 0) {
                try { ts.delete() } catch (_: Throwable) {}
                scan(mp4, "video/mp4")
                ok++
            } else {
                // 转换失败：保留 TS（数据不丢），也扫入媒体库以便能播
                Log.w(TAG, "ts->mp4 keep ts: " + ts.name)
                scan(ts, "video/mp2t")
            }
        }
        return ok
    }

    /**
     * v64: scan all mp4 in dir to re-register files that were written to disk
     * but never got into MediaStore (async scanFile killed with the process).
     */
    fun scanAll(dir: File): Int {
        if (!dir.exists()) return 0
        var n = 0
        try {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") && f.length() > 0L }
                ?.forEach { scan(it, "video/mp4"); n++ }
        } catch (_: Throwable) {}
        if (n > 0) Log.i(TAG, "scanAll: rescan " + n + " mp4")
        return n
    }

    private fun scan(f: File, mime: String) {
        try {
            android.media.MediaScannerConnection.scanFile(
                com.dxam.rec.DetectService.appCtx, arrayOf(f.absolutePath), arrayOf(mime), null
            )
        } catch (_: Throwable) {}
    }
}
