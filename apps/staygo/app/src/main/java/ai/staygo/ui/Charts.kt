package ai.staygo.ui

import ai.staygo.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** Horizontal SOC bar. */
class SocBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.green) }
    private val rect = RectF()
    var socPercent: Int? = null
        set(v) { field = v; invalidate() }
    var charging: Boolean = false
        set(v) { field = v; fill.color = ContextCompat.getColor(context, if (v) R.color.green else R.color.muted); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        val soc = socPercent ?: return
        rect.set(0f, 0f, width * soc.coerceIn(0, 100) / 100f, height.toFloat())
        canvas.drawRoundRect(rect, r, r, fill)
    }
}

/** Charging power over the session with the session max and the late-phase line (k × Pmax). */
class PowerChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val font = ResourcesCompat.getFont(context, R.font.pretendard)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.green); strokeWidth = 6f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x222F9E62; style = Paint.Style.FILL }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted); strokeWidth = 3f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }
    private val late = Paint(guide).apply { color = ContextCompat.getColor(context, R.color.blue) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted); textSize = 30f; typeface = font
    }
    private val lateLabel = Paint(label).apply { color = ContextCompat.getColor(context, R.color.blue) }
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
        val top = 40f; val bottom = h - 8f
        if (points.size < 2) {
            canvas.drawText("충전전력 기록을 모으는 중", w / 2, h / 2, empty)
            return
        }
        val maxY = ((pmax ?: points.maxOf { it.second }) * 1.15).coerceAtLeast(10.0)
        val t0 = points.first().first; val t1 = points.last().first.coerceAtLeast(t0 + 1)
        fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * w
        fun y(v: Double) = (bottom - (v / maxY) * (bottom - top)).toFloat()
        pmax?.let {
            val yy = y(it); canvas.drawLine(0f, yy, w, yy, guide)
            canvas.drawText("세션 최대 %.0f kW".format(it), 0f, yy - 10f, label)
            val yl = y(it * lateRatio); canvas.drawLine(0f, yl, w, yl, late)
            canvas.drawText("후반 판정선 %.0f kW".format(it * lateRatio), 0f, yl - 10f, lateLabel)
        }
        path.reset(); fillPath.reset()
        points.forEachIndexed { i, (t, v) ->
            val px = x(t); val py = y(v)
            if (i == 0) { path.moveTo(px, py); fillPath.moveTo(px, bottom); fillPath.lineTo(px, py) } else { path.lineTo(px, py); fillPath.lineTo(px, py) }
        }
        fillPath.lineTo(x(points.last().first), bottom); fillPath.close()
        canvas.drawPath(fillPath, area)
        canvas.drawPath(path, line)
    }
}
