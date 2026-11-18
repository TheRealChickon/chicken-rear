package dev.kb896.aiusage.circle

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * An empty, see-through, untouchable activity that sits under the overlay while it is open over the home screen.
 * HyperOS switches its back gesture off whenever the launcher is the top activity; with this activity on top it
 * stays on, so the phone's own back swipe (and its arrow) also works when the overlay was opened from home.
 * The gesture itself still goes to the overlay window, which has the focus.
 */
class AnchorActivity : Activity() {
    companion object { @Volatile var current: AnchorActivity? = null }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        current = this
        if (!dev.kb896.aiusage.service.PinGlassService.circleWantsAnchor()) leave()
    }

    fun leave() { if (!isFinishing) { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) } }

    /** Home, recents or another app came up: nothing to anchor any more. */
    override fun onStop() { super.onStop(); leave() }

    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
}
