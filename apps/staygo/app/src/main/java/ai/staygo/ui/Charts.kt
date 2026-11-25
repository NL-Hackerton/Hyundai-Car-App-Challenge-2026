package ai.staygo.ui

import ai.staygo.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils

private fun View.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
private fun View.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/** Rounded progress bar (SOC, arrival SOC) with an optional marker, e.g. the reserve SOC. */
class SocBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.sg_track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.sg_stay) }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.sg_text) }
    private val rect = RectF()
    var socPercent: Int? = null
        set(v) { field = v; invalidate() }
    var markerPercent: Int? = null
        set(v) { field = v; invalidate() }
    var fillColor: Int
        get() = fill.color
        set(v) { fill.color = v; invalidate() }
    /** Kept for callers of the earlier API: charging -> green, otherwise neutral. */
    var charging: Boolean = false
        set(v) { field = v; fill.color = ContextCompat.getColor(context, if (v) R.color.sg_stay else R.color.sg_text_3); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        socPercent?.let {
            rect.set(0f, 0f, (width * it.coerceIn(0, 100) / 100f).coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
        }
        markerPercent?.let {
            val x = width * it.coerceIn(0, 100) / 100f
            val w = dp(3f)
            canvas.drawRect(x - w, -dp(6f), x + w, height + dp(6f), marker)
        }
    }
}

/** Charging power sparkline for the session, with the late-phase line (k x Pmax) it is judged against. */
class PowerChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val font = ResourcesCompat.getFont(context, R.font.asta_sans)
    private val accent = ContextCompat.getColor(context, R.color.sg_go_text)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = dp(4f); style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(accent, 0x2E); style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val late = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sg_text_3); strokeWidth = dp(2f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sg_text_3); textSize = sp(24f); typeface = font; textAlign = Paint.Align.RIGHT
    }
    private val empty = Paint(label).apply { textAlign = Paint.Align.CENTER }

    private val points = ArrayList<Pair<Long, Double>>()
    private var pmax: Double? = null
    private var lateRatio = 0.5
    private val path = Path()
    private val fillPath = Path()

    fun update(samples: List<Pair<Long, Double>>, sessionMax: Double?, ratio: Double) {
        points.clear(); points.addAll(samples); pmax = sessionMax; lateRatio = ratio; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = dp(8f)
        val top = pad; val bottom = h - pad
        if (points.size < 2) {
            canvas.drawText("충전전력 기록을 모으는 중", w / 2, h / 2 + label.textSize / 3, empty)
            return
        }
        val maxY = ((pmax ?: points.maxOf { it.second }) * 1.1).coerceAtLeast(10.0)
        val t0 = points.first().first; val t1 = points.last().first.coerceAtLeast(t0 + 1)
        val right = w - pad
        fun x(t: Long) = pad + (t - t0).toFloat() / (t1 - t0) * (right - pad)
        fun y(v: Double) = (bottom - (v / maxY) * (bottom - top)).toFloat()
        pmax?.let {
            val yl = y(it * lateRatio)
            canvas.drawLine(0f, yl, w, yl, late)
            val full = "판정선 %.0f kW".format(it * lateRatio)
            val text = if (label.measureText(full) <= w * 0.9f) full else "%.0f kW".format(it * lateRatio)
            canvas.drawText(text, w, yl - dp(8f), label)
        }
        path.reset(); fillPath.reset()
        points.forEachIndexed { i, (t, v) ->
            val px = x(t); val py = y(v)
            if (i == 0) { path.moveTo(px, py); fillPath.moveTo(px, bottom); fillPath.lineTo(px, py) } else { path.lineTo(px, py); fillPath.lineTo(px, py) }
        }
        fillPath.lineTo(x(points.last().first), bottom); fillPath.close()
        canvas.drawPath(fillPath, area)
        canvas.drawPath(path, line)
        canvas.drawCircle(x(points.last().first), y(points.last().second), dp(7f), dot)
    }
}
