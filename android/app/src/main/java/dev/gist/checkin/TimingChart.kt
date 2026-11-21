package dev.gist.checkin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * How long a check-in took, day by day (oldest on the left): one bar per day, getting
 * started at the bottom (lighter) and doing it on top. Days with nothing timed are blank.
 */
class TimingChart(context: Context, private val days: List<String>, private val data: Map<String, Timing.Day>) : View(context) {
    private val density = resources.displayMetrics.density
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.primary_container) }
    private val stepsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.primary) }
    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.outline)
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = 11 * density
    }
    private val bar = RectF()

    init {
        contentDescription = "Chart: how long each day's check-in took"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (150 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val label = 16 * density
        val top = label
        val bottom = height - label
        val max = data.values.maxOfOrNull { (it.start ?: 0) + it.steps }?.coerceAtLeast(60) ?: return
        // Top line and its value; the baseline.
        canvas.drawLine(0f, top, width.toFloat(), top, gridPaint)
        canvas.drawText(Took.text(max), 0f, top - 4 * density, textPaint)
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, gridPaint)
        val slot = width.toFloat() / days.size
        val gap = (slot * 0.25f).coerceAtMost(4 * density)
        val r = 2 * density
        days.forEachIndexed { i, day ->
            val d = data[day] ?: return@forEachIndexed
            val x = i * slot + gap / 2
            val scale = (bottom - top) / max
            val startTop = bottom - (d.start ?: 0) * scale
            val stepsTop = startTop - d.steps * scale
            bar.set(x, startTop, x + slot - gap, bottom)
            canvas.drawRect(bar, startPaint)
            bar.set(x, stepsTop, x + slot - gap, startTop)
            canvas.drawRoundRect(bar, r, r, stepsPaint)
        }
        // First and last day under the bars.
        val first = Days.short(days.first())
        val last = Days.short(days.last())
        canvas.drawText(first, 0f, height - 2 * density, textPaint)
        canvas.drawText(last, width - textPaint.measureText(last), height - 2 * density, textPaint)
    }
}
