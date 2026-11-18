package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.DashboardState
import dev.kb896.aiusage.data.Health
import dev.kb896.aiusage.data.MascotMood
import dev.kb896.aiusage.data.WindowKind
import dev.kb896.aiusage.rear.dots.DotsActor
import dev.kb896.aiusage.rear.dots.DotsFeed

/**
 * The rear usage page. Each account shows its weekly usage by default, with an optional
 * five-hour vessel. Model-specific limits share the weekly vessel at their own levels.
 * Reset times appear above the fluid. Claude's mascot follows the fullest vessel.
 * ChatGPT uses its installed app icon, with the optional Dots animation when available.
 * With no connected accounts, a single button opens sign-in on the front screen.
 */
object UsageFace {

    /** One limit: how much of it is used (0..1), how long until it resets (in words, "" for none); [model]: a model's own week, by its name. */
    data class Limit(
        val name: String, val used: Float, val until: String, val model: Boolean = false,
        /** The five hours (shown only with the switch "Five-hour limits"). */
        val five: Boolean = false,
    )

    /** One account: its five hours first, then the week of all models, then each model's own week. [mood]: what its mascot has earned (MascotMood). */
    data class Column(
        val name: String, val limits: List<Limit>, val note: String = "", val mood: String = MascotMood.SLEEP,
        /** The app the account is of, by its package (Account.app): used to select the account's artwork. */
        val app: String = "",
    ) {
        /** The five hours stand in a vessel of their own, beside the week. */
        val split: Boolean get() = limits.size > 1 && limits[0].five
        /** ChatGPT's account: uses the artwork available from the installed ChatGPT app. */
        val chatGpt: Boolean get() = app == Account.CHATGPT.app
    }

    /** The whole page: its accounts, or [empty] alone when nobody is signed in. [demo] marks made-up figures. */
    data class Model(val columns: List<Column>, val empty: String = "", val demo: Boolean = false)

    /** "2h14m", "3d4h", "12m", "now"; "" when there is nothing to wait for. */
    fun until(resetsAt: Long?, now: Long): String {
        if (resetsAt == null) return ""
        val min = ((resetsAt - now) / 60_000L).toInt()
        return when {
            min <= 0 -> "now"
            min < 60 -> "${min}m"
            min < 24 * 60 -> "${min / 60}h${"%02d".format(min % 60)}m"
            else -> "${min / (24 * 60)}d${(min % (24 * 60)) / 60}h"
        }
    }

    /**
     * The page for [state] at [now]. [names]: an account's custom name (kept for the model, not drawn).
     * [fiveHours]: the switch "Five-hour limits"; off (the default), an account is its week alone. An account that
     * has no week at all (a plan with five hours only) shows its five hours all the same, rather than an empty
     * vessel that says it is waiting.
     */
    fun model(state: DashboardState, now: Long, names: (Account) -> String? = { null }, fiveHours: Boolean = false): Model {
        val columns = state.statuses.filter { it.connected }.take(2).map { s ->
            val name = names(s.account)?.takeIf { it.isNotBlank() } ?: s.account.label.lowercase().replaceFirstChar { it.uppercase() }
            val windows = s.snapshot?.windows.orEmpty()
            val session = windows.firstOrNull { it.kind == WindowKind.SESSION }?.let { Limit("5 h", it.utilization.coerceIn(0f, 1f), until(it.resetsAt, now), five = true) }
            val weeks = listOfNotNull(
                windows.firstOrNull { it.kind == WindowKind.WEEKLY_ALL }?.let { Limit("Week", it.utilization.coerceIn(0f, 1f), until(it.resetsAt, now)) },
            ) + windows.filter { it.kind == WindowKind.WEEKLY_SCOPED }.map {
                // A model's own week ("Fable 7d"): known by the model alone, and shown by its colour.
                Limit(it.label.substringBefore(' ').ifBlank { "Model" }, it.utilization.coerceIn(0f, 1f), until(it.resetsAt, now), model = true)
            }
            val limits = if (fiveHours || weeks.isEmpty()) listOfNotNull(session) + weeks else weeks
            val note = when {
                s.health == Health.AUTH -> "Sign in again in the app"
                limits.isEmpty() && s.health == Health.ERROR -> "No answer yet"
                limits.isEmpty() -> "Waiting for the first reading"
                else -> ""
            }
            Column(name, limits, note, s.mascot, app = s.account.app)
        }
        return if (columns.isEmpty()) Model(emptyList(), empty = "Sign in") else Model(columns)
    }

    /** Made-up figures, for a look at the page with nobody signed in (debug, or the settings' demo mode). */
    fun demo(now: Long, fiveHours: Boolean = false): Model = Model(
        listOf(
            Column("Personal", listOf(Limit("5 h", 0.42f, until(now + 134 * 60_000L, now), five = true), Limit("Week", 0.71f, until(now + (3 * 24 + 4) * 3_600_000L, now)), Limit("Fable", 0.18f, until(now + (3 * 24 + 4) * 3_600_000L, now), model = true)), mood = MascotMood.WALK),
            Column("Work", listOf(Limit("5 h", 0.08f, until(now + 281 * 60_000L, now), five = true), Limit("Week", 0.23f, until(now + (5 * 24 + 11) * 3_600_000L, now))), mood = MascotMood.SLEEP),
        ).map { if (fiveHours) it else it.copy(limits = it.limits.filter { l -> !l.five }) },
        demo = true,
    )

    // ---- the colours ----

    /** All models: the neutral fluid. */
    const val ALL = 0xFFE9E4D8.toInt()
    /** A model's own week, by the order the models come in: Fable's warm amber first; no purple among them. */
    val MODELS = intArrayOf(0xFFF2A93B.toInt(), 0xFF6FC3B2.toInt(), 0xFFE58B8B.toInt())

    /** The fluid's colour for limit [index] of an account that has its five hours (0 the five hours, 1 the week, 2 and up the models). */
    fun color(index: Int): Int = if (index <= 1) ALL else MODELS[(index - 2) % MODELS.size]

    /** The fluid's colour for limit [i] of [col], whichever limits it has: the neutral for its own windows, each model its colour in turn. */
    fun color(col: Column, i: Int): Int {
        if (!col.limits[i].model) return ALL
        return MODELS[col.limits.take(i).count { it.model } % MODELS.size]
    }

    // ---- where everything stands ----

    /**
     * One vessel: whose (account), [which] of its two (0 the five hours, 1 the week, its levels), the limits
     * that stand in it (their indices in the account's list), its shape, and its head: the top of
     * the vessel, where its time stands.
     */
    class Tank(val account: Int, val which: Int, val limits: List<Int>, val vessel: RectF, val reading: RectF)

    /**
     * The vessels of [m] on a 976x596 panel: per account ONE, the week with its models' levels (one account the whole
     * panel right of the lenses, two a half each); with the five hours on, the five hours, wider, beside the week.
     */
    fun tanks(m: Model): List<Tank> {
        val out = ArrayList<Tank>()
        val n = m.columns.size
        if (n == 0) return out
        val share = (RIGHT - LEFT - (n - 1) * ACCOUNT_GAP) / n
        for ((a, col) in m.columns.withIndex()) {
            if (col.limits.isEmpty()) continue
            val left = LEFT + a * (share + ACCOUNT_GAP)
            if (!col.split) {
                out += Tank(a, if (col.limits[0].five) 0 else 1, col.limits.indices.toList(), RectF(left, TOP, left + share, BOTTOM), RectF(left, TOP, left + share, TOP + HEAD))
                continue
            }
            val week = (1 until col.limits.size).toList()
            val w0 = (share - TANK_GAP) * 0.56f
            out += Tank(a, 0, listOf(0), RectF(left, TOP, left + w0, BOTTOM), RectF(left, TOP, left + w0, TOP + HEAD))
            out += Tank(a, 1, week, RectF(left + w0 + TANK_GAP, TOP, left + share, BOTTOM), RectF(left + w0 + TANK_GAP, TOP, left + share, TOP + HEAD))
        }
        return out
    }

    /** The mascot's height in a vessel [w] wide. */
    fun mascotHeight(w: Float): Float = (w * 0.42f).coerceIn(48f, 118f)

    /** The side of OpenAI's Dots character's square (its own picture has room round the figure) in a vessel [w] wide. */
    fun dotSide(w: Float): Float = minOf(mascotHeight(w) * 1.3f, w - 16f, BOTTOM - TOP - HEAD - 16f)

    /**
     * Where ChatGPT's Dots character stands in [t]: a square of [dotSide], across the middle, its foot a little into
     * the fluid at [surface]; never up in the vessel's head, never out of the vessel.
     */
    fun dotBox(t: Tank, surface: Float): RectF {
        val side = dotSide(t.vessel.width())
        val floor = TOP + HEAD + 8f
        var bottom = (surface + side * DOT_SINK).coerceAtMost(t.vessel.bottom - 4f)
        if (bottom - side < floor) bottom = floor + side
        val cx = t.vessel.centerX()
        return RectF(cx - side / 2f, bottom - side, cx + side / 2f, bottom)
    }

    /** Whether a tap at [x], [y] (rear pixels of a 976x596 panel) is on the empty page's one button. */
    fun onSignIn(x: Float, y: Float): Boolean {
        val cx = (LEFT + RIGHT) / 2f
        return kotlin.math.abs(x - cx) <= SIGN_W / 2f + 16f && y >= SIGN_TOP - 16f && y <= SIGN_TOP + SIGN_H + 16f
    }

    // ---- the mascot, alive (MascotLive) ----

    private val actors = HashMap<Int, MascotLive.Actor>()
    private val dotActors = HashMap<Int, DotsActor>()
    private val vesselPath = android.graphics.Path()
    /** Where the middle of each drawn cycle's figure is across its box (0..1), from its first drawing: the figure, not its dumbbells or flag, stands on the spot. */
    private val middles = HashMap<String, Float>()
    private fun middle(clip: MascotFrames.Clip): Float = middles.getOrPut(clip.name) {
        val bodyParts = clip.frames[0].filter { it.color == MascotRenderer.BODY_PURPLE }
        val b = bodyParts.maxByOrNull { it.w * it.h }
        if (b == null) 0.5f else b.x + b.w / 2f
    }

    // ---- the picture ----

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint()
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = INK_BLACK }
    private val rect = RectF()
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** An account's app icon in its first vessel's corner, and how far in from the corner. */
    private const val MARK = 24f
    private const val MARK_INSET = 12f
    private var faces: Array<Typeface>? = null
    private var shownFor = Long.MAX_VALUE

    private fun faces() = faces ?: arrayOf(
        Typeface.create(Typeface.DEFAULT, 400, false),
        Typeface.create(Typeface.DEFAULT, 500, false),
        Typeface.create(Typeface.DEFAULT, 600, false),      // the timer page's
    ).also { faces = it }

    private fun ease(t: Float): Float { val p = t.coerceIn(0f, 1f); return 1f - (1f - p) * (1f - p) * (1f - p) }

    /**
     * Draws the page over the whole canvas, black wherever it draws nothing else. [sinceShown]: ms
     * since the page came up (its fluid rises once); [water]: the fluid's picture
     * (TimerFace.ART_WATER), or null for a plain fill.
     */
    @Synchronized
    fun draw(
        c: Canvas, w: Float, h: Float, m: Model, now: Long = System.currentTimeMillis(), sinceShown: Long = Long.MAX_VALUE, water: Bitmap? = null,
        /** An app's real icon by its package, or null where it cannot be had (then nothing stands in for it). */
        icon: (String) -> Bitmap? = { null },
        /** OpenAI's Dots character as the ChatGPT app's own renderer draws it (rear/dots), or null to use the app icon. */
        dots: DotsFeed? = null,
    ) {
        val u = h / 596f
        val f = faces()
        c.drawColor(INK_BLACK)
        fun say(s: String, x: Float, y: Float, size: Float, face: Int, color: Int, alpha: Int, align: Paint.Align = Paint.Align.LEFT) {
            text.typeface = f[face]; text.textSize = size * u; text.color = color; text.alpha = alpha; text.textAlign = align; text.letterSpacing = 0f
            c.drawText(s, x * u, y * u, text)
        }
        if (m.columns.isEmpty()) {
            // Nobody signed in: ONE button. A tap opens sign-in on the front screen.
            val cx = (LEFT + RIGHT) / 2f
            fill.color = INK; fill.alpha = 240
            rect.set((cx - SIGN_W / 2f) * u, SIGN_TOP * u, (cx + SIGN_W / 2f) * u, (SIGN_TOP + SIGN_H) * u)
            c.drawRoundRect(rect, SIGN_H * u / 2f, SIGN_H * u / 2f, fill)
            text.typeface = f[1]; text.textSize = 34f * u; text.color = INK_BLACK; text.alpha = 255; text.textAlign = Paint.Align.CENTER
            c.drawText(m.empty, cx * u, (SIGN_TOP + SIGN_H / 2f + 12f) * u, text)
            say("Opens on the front screen", cx, SIGN_TOP + SIGN_H + 50f, 30f, 0, INK, 170, Paint.Align.CENTER)
            return
        }
        // A page that has just come up again starts its mascots afresh.
        if (sinceShown < shownFor) { for (a in actors.values) a.reset(); for (a in dotActors.values) a.reset() }
        shownFor = sinceShown
        val grow = ease(sinceShown.coerceAtLeast(0L) / RISE_MS)
        val opened = now - sinceShown.coerceIn(0L, 86_400_000L)

        val boxes = tanks(m)
        // Where each vessel's top fluid stands, for the mascot to stand on.
        val surfaces = HashMap<Tank, Float>()
        for (t in boxes) {
            val col = m.columns[t.account]
            val v = t.vessel; val tw = v.width()
            rect.set(v.left * u, v.top * u, v.right * u, v.bottom * u)
            val radius = minOf(tw / 2f, 44f) * u
            fill.color = INK; fill.alpha = 16
            c.drawRoundRect(rect, radius, radius, fill)
            // Its levels, the highest behind and the lowest in front, each in its own colour, each with its own surface.
            var top = v.bottom
            for (i in t.limits.sortedByDescending { col.limits[it].used }) {
                val level = col.limits[i].used * grow
                val surface = v.bottom - level * v.height()
                top = minOf(top, surface)
                if (level > 0.002f) Fluid.draw(c, rect, radius, surface * u, now, water, u, tint = color(col, i), driftMs = DRIFT_MS)
            }
            surfaces[t] = top

            // In its head, in the timer page's type: when it starts again. A model whose week starts again at another time: its own, in its colour.
            val head = t.reading
            val big = (tw * 0.19f).coerceIn(26f, 46f)
            val first = col.limits[t.limits.first()]
            // On the fluid, where a vessel is so full that it reaches its head, the time is written dark: light on the light fluid could not be read.
            val base = head.top + 26f + big * 0.8f
            val onFluid = top < base - big * 0.4f
            if (first.until.isNotEmpty()) say(first.until, head.centerX(), base, big, 2, if (onFluid) INK_BLACK else INK, 250, Paint.Align.CENTER)
            var line = 1
            for (i in t.limits.drop(1)) {
                val other = col.limits[i]
                if (other.until.isEmpty() || other.until == first.until) continue
                say(other.until, head.centerX(), base + line * big * 0.74f, big * 0.62f, 2, if (onFluid) INK_BLACK else color(col, i), 240, Paint.Align.CENTER)
                line++
            }
        }

        // Keep the Claude app mark in the header when both providers are present.
        if (m.columns.map { it.app }.filter { it.isNotEmpty() }.distinct().size > 1) {
            for ((a, col) in m.columns.withIndex()) {
                if (col.chatGpt) continue
                val bmp = icon(col.app) ?: continue
                val t = boxes.firstOrNull { it.account == a } ?: continue
                rect.set((t.vessel.left + MARK_INSET) * u, (t.vessel.top + MARK_INSET) * u, (t.vessel.left + MARK_INSET + MARK) * u, (t.vessel.top + MARK_INSET + MARK) * u)
                markPaint.alpha = 215
                c.drawBitmap(bmp, null, rect, markPaint)
            }
        }

        // The mascots: one per account, on the vessel that matters, alive (MascotLive): standing, walking the vessel, hopping to another, or in one of upstream's cycles.
        // ChatGPT uses the installed icon when the optional Dots animation has no frame.
        for ((a, col) in m.columns.withIndex()) {
            val mine = boxes.filter { it.account == a }
            if (mine.isEmpty()) continue
            val levels = mine.map { t -> t.limits.maxOf { col.limits[it].used } }
            if (col.chatGpt) {
                val frame = dots?.let {
                    val act = dotActors.getOrPut(a) { DotsActor(a) }.at(col.limits.map { it.used }, col.mood, now, opened)
                    if (act.signature) it.signature()
                    it.calm(act.calm)
                    it.picture()
                }
                val pic = frame ?: icon(col.app) ?: continue
                // In the vessel nearest its limit (the week, where the five hours are not shown).
                val t = mine[levels.indices.maxByOrNull { levels[it] + it * 0.0001f } ?: 0]
                val box = dotBox(t, surfaces[t] ?: t.vessel.bottom)
                val v = t.vessel
                val radius = minOf(v.width() / 2f, 44f) * u
                c.save()
                vesselPath.reset(); vesselPath.addRoundRect(v.left * u, v.top * u, v.right * u, v.bottom * u, radius, radius, android.graphics.Path.Direction.CW)
                c.clipPath(vesselPath)
                c.clipRect(v.left * u, (TOP + HEAD + 8f) * u, v.right * u, v.bottom * u)
                rect.set(box.left * u, box.top * u, box.right * u, box.bottom * u)
                markPaint.alpha = 255
                c.drawBitmap(pic, null, rect, markPaint)
                c.restore()
                continue
            }
            val pose = actors.getOrPut(a) { MascotLive.Actor(a) }.at(col.limits.map { it.used }, col.mood, now, opened, levels)
            val fromTank = mine.getOrNull(pose.vessel) ?: mine[0]
            val toTank = mine.getOrNull(pose.to) ?: fromTank
            val k = pose.along
            val tw = fromTank.vessel.width() + (toTank.vessel.width() - fromTank.vessel.width()) * k
            // One size for the figure however it is drawn: a pixel count to one of its units (107 x 86).
            val unit = mascotHeight(tw) / MascotFrames.REFERENCE_BOX
            // Across a vessel: from one wall to the other, the figure whole inside it.
            fun across(t: Tank): Float {
                val half = MascotLive.W / 2f * (mascotHeight(t.vessel.width()) / MascotFrames.REFERENCE_BOX) + 8f
                return t.vessel.left + half + (t.vessel.width() - 2f * half).coerceAtLeast(0f) * pose.spot
            }
            val x = across(fromTank) + (across(toTank) - across(fromTank)) * k
            val g0 = surfaces[fromTank] ?: fromTank.vessel.bottom
            val g1 = surfaces[toTank] ?: toTank.vessel.bottom
            // Standing in the fluid to its feet; a leap's arc over that; and never up into a vessel's head, so never on a figure.
            val high = pose.height * (if (fromTank !== toTank) HOP_HEIGHT else JUMP_HEIGHT)
            val tall = MascotLive.H * unit
            val feet = (g0 + (g1 - g0) * k + tall * 0.08f - high).coerceAtLeast(TOP + HEAD + 8f + tall).coerceAtMost(BOTTOM + tall * 0.08f)
            val clip = pose.clip?.let { name -> MascotFrames.CLIPS.firstOrNull { it.name == name } }
            if (clip == null) MascotLive.draw(c, pose.rig, x * u, feet * u, unit * u, body, OriginalUsage.CLAUDE, EYE)
            else {
                val mh = clip.boxHeight * unit
                val mw = mh * clip.aspect
                // A cycle with scenery over the figure (the sleeper's Zs) stays under the vessel's head too.
                val top = (feet - mh).coerceAtLeast(TOP + HEAD + 8f)
                eye.strokeWidth = 2f * u
                MascotRenderer.draw(c, clip, pose.frame * clip.frameMs, (x - middle(clip) * mw) * u, top * u, mh * u, body, eye, accent = WHITE, dead = pose.dead, bodyColor = OriginalUsage.CLAUDE)
            }
        }
        // An account with nothing to read yet says so where its vessels would be.
        for ((a, col) in m.columns.withIndex()) if (col.limits.isEmpty() && col.note.isNotEmpty()) {
            val share = (RIGHT - LEFT - (m.columns.size - 1) * ACCOUNT_GAP) / m.columns.size
            // In the middle of that account's part, across and down, as large as its room lets it be (it was 26 px
            // at the part's left edge).
            var size = 40f
            text.typeface = f[0]
            while (size > 20f) { text.textSize = size * u; if (text.measureText(col.note) <= (share - 16f) * u) break; size -= 2f }
            say(col.note, LEFT + a * (share + ACCOUNT_GAP) + share / 2f, NoticePop.MIDDLE + size * 0.36f, size, 0, INK, 200, Paint.Align.CENTER)
        }
        if (m.demo) say("Demo", RIGHT, 22f, 16f, 0, INK, 90, Paint.Align.RIGHT)
    }

    private const val INK = 0xFFF1EFEA.toInt()
    private const val INK_BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    /** The mascot's eyes, as upstream's drawings have them. */
    private const val EYE = 0xFF1D1F21.toInt()
    /** Right of the lenses, and in from the rounded corners. */
    const val LEFT = 330f
    const val RIGHT = 948f
    const val TOP = 30f
    const val BOTTOM = 572f
    /** A vessel's head: the room at its top for its time, which the mascot stays under. */
    const val HEAD = 112f
    /** Once across for the fluid's surface here: slow (the timer's is two seconds). */
    private const val DRIFT_MS = 14_000L
    private const val TANK_GAP = 18f
    private const val ACCOUNT_GAP = 30f
    private const val RISE_MS = 900f
    private const val HOP_HEIGHT = 70f
    /** How far the Dots character's square stands down into the fluid, of its side (its picture has room under the figure). */
    private const val DOT_SINK = 0.10f
    private const val JUMP_HEIGHT = 46f
    private const val SIGN_W = 300f
    private const val SIGN_H = 84f
    private const val SIGN_TOP = 232f
}
