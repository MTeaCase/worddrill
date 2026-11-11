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
import com.worddrill.app.data.MasteryStats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 熟练度分布条：一条横置的堆叠条，四段分别是新词 / 学习中 / 较熟 / 已掌握。
 * 比一行文字更直观，也不占高度。
 */
class MasteryBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var stats: MasteryStats? = null

    private val paints = intArrayOf(
        R.color.brand_container,
        R.color.brand_soft,
        R.color.accent,
        R.color.brand,
    ).map { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, it) } }

    fun setStats(value: MasteryStats) {
        stats = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = stats ?: return
        val counts = intArrayOf(data.fresh, data.learning, data.familiar, data.mastered)
        val total = counts.sum()
        if (total <= 0) return

        val density = resources.displayMetrics.density
        val barHeight = height.toFloat().coerceAtMost(14f * density)
        val top = (height - barHeight) / 2f
        val radius = barHeight / 2f
        val rect = RectF(0f, top, width.toFloat(), top + barHeight)

        canvas.save()
        canvas.clipRect(rect)
        var left = 0f
        counts.forEachIndexed { index, count ->
            if (count <= 0) return@forEachIndexed
            val w = width.toFloat() * count / total
            canvas.drawRect(left, top, left + w, top + barHeight, paints[index])
            left += w
        }
        canvas.restore()
    }
}

/**
 * 到期量折线：横轴是「今天 / 明天 / N 天后」，纵轴是待复习词数。
 * 用来代替原来的第二根柱状图，曲线更能看出堆积趋势。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var values: List<Int> = emptyList()

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_soft)
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
        style = Paint.Style.FILL
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

    fun setForecast(counts: List<Int>) {
        values = counts
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val bottomPad = 18f * density
        val topPad = 16f * density
        val usable = height - bottomPad - topPad
        val slot = width.toFloat() / values.size
        val max = maxOf(values.max(), 1)

        val points = values.mapIndexed { index, value ->
            val x = slot * index + slot / 2f
            val y = topPad + usable * (1f - value.toFloat() / max)
            x to y
        }

        // 线下方淡色填充，让趋势更好读
        if (points.size > 1) {
            val area = Path()
            area.moveTo(points.first().first, topPad + usable)
            points.forEach { (x, y) -> area.lineTo(x, y) }
            area.lineTo(points.last().first, topPad + usable)
            area.close()
            canvas.drawPath(area, fillPaint)

            val line = Path()
            line.moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { (x, y) -> line.lineTo(x, y) }
            canvas.drawPath(line, linePaint)
        }

        points.forEachIndexed { index, (x, y) ->
            canvas.drawCircle(x, y, 3f * density, dotPaint)
            val value = values[index]
            if (value > 0) canvas.drawText(value.toString(), x, y - 7f * density, valuePaint)
            canvas.drawText(
                when (index) {
                    0 -> "今"
                    1 -> "明"
                    else -> "${index + 1}天"
                },
                x,
                height - 4f * density,
                labelPaint,
            )
        }
    }
}

/**
 * 一年打卡热力图：一列一周，7 行是周一到周日，颜色越深题量越大。
 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var stats: List<DailyStat> = emptyList()

    private val density = resources.displayMetrics.density
    private val buckets = intArrayOf(
        R.color.divider,
        R.color.brand_soft,
        R.color.brand_container,
        R.color.accent,
        R.color.brand,
    ).map { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, it) } }

    fun setStats(value: List<DailyStat>) {
        stats = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (stats.isEmpty()) return

        val gap = 2f * density
        val columns = ((stats.size + 6) / 7).coerceAtLeast(1)
        val cell = ((width.toFloat() - gap * (columns - 1)) / columns).coerceAtMost(14f * density)
        val radius = cell * 0.24f

        // 第一列从周一对齐：算出第一天是星期几（周一=0）
        val offset = weekdayOffset(stats.first().date)
        stats.forEachIndexed { index, stat ->
            val position = index + offset
            val column = position / 7
            val row = position % 7
            val left = column * (cell + gap)
            val top = row * (cell + gap)
            if (left + cell > width) return@forEachIndexed
            val rect = RectF(left, top, left + cell, top + cell)
            canvas.drawRoundRect(rect, radius, radius, buckets[bucketOf(stat.answered)])
        }
    }

    private fun bucketOf(answered: Int): Int = when {
        answered <= 0 -> 0
        answered < 20 -> 1
        answered < 50 -> 2
        answered < 100 -> 3
        else -> 4
    }

    private fun weekdayOffset(date: String): Int {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parsed = runCatching { fmt.parse(date) }.getOrNull() ?: return 0
        val cal = Calendar.getInstance().apply { time = parsed }
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }
}