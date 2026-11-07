package com.echocover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * The circuit outline with a dot per car. New positions arrive about once a second; each dot
 * glides from where it is to its new spot over that time, so the cars move smoothly.
 */
class TrackMapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Dot(val tla: String, var color: Int, var x: Float, var y: Float) {
        var fromX = x
        var fromY = y
        var toX = x
        var toY = y
    }

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(56, 56, 56)
        strokeWidth = 9 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val finishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3 * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2 * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * resources.displayMetrics.scaledDensity
        typeface = resources.getFont(R.font.jakarta_medium)
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }

    private var track: F1Track? = null
    private val path = Path()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var dots = LinkedHashMap<String, Dot>()   // leaderboard order, leader first
    private var animStart = 0L
    private var animMs = 1000L

    // Label boxes drawn this frame, reused between frames.
    private val labels = ArrayList<RectF>()
    private var labelCount = 0

    private fun labelBox(i: Int): RectF {
        while (labels.size <= i) labels.add(RectF())
        return labels[i]
    }

    val trackId: String?
        get() = track?.id

    fun setTrack(t: F1Track) {
        track = t
        layoutTrack()
        invalidate()
    }

    /** Moves each car to its new position over [durationMs]; cars that are off the map vanish. */
    fun setDrivers(drivers: List<F1Driver>, durationMs: Long) {
        val now = SystemClock.uptimeMillis()
        advance(now)
        val next = LinkedHashMap<String, Dot>()
        for (d in drivers) {
            val x = d.x ?: continue
            val y = d.y ?: continue
            val dot = dots[d.num] ?: Dot(d.tla, d.color, x, y)
            dot.color = d.color
            dot.fromX = dot.x
            dot.fromY = dot.y
            dot.toX = x
            dot.toY = y
            // Big jumps (leaving the pits, a resync) snap instead of sliding across the infield.
            if (hypot(x - dot.x, y - dot.y) > 0.2f) {
                dot.fromX = x
                dot.fromY = y
            }
            next[d.num] = dot
        }
        dots = next
        animStart = now
        animMs = durationMs
        postInvalidateOnAnimation()
    }

    fun clear() {
        dots = LinkedHashMap()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutTrack()
    }

    private fun layoutTrack() {
        val t = track ?: return
        val pad = 28 * density
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        if (availW <= 0 || availH <= 0) return
        scale = min(availW / t.w, availH / t.h)
        offsetX = pad + (availW - t.w * scale) / 2
        offsetY = pad + (availH - t.h * scale) / 2
        path.reset()
        val p = t.points
        path.moveTo(sx(p[0]), sy(p[1]))
        for (i in 2 until p.size step 2) path.lineTo(sx(p[i]), sy(p[i + 1]))
        path.close()
    }

    private fun sx(x: Float) = offsetX + x * scale
    private fun sy(y: Float) = offsetY + y * scale

    private fun advance(now: Long) {
        val f = ((now - animStart).toFloat() / animMs).coerceIn(0f, 1f)
        for (d in dots.values) {
            d.x = d.fromX + (d.toX - d.fromX) * f
            d.y = d.fromY + (d.toY - d.fromY) * f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = track ?: return
        canvas.drawPath(path, trackPaint)
        drawFinishLine(canvas, t.points)

        val now = SystemClock.uptimeMillis()
        advance(now)
        val radius = 8 * density
        // Dots back to front so the leader sits on top.
        for (d in dots.values.reversed()) {
            dotPaint.color = d.color
            canvas.drawCircle(sx(d.x), sy(d.y), radius, dotPaint)
            canvas.drawCircle(sx(d.x), sy(d.y), radius, ringPaint)
        }
        // Labels front to back, skipping any that would overlap a car ahead's label.
        labelCount = 0
        for (d in dots.values) {
            val w = labelPaint.measureText(d.tla)
            var x = sx(d.x) + radius + 3 * density
            if (x + w > width) x = sx(d.x) - radius - 3 * density - w   // flip near the right edge
            val y = sy(d.y) - radius * 0.4f
            val box = labelBox(labelCount)
            box.set(x, y + labelPaint.ascent(), x + w, y + labelPaint.descent())
            if ((0 until labelCount).any { RectF.intersects(labels[it], box) }) continue
            labelCount++
            canvas.drawText(d.tla, x, y, labelPaint)
        }
        if (now - animStart < animMs) postInvalidateOnAnimation()
    }

    /** A short white line across the track at the first outline point. */
    private fun drawFinishLine(canvas: Canvas, p: FloatArray) {
        if (p.size < 8) return
        val dx = sx(p[6]) - sx(p[0])
        val dy = sy(p[7]) - sy(p[1])
        val len = hypot(dx, dy).takeIf { it > 0 } ?: return
        val half = 9 * density
        val nx = -dy / len * half
        val ny = dx / len * half
        val x = sx(p[0])
        val y = sy(p[1])
        canvas.drawLine(x - nx, y - ny, x + nx, y + ny, finishPaint)
    }
}
