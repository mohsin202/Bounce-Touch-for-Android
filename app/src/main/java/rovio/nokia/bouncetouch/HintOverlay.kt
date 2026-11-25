package rovio.nokia.bouncetouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** First-run tutorial card (top-left): red ball + dashed arrow = swipe up to roll forward. */
class HintOverlay(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; color = 0xFF000000.toInt() }
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE01510.toInt() }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFFE8573A.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8573A.toInt() }
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val fingerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = 0xFF222222.toInt() }
    private var phase = 0f

    override fun onDraw(c: Canvas) {
        val w = 190 * d; val h = 120 * d; val x = 12 * d; val y = 12 * d
        val r = RectF(x, y, x + w, y + h)
        c.drawRoundRect(r, 14 * d, 14 * d, fill); c.drawRoundRect(r, 14 * d, 14 * d, stroke)
        c.drawCircle(x + 45 * d, y + h - 30 * d, 12 * d, ball)
        // animated hand + arrow (moves up and repeats)
        phase = (phase + 0.02f) % 1f
        val ax = x + 120 * d; val top = y + 18 * d; val bottom = y + h - 18 * d
        val p = Path().apply { moveTo(ax, bottom); lineTo(ax, top + 8 * d) }
        c.drawPath(p, arrow)
        val tri = Path().apply { moveTo(ax, top); lineTo(ax - 7 * d, top + 14 * d); lineTo(ax + 7 * d, top + 14 * d); close() }
        c.drawPath(tri, head)
        val hy = bottom - (bottom - top - 14 * d) * phase
        val hand = RectF(ax - 9 * d, hy, ax + 9 * d, hy + 30 * d)
        c.drawRoundRect(hand, 9 * d, 9 * d, finger); c.drawRoundRect(hand, 9 * d, 9 * d, fingerLine)
        postInvalidateDelayed(16)
    }
}
