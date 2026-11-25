package rovio.nokia.bouncetouch

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameView(this)
        val hint = HintOverlay(this)
        // the tutorial card disappears after the first swipe up
        view.onSwipeUp = { runOnUiThread { hint.visibility = View.GONE } }
        val root = FrameLayout(this)
        root.addView(view)
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onPause() { super.onPause(); view.onPause() }
    override fun onResume() { super.onResume(); view.onResume() }
}
