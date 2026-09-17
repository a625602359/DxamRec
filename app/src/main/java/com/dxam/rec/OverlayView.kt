package com.dxam.rec

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 在预览上画绿框 + 置信度。 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        textSize = 42f
        isFakeBoldText = true
    }

    private var box: RectF? = null
    private var viewW = 0
    private var viewH = 0
    private var conf = 0
    private var imgW = 1
    private var imgH = 1

    fun setBox(rect: RectF?, vw: Int, vh: Int, confidence: Int = 0) {
        box = rect
        viewW = vw; viewH = vh; conf = confidence
        // boundingBox 基于旋转后的图像坐标；用图像宽度做等比映射。
        if (rect != null && rect.right > 0) {
            imgW = rect.right.toInt().coerceAtLeast(1)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = box ?: return
        if (viewW == 0 || viewH == 0) return
        if (b.right <= b.left || b.bottom <= b.top) return
        // 简单等比缩放：按预览控件尺寸铺满
        val scale = viewW.toFloat() / b.right.coerceAtLeast(1f)
        val l = b.left * scale
        val t = b.top * scale
        val r = b.right * scale
        val bt = b.bottom * scale
        val cl = l.coerceIn(0f, viewW.toFloat())
        val ct = t.coerceIn(0f, viewH.toFloat())
        val cr = r.coerceIn(0f, viewW.toFloat())
        val cb = bt.coerceIn(0f, viewH.toFloat())
        canvas.drawRect(cl, ct, cr, cb, paint)
        val label = "人 $conf%"
        canvas.drawText(label, cl, (ct - 12).coerceAtLeast(40f), textPaint)
    }
}
