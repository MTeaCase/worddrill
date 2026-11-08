package com.worddrill.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.worddrill.app.R
import com.worddrill.app.data.DailyStat

/**
 * 极简柱状图：最近 N 天的练习量。只用 Canvas 手绘，不引第三方库。
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private var goal: Int = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
    }
    private val barSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_container)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_hint)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density),
            0f,
        )
    }

    fun setStats(stats: List<DailyStat>, dailyGoal: Int) {
        values = stats.map { it.answered }
        labels = stats.map { weekdayLabel(it.date) }
        goal = dailyGoal
        invalidate()
    }

    /** 复习量预测：不画目标线，达标高亮也没有意义。 */
    fun setForecast(counts: List<Int>) {
        values = counts
        labels = counts.indices.map { index ->
            when (index) {
                0 -> "今"
                1 -> "明"
                else -> "${index + 1}天"
            }
        }
        goal = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val paddingBottom = 18f * resources.displayMetrics.density
        val paddingTop = 16f * resources.displayMetrics.density
        val usableHeight = height - paddingBottom - paddingTop
        val slot = width.toFloat() / values.size
        val barWidth = slot * 0.52f

        val maxValue = maxOf(values.max(), goal, 1)
        val goalY = paddingTop + usableHeight * (1f - goal.toFloat() / maxValue)

        values.forEachIndexed { index, value ->
            val cx = slot * index + slot / 2f
            val ratio = value.toFloat() / maxValue
            val barHeight = usableHeight * ratio
            val top = paddingTop + usableHeight - barHeight
            val rect = RectF(cx - barWidth / 2f, top, cx + barWidth / 2f, paddingTop + usableHeight)
            val paint = if (goal > 0 && value >= goal) barPaint else barSoftPaint
            val radius = barWidth / 2f
            canvas.drawRoundRect(rect, radius, radius, paint)

            if (value > 0) {
                canvas.drawText(value.toString(), cx, top - 4f * resources.displayMetrics.density, valuePaint)
            }
            labels.getOrNull(index)?.let { label ->
                canvas.drawText(label, cx, height - 4f * resources.displayMetrics.density, labelPaint)
            }
        }

        if (goal > 0) {
            val path = Path()
            path.moveTo(0f, goalY)
            path.lineTo(width.toFloat(), goalY)
            canvas.drawPath(path, goalPaint)
        }
    }

    private fun weekdayLabel(date: String): String {
        val day = date.substringAfterLast('-').toIntOrNull() ?: return date
        return day.toString()
    }
}
