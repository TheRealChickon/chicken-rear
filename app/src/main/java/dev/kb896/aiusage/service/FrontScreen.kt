package dev.kb896.aiusage.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display

/**
 * Whether the FRONT screen is on: the front display's own state, and not PowerManager.isInteractive.
 *
 * The two are not the same on this phone. The back is a power group of its own, and "interactive" is the
 * phone as a whole: with the back lit (which our own wake lock does) the phone stays interactive when the
 * front times out. A rule that took "in use" from isInteractive then held the back lit because the back
 * was lit, with no end (a night of that empties the battery). The front display's state has no such
 * loop: it is on while the front is on, and at no other time.
 */
object FrontScreen {
    /** A display state that is "on" for somebody looking at it; dozing (always-on display) is not. */
    fun isOn(state: Int): Boolean = state == Display.STATE_ON || state == Display.STATE_VR

    /** The front display's state now; where it cannot be read, what the phone says of itself. */
    fun on(context: Context): Boolean {
        val display = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null) return isOn(display.state)
        return (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
    }
}
