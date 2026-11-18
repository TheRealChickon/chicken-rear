package dev.kb896.aiusage.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * THE PIN'S GLASS SEES THE PAGE BEHIND IT. A see-through activity gets nothing of what is
 * under it (the compositor's blur is the compositor's), and glass that bends and lights the page needs its pixels.
 * An accessibility service may take a screenshot without asking each time (`takeScreenshot`, declared
 * `canTakeScreenshot`), so this is one that does ONLY that: it asks for no events and no window content
 * (`res/xml/pin_glass.xml`), and [shot] is its whole work.
 *
 * ONE PICTURE PER PIN, taken as the pin begins (before anything of the pin's own is on the screen), handed to the
 * pin's screen, held there in memory for the two seconds of the animation and let go ([PinActivity]). NEVER saved,
 * sent or logged; only that it came, how large and how long it took is said.
 *
 * HE SWITCHES IT ON HIMSELF (Settings, Accessibility; the app's Notes section has the row that leads there): an
 * accessibility service is not ours to enable. Off, or refused, or slower than the animation: the pin plays over
 * the system's blur as before, and never waits.
 */
class PinGlassService : AccessibilityService() {
    override fun onServiceConnected() { live = this; Log.i(TAG, "glass: the service is on") }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (live === this) live = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (live === this) live = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "AiUsagePin"
        @Volatile private var live: PinGlassService? = null
        @Volatile private var lastAt = 0L

        /** Android gives a service one screenshot a second; a second pin inside that plays without. */
        const val SPACING_MS = 1_000L

        /** Whether a picture may be asked for now: the service on, and the last one long enough ago. Pure. */
        fun mayShoot(on: Boolean, now: Long, last: Long): Boolean = on && (last == 0L || now - last >= SPACING_MS)

        /** Running now: the system has bound it. */
        fun running(): Boolean = live != null

        /** Switched on in the phone's settings (bound or not yet). */
        fun enabled(context: Context): Boolean {
            if (live != null) return true
            val want = ComponentName(context, PinGlassService::class.java)
            val set = runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull().orEmpty()
            return set.split(':').any { ComponentName.unflattenFromString(it) == want }
        }

        /**
         * THE PIN'S ANIMATION IN AN OVERLAY OF THIS SERVICE'S OWN, above everything, another app's overlay included
         * (Circle Search is one: an activity's window would lie under it), for the two seconds it plays: it takes
         * no touch and no focus, so whatever he was in stays as it was, and is taken away when it has played. The
         * picture of the page behind is taken first, before the overlay is there. False: the service is off, or
         * the overlay could not be added; the caller plays it in its own window. [picture] (its file [image]): a
         * picture's pin, the picture where the words would be.
         */
        fun beam(text: String, origin: android.graphics.RectF?, run: dev.kb896.aiusage.ui.PinRun, light: dev.kb896.aiusage.rear.XiaomiPin.Light? = null, slow: Float = 1f,
                 picture: Bitmap? = null, image: String? = null): Boolean {
            val s = live ?: return false
            return runCatching {
                val wm = s.getSystemService(android.view.WindowManager::class.java)
                val bounds = wm.maximumWindowMetrics.bounds
                var view: dev.kb896.aiusage.ui.PinBeamView? = null
                var bView: dev.kb896.aiusage.ui.PinBView? = null
                var host: android.view.SurfaceControlViewHost? = null
                var gone = false
                val end = {
                    if (!gone) {
                        gone = true
                        run.finish()
                        runCatching { host?.surfacePackage?.surfaceControl?.let { sc -> android.view.SurfaceControl.Transaction().reparent(sc, null).apply() } }
                        runCatching { host?.release() }
                        view?.behind?.recycle(); view?.behind = null
                        bView?.behind?.recycle(); bView?.behind = null
                        Log.i(TAG, "beam: the overlay is gone")
                    }
                }
                // ATTACHED TO THE DISPLAY ABOVE ALL ITS WINDOWS (attachAccessibilityOverlayToDisplay): an ordinary
                // accessibility-overlay window of ours lay UNDER Circle Search's on the emulator (which of two
                // services' windows is on top goes by which service was bound first). No window of its own, so no
                // window blur: what is behind is the picture of the page, blurred here, once it has come.
                // "Ours" is draft B where Xiaomi's light pictures were read (rear/PinB), the plain drawing otherwise. Should
                // OpenGL fail, the pin simply ends. A TOUCH ENDS EITHER AT ONCE: the embedded window is touchable over the whole
                // screen (setView's own flags), so a view that let touches "go through" ate every one for 4.4 s.
                val v: android.view.View = if (light != null) dev.kb896.aiusage.ui.PinBView(
                    s, text, origin, run, light, blur = {}, touchEnds = true, onFailed = { end() }, end = end, slow = slow, picture = picture, image = image,
                ).also { bView = it } else dev.kb896.aiusage.ui.PinBeamView(s, text, origin, run, blur = {}, touchEnds = true, picture = picture, image = image, end = end).also { view = it }
                val h = android.view.SurfaceControlViewHost(s, s.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY), android.os.Binder())
                host = h
                h.setView(v, bounds.width(), bounds.height())
                val sc = h.surfacePackage!!.surfaceControl
                s.attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, sc)
                // Attached is not yet shown (nothing was on the screen on the emulator until this): shown, at the screen's corner.
                android.view.SurfaceControl.Transaction().setVisibility(sc, true).setPosition(sc, 0f, 0f).setLayer(sc, 1).apply()
                // The page behind (Circle Search as he sees it): asked for only now that the overlay is sure, in the
                // same instant, before its first frame is on the screen. The animation does not wait for it.
                shot { picture -> if (gone) picture?.recycle() else { view?.behind = picture; bView?.behind = picture } }
                Log.i(TAG, "beam: in an overlay above every window")
                // Should it never draw (the screen went off): gone all the same.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ end() }, ((if (light != null) dev.kb896.aiusage.rear.PinB.FRONT_MS * slow else dev.kb896.aiusage.rear.PinBeam.FRONT_MS.toFloat()) + 1_500f).toLong())
                true
            }.onFailure { Log.w(TAG, "beam: no overlay: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}; ${it.cause?.message.orEmpty().take(120)}") }.getOrDefault(false)
        }

        /**
         * The running service as a context to add a window of TYPE_ACCESSIBILITY_OVERLAY with (the earbuds' card,
         * ui/EarbudsOverlay: a trusted window that takes no touch). Null: the service is off.
         */
        fun windowContext(): Context? = live

        /**
         * One picture of the front as it is now, to [done] on the main thread (null: off, too soon, or refused).
         * A hardware bitmap, in memory only (a drawing path may copy its pixels into a texture and lets the copy go at
         * once): never written anywhere.
         */
        fun shot(done: (Bitmap?) -> Unit) {
            val s = live
            val now = SystemClock.uptimeMillis()
            if (!mayShoot(s != null, now, lastAt) || s == null) {
                Log.i(TAG, if (s == null) "glass: the service is off — the pin plays over the system's blur" else "glass: asked again within a second — this pin plays without")
                done(null)
                return
            }
            lastAt = now
            runCatching {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val b = runCatching { Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace) }.getOrNull()
                        runCatching { r.hardwareBuffer.close() }
                        Log.i(TAG, "glass: the page behind arrived ${SystemClock.uptimeMillis() - now} ms after it was asked for (${b?.width} x ${b?.height})")
                        done(b)
                    }
                    override fun onFailure(code: Int) { Log.w(TAG, "glass: no picture (code $code)"); done(null) }
                })
            }.onFailure { Log.w(TAG, "glass: ${it.javaClass.simpleName}"); done(null) }
        }
    }
}
