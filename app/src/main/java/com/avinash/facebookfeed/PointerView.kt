package com.avinash.facebookfeed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Pointer mode's on-screen pointer: a large white arrow with a thick black outline, readable on any background.
 * Its tip is at the view's top-left corner plus [TIP_OFFSET], so placing the view at (x - TIP_OFFSET, y - TIP_OFFSET)
 * puts the tip at (x, y). It never takes focus; the keys stay with the WebView.
 */
class PointerView(context: Context) : View(context) {

    // The classic arrow cursor, in units of SCALE px, tip at (0, 0).
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 17f)
        lineTo(4f, 13.5f)
        lineTo(7f, 20f)
        lineTo(10f, 18.8f)
        lineTo(7.2f, 12.5f)
        lineTo(12f, 12.5f)
        close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((13 * SCALE + 2 * TIP_OFFSET).toInt(), (21 * SCALE + 2 * TIP_OFFSET).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.translate(TIP_OFFSET, TIP_OFFSET)
        canvas.scale(SCALE, SCALE)
        canvas.drawPath(arrow, outline)
        canvas.drawPath(arrow, fill)
    }

    companion object {
        private const val SCALE = 2.6f
        /** Room for the outline around the tip. */
        const val TIP_OFFSET = 7f
    }
}
