package com.echocover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** The timing tower. Rows share the view's height, so the whole field always fits. */
class LeaderboardView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var rows: List<F1Driver> = emptyList()
    private var race = true

    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" } // digits line up
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val medium = resources.getFont(R.font.jakarta_medium)
    private val regular = resources.getFont(R.font.jakarta_regular)

    fun setRows(drivers: List<F1Driver>, isRace: Boolean) {
        rows = drivers
        race = isRace
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val rowH = (height - paddingTop - paddingBottom) / (max(rows.size, 20) + 1f)
        val size = (rowH * 0.5f).coerceAtMost(26 * density)

        val posRight = left + size * 1.6f
        val barLeft = posRight + size * 0.55f
        val tlaLeft = barLeft + size * 0.75f
        val gapRight = right
        val intervalRight = right - size * 5f

        // Column headings
        text.textSize = size * 0.62f
        text.typeface = medium
        text.color = Color.rgb(110, 110, 110)
        text.textAlign = Paint.Align.RIGHT
        val headY = top + rowH * 0.6f
        canvas.drawText(if (race) "INTERVAL" else "BEST", intervalRight, headY, text)
        canvas.drawText("GAP", gapRight, headY, text)

        for ((i, d) in rows.withIndex()) {
            val rowTop = top + rowH * (i + 1)
            val mid = rowTop + rowH / 2
            val baseline = mid + size * 0.36f
            val alpha = if (d.out) 90 else 255

            if (i % 2 == 0) {
                fill.color = Color.rgb(14, 14, 14)
                canvas.drawRect(left - 8 * density, rowTop, right + 8 * density, rowTop + rowH, fill)
            }

            text.textSize = size
            text.typeface = medium
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.rgb(150, 150, 150)
            text.alpha = alpha
            canvas.drawText(d.pos.toString(), posRight, baseline, text)

            fill.color = d.color
            fill.alpha = alpha
            rect.set(barLeft, mid - rowH * 0.3f, barLeft + size * 0.22f, mid + rowH * 0.3f)
            canvas.drawRoundRect(rect, 2 * density, 2 * density, fill)
            fill.alpha = 255

            text.textAlign = Paint.Align.LEFT
            text.color = Color.WHITE
            text.alpha = alpha
            canvas.drawText(d.tla, tlaLeft, baseline, text)

            text.typeface = regular
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.rgb(200, 200, 200)
            text.alpha = alpha
            val (first, second) = columns(d)
            if (d.inPit && !d.out) {
                drawPit(canvas, intervalRight, mid, size)
            } else {
                canvas.drawText(first, intervalRight, baseline, text)
            }
            canvas.drawText(second, gapRight, baseline, text)
        }
        text.alpha = 255
    }

    /** Text for the two timing columns. */
    private fun columns(d: F1Driver): Pair<String, String> {
        if (d.out) return "" to "OUT"
        if (!race) return d.gap to d.interval
        // F1 sends "LAP n" in the leader's gap and interval fields.
        if (d.gap.startsWith("LAP")) return "" to "Leader"
        return d.interval to d.gap
    }

    private fun drawPit(canvas: Canvas, rightEdge: Float, mid: Float, size: Float) {
        val label = "PIT"
        text.typeface = medium
        text.textSize = size * 0.7f
        val w = text.measureText(label) + size * 0.6f
        rect.set(rightEdge - w, mid - size * 0.55f, rightEdge, mid + size * 0.55f)
        fill.color = Color.WHITE
        canvas.drawRoundRect(rect, 4 * density, 4 * density, fill)
        text.color = Color.BLACK
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(label, rect.centerX(), mid + size * 0.25f, text)
        text.textSize = size
        text.typeface = regular
        text.textAlign = Paint.Align.RIGHT
        text.color = Color.rgb(200, 200, 200)
    }
}
