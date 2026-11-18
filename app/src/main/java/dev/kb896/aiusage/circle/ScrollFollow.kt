package dev.kb896.aiusage.circle

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * Translations move with the page WHILE it scrolls ("Scroll and translate").
 *
 * Source of the movement: the app's own accessibility nodes. When a set of translations is on the screen and the page
 * rests, each translation is matched to the app's text node under it, and the nodes are grouped by the scrolling
 * container they sit in. While the page moves, one node per container is read again every display frame on a thread
 * of its own (`refresh()`, one call into the app; it answers with where the app drew it in its latest frame), a few
 * milliseconds after the frame began so the app has moved its content for that frame. The main thread draws the
 * translations of that container moved by the same amount, carried on to its own frame ([FollowTrack]), cut to the
 * container. Pictures are not used for this (they come about three a second). Scroll events are not used either:
 * Android sends them at most ten times a second.
 *
 * ONLY WHERE IT IS SMOOTH. Each app is judged by its own motions ([FollowTrack]: readings late, stalled or jumping).
 * Matching nodes supply measured offsets. Only apps with consistently timely readings use prediction;
 * late readings keep their measured position. When a node can no longer be matched, its last offset stays
 * until a fresh capture can place the text again.
 *
 * Nothing read from the nodes is logged or kept beyond the session: the text is only hashed, to notice a list that
 * reused a row for other content.
 */
class ScrollFollow(private val svc: AccessibilityService, private val prefs: SharedPreferences) {
    companion object {
        const val MAX_G = 4           // containers followed at once
        private const val MAX_NODES = 2500
        const val TRUST_AFTER = 2     // clean motions in a row before enabling prediction
        private const val READ_DELAY_MS = 3L
    }

    /** What the overlay draws with (main thread only). */
    class Out {
        var live = false                       // a set is anchored
        var list: Any? = null                  // the patch list the groups belong to
        var groups = IntArray(0)               // per patch: its container, -1 = none, -2 = in no scrolling part
        val dy = FloatArray(MAX_G)             // how far the content moved up since the anchoring, screen px
        val clip = Array(MAX_G) { Rect() }
        val lost = BooleanArray(MAX_G)         // cannot be followed any more (until the page rests)
        var predict = false                   // measured offsets may be extrapolated by up to two frames
        var n = 0
        /** A scroll was reported since the anchoring. */
        var scrolled = false
        /** The page has moved since the anchoring: draw the followed way. */
        fun moved(): Boolean { if (scrolled) return true; for (g in 0 until n) if (dy[g] != 0f || lost[g]) return true; return false }
    }
    val out = Out()
    /** Called on the main thread after [out] changed (the view invalidates). */
    var onChange: (() -> Unit)? = null
    /** One line about a finished motion (for the debug log). */
    var onMotion: ((String) -> Unit)? = null
    /** Shown on the screen whatever the verdict (measuring: `SET --ez follow_trust true`). */

    private val th = HandlerThread("scroll-follow", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val h = Handler(th.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private var bgChor: Choreographer? = null
    init { h.post { bgChor = Choreographer.getInstance() } }

    private val lock = Any()
    // Shared with the main thread, under [lock].
    @Volatile private var epoch = 0
    private var nG = 0
    private val gLost = BooleanArray(MAX_G)
    private val tracks = arrayOfNulls<FollowTrack>(MAX_G)

    // Background thread only.
    private class Anchor(val node: AccessibilityNodeInfo, val top: Int, val bottom: Int, val left: Int, val hash: Int)
    private val anchors = Array(MAX_G) { ArrayList<Anchor>() }
    private val cur = IntArray(MAX_G)
    private val region = Array(MAX_G) { Rect() }
    private var pkg = ""
    private var frameNs = 16_666_667L
    private var polling = false
    @Volatile private var pollUntil = 0L
    private var motionReadings = 0
    private var slowReads = 0
    private val rb = Rect()

    /** Lead of the drawn value: the readings are aimed at the time the main thread's frame is shown. */
    /** Measuring: one line per reading and per drawn frame into this file (null = off). */

    private fun verdict(p: String) = prefs.getInt("follow_v:$p", 0)

    // ---------------------------------------------------------------- anchoring (page at rest)

    /**
     * A new set of translations is on the screen (boxes of the original text, screen px). Matching runs on the
     * follow thread; [done] gets a summary on the main thread, unless another anchoring or [drop] came first.
     */
    fun anchor(list: Any, boxes: List<Rect>, done: ((String) -> Unit)? = null) {
        val e = synchronized(lock) { ++epoch; nG = 0; epoch }
        out.live = false; out.list = null; out.scrolled = false
        h.post {
            if (e != epoch) return@post
            val t0 = SystemClock.uptimeMillis()
            val res = runCatching { match(boxes) }.getOrNull()
            val ms = SystemClock.uptimeMillis() - t0
            if (res == null || e != epoch) return@post
            val (groups, n, nodes) = res
            val jump = 8 * svc.resources.displayMetrics.density
            val trusted = verdict(pkg) >= TRUST_AFTER
            val hz = runCatching { svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0).refreshRate }.getOrDefault(60f)
            frameNs = (1e9 / hz.coerceIn(30f, 240f)).toLong()
            synchronized(lock) {
                if (e != epoch) return@post
                nG = n
                for (g in 0 until n) { gLost[g] = false; tracks[g] = FollowTrack(jump, frameNs) }
            }
            motionReadings = 0; slowReads = 0
            val regions = Array(n) { Rect(region[it]) }
            val p = pkg
            main.post {
                if (e != epoch) return@post
                out.list = list; out.groups = groups; out.n = n; out.scrolled = false; out.predict = trusted
                for (g in 0 until MAX_G) { out.dy[g] = 0f; out.lost[g] = false }
                for (g in 0 until n) out.clip[g].set(regions[g])
                out.live = true
                val mode = if (trusted) "prediction enabled" else "measured offsets"
                done?.invoke("${boxes.size} translations: ${groups.count { it >= 0 }} in $n scrolling part(s), " +
                    "${groups.count { it == -2 }} fixed, ${groups.count { it == -1 }} without a node; " +
                    "$nodes nodes in $ms ms; $p $mode (verdict ${verdict(p)})")
            }
        }
    }

    /** The set on the screen is gone or replaced in a way that cannot be followed. */
    fun close() {
        drop()
        th.quitSafely()
    }

    fun drop() {
        synchronized(lock) { ++epoch; nG = 0 }
        out.live = false; out.list = null; out.scrolled = false
        stopMain()
    }

    /** A scroll was reported by the app (an event, or content changing right after a touch). */
    fun scrolled() { if (out.live && !out.scrolled) { out.scrolled = true; onChange?.invoke() }; wake() }

    private data class Match(val groups: IntArray, val n: Int, val nodes: Int)

    private fun appWindow(): AccessibilityWindowInfo? = svc.windows.let { ws ->
        ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    }

    private fun match(boxes: List<Rect>): Match {
        for (g in 0 until MAX_G) { anchors[g].clear(); cur[g] = 0 }
        val groups = IntArray(boxes.size) { -1 }
        val root = appWindow()?.root ?: return Match(groups, 0, 0)
        pkg = root.packageName?.toString() ?: ""
        // Every node that shows text, with its bounds.
        val nodes = ArrayList<AccessibilityNodeInfo>(); val bounds = ArrayList<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var seen = 0
        var anyScroll = false
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst(); seen++
            val b = Rect(); n.getBoundsInScreen(b)
            if (b.isEmpty || n.isPassword || n.isEditable) continue
            if (n.isScrollable) anyScroll = true
            if (n.isVisibleToUser && (!n.text.isNullOrEmpty() || (n.childCount == 0 && !n.contentDescription.isNullOrEmpty()))) { nodes.add(n); bounds.add(b) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        // An app that says nothing scrolls (games, some custom views): there are no scroll anchors.
        if (!anyScroll) return Match(groups, 0, seen)
        // Each box gets the node that covers it best: most of the box inside the node, the node not much bigger.
        val hit = arrayOfNulls<AccessibilityNodeInfo>(boxes.size)
        val hitB = arrayOfNulls<Rect>(boxes.size)
        for ((i, p) in boxes.withIndex()) {
            val pa = p.width().toLong() * p.height()
            if (pa <= 0) continue
            var best = 0.0; var bi = -1
            for (j in nodes.indices) {
                val b = bounds[j]
                val iw = minOf(p.right, b.right) - maxOf(p.left, b.left); val ih = minOf(p.bottom, b.bottom) - maxOf(p.top, b.top)
                if (iw <= 0 || ih <= 0) continue
                val inter = iw.toLong() * ih
                val na = b.width().toLong() * b.height()
                val cover = inter.toDouble() / pa
                if (cover < 0.5 || na > pa * 12) continue
                val score = inter.toDouble() / (pa + na - inter)
                if (score > best) { best = score; bi = j }
            }
            if (bi >= 0) { hit[i] = nodes[bi]; hitB[i] = bounds[bi] }
        }
        // Group by the scrolling container each node sits in.
        val keys = ArrayList<AccessibilityNodeInfo>()
        for (i in boxes.indices) {
            val n = hit[i] ?: continue
            // Grouped by the innermost scrolling container; cut to the outermost one, which is the part of the window
            // that scrolls (an inner list grows and shrinks on the screen as an outer header scrolls away).
            var p: AccessibilityNodeInfo? = n.parent; var depth = 0; var sc: AccessibilityNodeInfo? = null; var outer: AccessibilityNodeInfo? = null
            while (p != null && depth < 40) { if (p.isScrollable) { if (sc == null) sc = p; outer = p }; p = p.parent; depth++ }
            if (sc == null) { groups[i] = -2; continue }       // in no scrolling container: it stays where it is
            var g = keys.indexOf(sc)
            if (g < 0) {
                if (keys.size >= MAX_G) continue
                keys.add(sc); g = keys.size - 1
                (outer ?: sc).getBoundsInScreen(region[g])
            }
            groups[i] = g
        }
        // Anchors per container: its translations' nodes that are wholly in view, nearest the middle first.
        for (g in keys.indices) {
            val rg = region[g]
            val cand = boxes.indices.filter { groups[it] == g }.mapNotNull { i -> val b = hitB[i]!!; if (b.top > rg.top && b.bottom < rg.bottom) i else null }
                .sortedBy { abs(hitB[it]!!.centerY() - rg.centerY()) }
            val used = HashSet<AccessibilityNodeInfo>()
            for (i in cand) {
                val n = hit[i]!!; if (!used.add(n)) continue
                val b = hitB[i]!!
                anchors[g].add(Anchor(n, b.top, b.bottom, b.left, hashOf(n)))
                if (anchors[g].size >= 6) break
            }
        }
        // A container without an anchor keeps its last known positions until the next capture.
        for (g in keys.indices) if (anchors[g].isEmpty()) { for (i in groups.indices) if (groups[i] == g) groups[i] = -1 }
        return Match(groups, keys.size, seen)
    }

    private fun hashOf(n: AccessibilityNodeInfo) = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").hashCode()

    // ---------------------------------------------------------------- following (page moving)

    /** A touch or a scroll event: read the containers every frame for a while. */
    fun wake(ms: Long = 450) {
        if (!out.live) return
        pollUntil = maxOf(pollUntil, SystemClock.uptimeMillis() + ms)
        if (!polling) h.post {
            if (!polling && bgChor != null) {
                polling = true; motionReadings = 0; slowReads = 0
                synchronized(lock) { for (g in 0 until nG) tracks[g]?.newMotion() }
                bgChor!!.postFrameCallback(poll)
            }
        }
        startMain()
    }

    // The frame being read: its vsync time, and the read itself, a few ms after the frame began.
    private var readFt = 0L
    private val readNow = Runnable {
        val e = epoch; val n = nG
        for (g in 0 until n) { if (e != epoch) break; read(g, e, readFt) }
    }
    private val endNow = Runnable { endMotion() }
    private val poll = object : Choreographer.FrameCallback {
        override fun doFrame(ft: Long) {
            readFt = ft
            h.removeCallbacks(readNow); h.postDelayed(readNow, READ_DELAY_MS)
            if (SystemClock.uptimeMillis() > pollUntil) { polling = false; h.postDelayed(endNow, READ_DELAY_MS + 1); return }
            bgChor!!.postFrameCallback(this)
        }
    }

    /** The motion is over: the app's verdict is updated from what its readings showed. */
    private fun endMotion() {
        if (polling) return
        val e = epoch
        var clean = 0; var rough = 0; var worst = 0f; var moved = 0
        synchronized(lock) {
            if (e != epoch) return
            for (g in 0 until nG) {
                val t = tracks[g] ?: continue
                if (t.rough) rough++ else if (t.clean >= FollowTrack.CLEAN_READINGS) clean++
                if (t.worst > worst) worst = t.worst
                if (t.clean > 0 || t.hits > 0) moved++
            }
        }
        if (moved == 0 || pkg.isEmpty()) return
        val before = verdict(pkg)
        val after = when { rough > 0 -> minOf(before, 0) - 1; clean > 0 -> maxOf(before, 0) + 1; else -> before }.coerceIn(-9, 9)
        if (after != before) prefs.edit().putInt("follow_v:$pkg", after).apply()
        val msg = "follow: motion in $pkg ${if (rough > 0) "ROUGH" else if (clean > 0) "clean" else "too short to judge"} " +
            "(largest miss ${worst.toInt()} px, $slowReads late readings of $motionReadings), verdict $before -> $after"
        main.post { onMotion?.invoke(msg) }
    }

    private fun read(g: Int, e: Int, ft: Long) {
        if (synchronized(lock) { gLost[g] }) return
        val list = anchors[g]
        var tries = 0
        while (tries < list.size) {
            val a = list[cur[g]]
            val t0 = System.nanoTime()
            val ok = runCatching { a.node.refresh() }.getOrDefault(false)
            val t1 = System.nanoTime()
            var y = Int.MIN_VALUE
            rb.setEmpty()
            if (ok && a.node.isVisibleToUser && !a.node.isPassword && !a.node.isEditable && hashOf(a.node) == a.hash) {
                a.node.getBoundsInScreen(rb)
                val rh = a.bottom - a.top
                if (!rb.isEmpty && abs(rb.left - a.left) <= 8) {
                    // Bounds are cut to what is visible: take the edge that is not cut.
                    y = when {
                        abs(rb.height() - rh) <= 1 -> a.top - rb.top
                        rb.height() >= rh * 0.35f && rb.top > region[g].top + 1 -> a.top - rb.top
                        rb.height() >= rh * 0.35f -> a.bottom - rb.bottom
                        else -> Int.MIN_VALUE
                    }
                } else if (!rb.isEmpty) { lose(g, e, "moved sideways"); return }
            }
            if (y != Int.MIN_VALUE) {
                motionReadings++
                // Answered after the next frame began: the app may have moved again meanwhile, so the value cannot be
                // pinned to a frame. Left out (the track carries on and calls a long gap rough).
                if (t1 - ft > frameNs + READ_DELAY_MS * 1_000_000L) { slowReads++; return }
                synchronized(lock) {
                    if (e != epoch) return
                    val t = tracks[g] ?: return
                    val before = t.last
                    t.add(ft, y)
                    if (y != before) pollUntil = maxOf(pollUntil, SystemClock.uptimeMillis() + 350)
                }
                return
            }
            cur[g] = (cur[g] + 1) % list.size; tries++
        }
        lose(g, e, "no anchor left")
    }

    private fun lose(g: Int, e: Int, why: String) {
        synchronized(lock) { if (e == epoch) gLost[g] = true }
    }

    // ---------------------------------------------------------------- main thread: one value per drawn frame

    private var mainOn = false
    private val mainCb = object : Choreographer.FrameCallback {
        override fun doFrame(ft: Long) {
            if (!mainOn) return
            val changed = update(ft)
            if (changed) onChange?.invoke()
            if (SystemClock.uptimeMillis() > pollUntil + 120 && !polling) { mainOn = false; return }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun startMain() {
        main.post { if (!mainOn) { mainOn = true; Choreographer.getInstance().postFrameCallback(mainCb) } }
    }
    private fun stopMain() { main.post { mainOn = false } }

    /** Moves [out] to where the content will be when the frame of [ft] is on the screen. True if anything changed. */
    private fun update(ft: Long): Boolean {
        if (!out.live) return false
        var changed = false
        synchronized(lock) {
            for (g in 0 until minOf(nG, out.n)) {
                if (gLost[g] != out.lost[g]) { out.lost[g] = gLost[g]; changed = true }
                val t = tracks[g] ?: continue
                if (t.count == 0) continue
                val r = if (out.predict && !t.rough && !gLost[g]) Math.round(t.at(ft)).toFloat() else t.last.toFloat()
                if (r != out.dy[g]) { out.dy[g] = r; changed = true }
            }
        }
        return changed
    }
}
