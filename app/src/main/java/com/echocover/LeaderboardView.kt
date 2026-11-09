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

    private companion object {
        val TYRE_COLOURS = mapOf(
            "S" to Color.rgb(237, 28, 36),     // soft: red
            "M" to Color.rgb(255, 210, 0),     // medium: yellow
            "H" to Color.rgb(240, 240, 240),   // hard: white
            "I" to Color.rgb(67, 176, 42),     // intermediate: green
            "W" to Color.rgb(0, 114, 198)      // wet: blue
        )
    }

    private var rows: List<F1Driver> = emptyList()
    private var race = true

    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" } // digits line up
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        val tyreX = tlaLeft + size * 3.4f
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
        if (rows.any { it.tyre != null }) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("TYRE", tyreX, headY, text)
        }

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
            if (d.tyre != null && !d.out) drawTyre(canvas, d, tyreX, mid, size, alpha)

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

    /** Compound letter in a ring of its colour, like F1's graphics, then laps on the set. */
    private fun drawTyre(canvas: Canvas, d: F1Driver, cx: Float, mid: Float, size: Float, alpha: Int) {
        val colour = TYRE_COLOURS[d.tyre] ?: Color.GRAY
        val radius = size * 0.64f
        fill.color = Color.rgb(24, 24, 24)
        canvas.drawCircle(cx, mid, radius, fill)
        ring.color = colour
        ring.alpha = alpha
        ring.strokeWidth = size * 0.13f
        canvas.drawCircle(cx, mid, radius - ring.strokeWidth / 2, ring)

        text.typeface = medium
        text.textSize = size * 0.78f
        text.textAlign = Paint.Align.CENTER
        text.color = colour
        text.alpha = alpha
        canvas.drawText(d.tyre!!, cx, mid + size * 0.28f, text)

        d.tyreLaps?.let { laps ->
            text.typeface = regular
            text.textSize = size * 0.8f
            text.textAlign = Paint.Align.LEFT
            text.color = Color.rgb(140, 140, 140)
            text.alpha = alpha
            canvas.drawText(laps.toString(), cx + radius + size * 0.35f, mid + size * 0.29f, text)
        }
        text.textSize = size
        text.typeface = medium
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
