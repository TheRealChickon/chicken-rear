package dev.kb896.aiusage.rear

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer

/**
 * THE PICTURES OF ONE PAGE THREAD, ON THE DISPLAY'S VSYNC (FramePace decides when; this asks for it). One per
 * HandlerThread: [post] a page's own tick with the time it wants between two pictures, and it is run on [handler]'s
 * thread at the vsync nearest that time; [frameNs] is that frame's time while it runs, for a slide's place. In place of
 * `handler.postDelayed(tick, 16L)`, whose pictures fell one or two refreshes apart by turns.
 *
 * NEVER COUNTS ON A VSYNC IT HAS NOT MEASURED. The Choreographer's vsync is SurfaceFlinger's lead display's, which may
 * be the front (at a lower rate than the rear, or off, when the vsync is made up at 60 Hz):
 * - before it is used, and again whenever it misbehaves, two vsyncs in a row are asked for on the side (a PROBE, which
 *   no picture waits on) and the time between them is its refresh; while a probe is out, the pictures go by the clock;
 * - a refresh slower than the rear's ([FramePace.vsyncFits]) keeps it on the clock, so no page ever gets fewer pictures
 *   than it had; it is probed again every [PROBE_EVERY_NS] while pictures are wanted;
 * - each vsync asked for that comes later than one refresh can explain ([FramePace.cameLate]), or not at all (drawn by
 *   the clock at [FramePace.lateNs]), is counted: three of the last eight and it is measured again;
 * - on the clock the pictures follow the rear's own frame grid ([FramePace.clockNextNs]);
 * - no Choreographer to be had in this process: the clock, always.
 * It asks for a vsync only while a page has a picture to come: nothing is asked for at rest or while the back is dark.
 *
 * One tick is pending at most per runnable ([post] again replaces it; [cancel] takes it away, also a plain
 * `handler.post` of the same runnable). Any thread.
 */
class FramePacer(
    private val handler: Handler,
    private val name: String,
    /**
     * The rear's HIGHEST refresh rate in Hz, 0 when not known (read at most once a second). Not its rate of the moment:
     * SurfaceFlinger raises a display's rate for a layer that posts often, and pictures held to a lowered rate would keep
     * it low.
     */
    private val rearHz: () -> Float,
) {
    private val lock = Any()
    private val slots = HashMap<Runnable, Slot>()
    @Volatile private var choreographer: Choreographer? = null
    @Volatile private var noChoreographer = false

    /** The vsync's refresh as last measured by a probe (0: not yet). */
    @Volatile var periodNs = 0L
        private set
    @Volatile private var rearPeriodNs = FramePace.periodNsOf(FramePace.REAR_HZ)
    @Volatile private var rearReadAt = 0L
    private val lates = FramePace.LateWindow()
    /** A probe is out: its first vsync's time once it has come (0: not yet). */
    private var probing = false
    private var probeFirstNs = 0L
    private var probedAt = 0L

    /** The time of the frame being run (System.nanoTime's base): good inside a tick this pacer runs. */
    @Volatile var frameNs = 0L
        private set

    // What it did, said in the log at most once a minute while it is used, and at every change of its way.
    private var byVsync = 0
    private var byClock = 0
    private var late = 0
    private var loggedAt = 0L
    private var onClockSaid: Boolean? = null
    private var waySaidAt = 0L
    private var flips = 0

    init { handler.post { synchronized(lock) { if (choreographer == null && !noChoreographer) acquire() } } }

    /** The Choreographer of [handler]'s thread: ON THAT THREAD ONLY, under [lock]. */
    private fun acquire() {
        choreographer = runCatching { Choreographer.getInstance() }
            .onFailure { noChoreographer = true; Log.w(TAG, "pacer $name: no Choreographer in this process (${it.message}): pictures by the clock") }
            .getOrNull()
    }

    private inner class Slot(val r: Runnable) {
        var pending = false
        /** It waits for the clock (no vsync asked for), or for [arm] and then the vsync. */
        var byTheClock = false
        var armed = false
        /** When the vsync was asked for (System.nanoTime). */
        var askNs = 0L
        var lastNs = 0L
        var plannedNs = 0L
        val arm = Runnable { armed(this) }
        val fallback = Runnable { fellBack(this) }
        val vsync = Choreographer.VsyncCallback { data -> onVsync(this, data.frameTimeNanos) }
    }

    /**
     * [r] is run on [handler]'s thread for its next picture, [targetMs] after its last one (0: the next refresh of the
     * rear, for a slide), on a vsync. A pending one is replaced.
     */
    fun post(r: Runnable, targetMs: Float) {
        readRear(System.nanoTime())
        synchronized(lock) {
            val s = slots.getOrPut(r) { Slot(r) }
            clear(s)
            handler.removeCallbacks(r)
            val now = System.nanoTime()
            val want = FramePace.targetOn(targetMs, rearPeriodNs)
            // Made on this thread at its first post here (the init's post may not have run yet).
            if (choreographer == null && !noChoreographer && onThread()) acquire()
            val c = choreographer
            // Not measured yet, or too slow when last measured: measured (again), by the clock meanwhile.
            if (c != null && !probing && (periodNs == 0L || !FramePace.vsyncFits(periodNs, rearPeriodNs)) && now - probedAt >= PROBE_EVERY_NS) probe(c, now)
            val onClock = c == null || probing || periodNs == 0L || !FramePace.vsyncFits(periodNs, rearPeriodNs)
            if (c != null || noChoreographer) sayWay(onClock, now)
            s.pending = true
            if (onClock) {
                s.byTheClock = true
                s.plannedNs = FramePace.clockNextNs(s.lastNs, now, want, rearPeriodNs)
                // Rounded UP to the handler's whole ms, so it runs at or after the planned time and the grid stays exact.
                handler.postAtTime(s.fallback, (s.plannedNs + 999_999L) / 1_000_000L)
                return
            }
            val period = periodNs
            val due = FramePace.dueNs(s.lastNs, now, want, period)
            s.byTheClock = false
            s.plannedNs = due
            if (due <= now) { s.armed = true; s.askNs = now; c!!.postVsyncCallback(s.vsync) }
            else { s.armed = false; handler.postAtTime(s.arm, (due + 999_999L) / 1_000_000L) }
            handler.postAtTime(s.fallback, FramePace.lateNs(due, period, rearPeriodNs) / 1_000_000L)
        }
    }

    /** [r]'s next picture is not wanted (also a plain handler post of it). Any thread. */
    fun cancel(r: Runnable) {
        handler.removeCallbacks(r)
        synchronized(lock) { slots[r]?.let { clear(it) } }
    }

    private fun clear(s: Slot) {
        s.pending = false
        handler.removeCallbacks(s.arm)
        handler.removeCallbacks(s.fallback)
        if (s.armed) runCatching { choreographer?.removeVsyncCallback(s.vsync) }
        s.armed = false
    }

    private fun armed(s: Slot) = synchronized(lock) {
        if (!s.pending || s.byTheClock || s.armed) return@synchronized
        val c = choreographer ?: return@synchronized
        s.armed = true
        s.askNs = System.nanoTime()
        c.postVsyncCallback(s.vsync)
    }

    private fun onVsync(s: Slot, at: Long) {
        synchronized(lock) {
            if (!s.pending || s.byTheClock) return
            s.pending = false; s.armed = false
            handler.removeCallbacks(s.fallback)
            if (lates.note(FramePace.cameLate(s.askNs, at, periodNs))) misbehaved("came late")
            s.lastNs = at
            byVsync++
        }
        run(s, at)
    }

    private fun fellBack(s: Slot) {
        val now = System.nanoTime()
        val at: Long
        synchronized(lock) {
            if (!s.pending) return
            s.pending = false
            handler.removeCallbacks(s.arm)
            if (s.armed) runCatching { choreographer?.removeVsyncCallback(s.vsync) }
            s.armed = false
            if (s.byTheClock) {
                // On the grid it was asked for, not the moment it ran: the next one keeps the cadence.
                at = s.plannedNs.coerceAtMost(now)
            } else {
                at = now
                late++
                if (lates.note(true)) misbehaved("did not come")
            }
            byClock++
            s.lastNs = at
        }
        run(s, at)
    }

    /** Three of the last eight vsyncs were late or missing: by the clock until a probe has measured it again. Under [lock]. */
    private fun misbehaved(how: String) {
        val c = choreographer ?: return
        Log.i(TAG, "pacer $name: the vsync $how 3 times of the last 8 — measured again, by the clock meanwhile")
        probe(c, System.nanoTime())
    }

    private fun run(s: Slot, at: Long) {
        frameNs = at
        logNow(at)
        s.r.run()
    }

    /** Two vsyncs in a row asked for on the side: the time between them is the refresh this thread gets. Under [lock]. */
    private fun probe(c: Choreographer, now: Long) {
        probedAt = now
        probing = true
        probeFirstNs = 0L
        runCatching { c.removeVsyncCallback(probeCallback); c.postVsyncCallback(probeCallback) }.onFailure { probing = false }
    }

    private val probeCallback: Choreographer.VsyncCallback = Choreographer.VsyncCallback { data ->
        val at = data.frameTimeNanos
        synchronized(lock) {
            if (!probing) return@VsyncCallback
            if (probeFirstNs == 0L) {
                // The first of the two: the next vsync is the second.
                probeFirstNs = at
                runCatching { choreographer?.postVsyncCallback(probeCallback) }.onFailure { probing = false }
                return@VsyncCallback
            }
            val p = FramePace.periodFrom(probeFirstNs, at)
            probing = false
            probeFirstNs = 0L
            lates.reset()
            if (p > 0L && p != periodNs) {
                periodNs = p
                Log.i(TAG, "pacer $name: measured ${rate()}")
            }
        }
    }

    private fun readRear(now: Long) {
        if (rearReadAt != 0L && now - rearReadAt < 1_000_000_000L) return
        rearReadAt = now
        rearPeriodNs = FramePace.periodNsOf(runCatching { rearHz() }.getOrDefault(0f))
    }

    /** Its way changed: said, but at most every [WAY_LOG_NS] (the rest are counted in the minute's line). */
    private fun sayWay(onClock: Boolean, now: Long) {
        if (onClockSaid == onClock) return
        onClockSaid = onClock
        flips++
        if (waySaidAt != 0L && now - waySaidAt < WAY_LOG_NS) return
        waySaidAt = now
        Log.i(TAG, "pacer $name: ${if (onClock) "by the clock" else "on the vsync"} — ${rate()}")
    }

    private fun logNow(now: Long) {
        if (now - loggedAt < LOG_EVERY_NS) return
        synchronized(lock) {
            if (byVsync + byClock == 0) return
            Log.i(TAG, "pacer $name: ${rate()}; $byVsync pictures on the vsync, $byClock by the clock ($late of them for a vsync that did not come), $flips changes of way")
            byVsync = 0; byClock = 0; late = 0; flips = 0
            loggedAt = now
        }
    }

    private fun rate(): String {
        val v = periodNs
        val vs = if (v > 0L) "the vsync every ${"%.2f".format(v / 1e6)} ms (${"%.1f".format(1e9 / v)} Hz)" else "the vsync not measured yet"
        return "$vs, the rear at most ${"%.1f".format(1e9 / rearPeriodNs)} Hz${if (noChoreographer) ", no Choreographer" else ""}"
    }

    private fun onThread(): Boolean = Looper.myLooper() == handler.looper

    private companion object {
        const val TAG = "AiUsageRear"
        const val PROBE_EVERY_NS = 2_000_000_000L
        const val LOG_EVERY_NS = 60_000_000_000L
        const val WAY_LOG_NS = 10_000_000_000L
    }
}
