package com.mehmet.barkodokuyucu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

class ScanOverlayView(context: Context) : View(context) {
    private val shadePaint = Paint().apply { color = 0x66000000 }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val boxWidth = width * 0.82f
        val boxHeight = min(height * 0.34f, boxWidth * 0.52f)
        val left = (width - boxWidth) / 2f
        val top = (height - boxHeight) / 2f
        val rect = RectF(left, top, left + boxWidth, top + boxHeight)

        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, shadePaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shadePaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, shadePaint)

        canvas.drawRoundRect(rect, 18f, 18f, borderPaint)
    }
}
