package com.example.sdrvideoscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

data class EdgeTimelineData(
    val sampleCount: Int,
    val strictEdges: List<Int>,
    val skippedEdges: List<Int>,
)

class EdgeTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101515.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x332C3A3A
        strokeWidth = resources.displayMetrics.density
    }
    private val skippedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88F05A52.toInt()
        strokeWidth = max(1f, resources.displayMetrics.density * 2f)
    }
    private val strictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF40E878.toInt()
        strokeWidth = max(1f, resources.displayMetrics.density * 2.5f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x554A5B5B
        strokeWidth = resources.displayMetrics.density
    }

    private var timeline: EdgeTimelineData? = null

    fun setTimeline(data: EdgeTimelineData?) {
        timeline = data
        visibility = if (data == null) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val centerY = height * 0.5f
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        for (tick in 0..4) {
            val x = ((width - 1).toFloat() * tick.toFloat()) / 4f
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }

        val data = timeline
        if (data == null || data.sampleCount <= 0) {
            canvas.drawLine(0f, centerY, width.toFloat(), centerY, emptyPaint)
            return
        }

        drawEdges(canvas, data.sampleCount, data.skippedEdges, skippedPaint)
        drawEdges(canvas, data.sampleCount, data.strictEdges, strictPaint)
    }

    private fun drawEdges(
        canvas: Canvas,
        sampleCount: Int,
        edges: List<Int>,
        paint: Paint,
    ) {
        if (sampleCount <= 0 || edges.isEmpty()) {
            return
        }
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val maxX = max(0, width - 1).toFloat()
        edges.forEach { edge ->
            if (edge < 0 || edge > sampleCount) {
                return@forEach
            }
            val x = ((edge.toFloat() / sampleCount.toFloat()) * maxX).roundToInt().toFloat()
            canvas.drawLine(x, top, x, bottom, paint)
        }
    }
}
