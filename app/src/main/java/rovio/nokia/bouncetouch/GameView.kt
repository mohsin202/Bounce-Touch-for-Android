package rovio.nokia.bouncetouch

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** Touch controls: swipe up/down = faster/slower, double tap = jump, hold left/right half = turn camera. */
class GameView(ctx: Context) : GLSurfaceView(ctx) {
    private val renderer = GameRenderer(ctx)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var downX = 0f; private var downY = 0f
    var onSwipeUp: (() -> Unit)? = null

    private val doubleTap = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { renderer.jump(); return true }
    })

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        doubleTap.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                renderer.holdStart(e.x / width.toFloat())
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 3) renderer.nextLevel()
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop * 2 || abs(e.y - downY) > slop * 2) renderer.holdCancel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                renderer.holdCancel()
                val dx = e.x - downX; val dy = e.y - downY
                if (e.actionMasked == MotionEvent.ACTION_UP && abs(dy) > slop * 3 && abs(dy) > abs(dx)) {
                    // up (negative dy) = faster, down = slower; longer swipe = bigger change
                    val strength = (abs(dy) / height.toFloat()).coerceIn(0.1f, 0.6f)
                    renderer.swipe(if (dy < 0) strength else -strength)
                    if (dy < 0) onSwipeUp?.invoke()
                }
            }
        }
        return true
    }
}
