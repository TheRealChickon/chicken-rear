package dev.kb896.aiusage.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.PinImageFiles
import dev.kb896.aiusage.rear.PinFace

class PinViewActivity : Activity() {
    private var viewer: PinViewer? = null
    /** Asked to unlock, and waiting for him. */
    private var unlocking = false
    /** Which ask to unlock is the latest: an earlier one's answer is not this one's. */
    private var unlockAsk = 0
    /** Which read is the latest: a picture read for an earlier ask is let go. */
    private var asked = 0
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        if (!unlocking) return@Runnable
        Log.i(TAG, "view: not unlocked within ${UNLOCK_WAIT_MS / 1000} s — nothing shown")
        leave()
    }

    private val backGesture = object : OnBackAnimationCallback {
        override fun onBackStarted(e: BackEvent) = Unit
        override fun onBackProgressed(e: BackEvent) { viewer?.backProgress(e.progress) }
        override fun onBackCancelled() { viewer?.backCancelled() }
        override fun onBackInvoked() { viewer?.closeBack() ?: leave() }
    }

    /** The picture on the screen, for a test. */
    internal val shownViewer: PinViewer? get() = viewer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        // Never over the lock screen; the front woken where it was off.
        setShowWhenLocked(false)
        setTurnScreenOn(true)
        window.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setDecorFitsSystemWindows(false)
            attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            decorView
            insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                // Shown by a swipe at the edge: light on the black.
                it.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            }
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backGesture)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun keyguard(): KeyguardManager? = getSystemService(KeyguardManager::class.java)
    private fun locked(): Boolean = keyguard()?.isKeyguardLocked == true
    private fun callOn(): Boolean = PinViewGate.inCall(runCatching { getSystemService(AudioManager::class.java)?.mode ?: 0 }.getOrDefault(0))
    private fun pinOf(at: Long): Pin? = PinViewGate.pin(ConfigStore(applicationContext).load().pins, at)

    /** One ask: the note's time, and nothing else, from [intent]. */
    private fun handle(intent: Intent?) {
        val at = PinViewGate.at(runCatching { intent?.getLongExtra(PinViewGate.EXTRA_AT, 0L) ?: 0L }.getOrDefault(0L))
        val pin = pinOf(at)
        val v = PinViewGate.verdict(at, pin, callOn(), locked())
        Log.i(TAG, "view: ${v.why}")
        when (v) {
            PinViewGate.Verdict.SHOW -> show(pin!!)
            PinViewGate.Verdict.UNLOCK_FIRST -> unlockThen(pin!!.at)
            // A later ask that is not to be shown leaves the picture showing as it is.
            else -> if (viewer == null) leave()
        }
    }

    /** Locked: he is asked to unlock, and the picture is read and shown only once he has. */
    private fun unlockThen(at: Long) {
        val kg = keyguard() ?: return leave()
        unlocking = true
        val ask = ++unlockAsk
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, UNLOCK_WAIT_MS)
        kg.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                if (!unlocking || ask != unlockAsk) return
                unlocking = false
                handler.removeCallbacks(giveUp)
                val pin = pinOf(at)
                if (PinViewGate.afterUnlock(pin, callOn(), locked())) show(pin!!)
                else { Log.i(TAG, "view: unlocked, but the picture is gone, a call is on, or the lock is back — nothing shown"); leave() }
            }

            override fun onDismissCancelled() = notUnlocked(ask, "he did not unlock")
            override fun onDismissError() = notUnlocked(ask, "the lock screen could not be asked to go")
        })
    }

    private fun notUnlocked(ask: Int, why: String) {
        if (!unlocking || ask != unlockAsk) return
        Log.i(TAG, "view: $why — nothing shown")
        leave()
    }

    /** [pin]'s picture read (off the main thread) and opened. Never while locked. */
    private fun show(pin: Pin) {
        val ask = ++asked
        val app = applicationContext
        val name = pin.image
        work {
            val picture = PinImageFiles.readForView(app, name)
            runOnUiThread { opened(ask, pin, picture) }
        }
    }

    private fun opened(ask: Int, pin: Pin, picture: Bitmap?) {
        if (ask != asked || isFinishing || isDestroyed) return
        if (picture == null) {
            Log.w(TAG, "view: the picture's file could not be read — nothing shown")
            if (viewer == null) leave()
            return
        }
        // The lock came back while it was read: still never over it.
        if (locked()) { Log.i(TAG, "view: locked again meanwhile — nothing shown"); leave(); return }
        val v = PinViewer(this, picture, pin.text, PinFace.pictureAt(pin.text, pin.image)) { leave() }
        viewer = v
        setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.begin()
    }

    /** Gone, with its task, and no animation of the window's own (the picture has made its own). */
    private fun leave() {
        unlocking = false
        handler.removeCallbacks(giveUp)
        if (!isFinishing) finishAndRemoveTask()
    }

    /** Left any other way than its own close (Home, Recents, the screen off): it goes, not to be found again later. */
    override fun onStop() {
        super.onStop()
        if (viewer != null && !isChangingConfigurations && !isFinishing) {
            Log.i(TAG, "view: left — closed")
            leave()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backGesture) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AiUsagePin"
        /** How long the unlock is waited for: then the screen goes with nothing shown. */
        const val UNLOCK_WAIT_MS = 60_000L

        /** Where the picture is read: a thread of its own (a test reads it in place). */
        @Volatile internal var work: (() -> Unit) -> Unit = { job -> Thread(job, "pin-view-read").apply { isDaemon = true }.start() }
    }
}
