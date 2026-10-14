package app.mg4dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.mg4dash.R
import java.util.Locale

/** Barre horizontale empilée : répartition de l'énergie (conduite, clim, accessoires). */
class StackedBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    data class Part(val value: Float, val color: Int)

    var parts: List<Part> = emptyList()
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val r = resources.displayMetrics.density * 6f
    private val gap = resources.displayMetrics.density * 3f
    private val off = ContextCompat.getColor(context, R.color.gauge_off)

    override fun onDraw(canvas: Canvas) {
        val total = parts.sumOf { it.value.toDouble() }.toFloat()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (total <= 0f) { paint.color = off; canvas.drawRoundRect(rect, r, r, paint); return }
        var x = 0f
        val visible = parts.filter { it.value > 0f }
        visible.forEachIndexed { i, p ->
            val w = (width - gap * (visible.size - 1)) * p.value / total
            rect.set(x, 0f, x + w, height.toFloat())
            paint.color = p.color
            canvas.drawRoundRect(rect, r, r, paint)
            x += w + gap
        }
    }
}

/** Courbe de consommation (kWh/100 km) avec aire dégradée et ligne de moyenne. */
class LineChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var values: List<Float> = emptyList()
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent_energy)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = ContextCompat.getColor(context, R.color.panel_border); strokeWidth = 1f }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        color = ContextCompat.getColor(context, R.color.accent_heat)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 6 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_title); textSize = 14f * d
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_sub); textSize = 17f * d; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val left = 34 * d; val top = 6 * d; val bottom = height - 6 * d; val right = width.toFloat()
        if (values.size < 2) {
            canvas.drawText(context.getString(app.mg4dash.R.string.chart_empty), width / 2f, height / 2f, empty)
            return
        }
        val lo = (values.minOrNull()!! - 2f).coerceAtLeast(0f)
        val hi = values.maxOrNull()!! + 2f
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        listOf(lo, (lo + hi) / 2, hi).forEach { v ->
            canvas.drawLine(left, y(v), right, y(v), grid)
            canvas.drawText(String.format(Locale.getDefault(), "%.0f", v), 0f, y(v) + 4 * d, label)
        }
        val step = (right - left) / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v -> val x = left + i * step; if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v)) }
        val area = Path(path).apply { lineTo(right, bottom); lineTo(left, bottom); close() }
        fill.shader = LinearGradient(0f, top, 0f, bottom, 0x5500E676, 0x0000E676, Shader.TileMode.CLAMP)
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        val avg = values.average().toFloat()
        canvas.drawLine(left, y(avg), right, y(avg), avgPaint)
    }
}
