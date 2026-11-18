package dev.kb896.aiusage.rear

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared timeline for the rear message island and its settings preview.
 *  1. a small black pill drops in at the top of what he sees (centred on x 636), widens to show the app's own icon, who
 *     it is from and one line of the message, while Xiaomi's light flows once round its outline (the measured palette,
 *     [PALETTE], three falloffs outside it, a lit lip, a little light in the black glass);
 *  2. the pill grows into the whole page: its words fade out quickly, the shape grows with no words, the one icon
 *     travels to the page's head, the page's words fade in at the end (no line is ever stretched from one layout to the
 *     other); the light follows the growing outline and is gone as it opens;
 *  3. the page holds as the full pop-up does ([HOLD_MS]), over THE LIVE FACE: [Look.TINT_OVER_FACE] leaves the face as
 *     it is and the scene itself takes on the app's colour (NoticeVeil, SceneEngine), with a soft shade behind the words
 *     only; [Look.SWEEP] dims the face to a fifth and lets one broad band of Xiaomi's light pass behind the words, then rests;
 *  4. it folds back into the pill (the light comes back round it) and the pill condenses and sinks into the top edge.
 *
 * Time zero is the first drop. The expanded page uses the existing notification hold.
 *
 * THE SAME FORMULA TWICE, SIDE BY SIDE ON PURPOSE (as MusicBody): [AGSL] draws the pill, its light, the page's veil and the
 * sweep on the shell's hardware canvas (IslandArt), and [pixel] is the same lines in Kotlin, which the tests run and the
 * software canvas (the PC's renders) draws with. A change to one is a change to the other.
 *
 * Page units: the 976 x 596 rear (IslandArt scales to the canvas). Times in ms from the arrival. Pure: no Android.
 */
object MessageIsland {
    const val W = 976f
    const val H = 596f
    /** The middle of what he sees across (the lenses cover the left 296 px): NoticePop.CENTRE. */
    const val CX = 636f
    /** The page's middle down, as NoticePop.MIDDLE. */
    const val MIDDLE = 298f
    /** The page's corner at the end of its growth (the display's own corners cut it there anyway). */
    const val PAGE_R = 105f

    // ---- the pill ---------------------------------------------------------------------------------------------------
    /** The small pill as it drops in, before it widens. */
    const val SMALL_W = 132f
    const val SMALL_H = 38f
    /** The pill with its words: its height, its centre, and how wide it may be (20 px inside the seen 296..976). */
    const val PILL_H = 128f
    const val PILL_CY = 84f
    const val PILL_MAX_W = 640f
    /** Never narrower than this, so a one-word message still reads as an island and not a dot. */
    const val PILL_MIN_W = 220f
    const val PILL_ICON = 40f
    const val PILL_GAP = 16f
    const val PILL_PAD = 36f
    /** Who it is from, regular, and the message, demibold, in the pill. */
    const val PILL_SENDER = 26f
    const val PILL_MESSAGE = 32f
    /** The two lines' baselines from the pill's centre (render.py: 65 and 109 in a pill centred on 84), and one line's. */
    const val PILL_SENDER_BASE = -19f
    const val PILL_MESSAGE_BASE = 25f
    const val PILL_ONE_BASE = 11f
    /** The widest a pill line may be. */
    const val PILL_TEXT_MAX = PILL_MAX_W - 2 * PILL_PAD - PILL_ICON - PILL_GAP

    // ---- the timeline (ms from the arrival; render.py's B less its first 0.45 s) -------------------------------------
    const val DROP_TO = 470f
    const val GROW_FROM = 270f
    const val GROW_TO = 830f
    /** The pill's words go in the first 80 ms of the opening; the shape grows with none. */
    const val OPEN_FROM = 1200f
    const val OPEN_TO = 2000f
    const val INK_MS = 80f
    /** In: until the page is open. */
    const val IN_MS = 2000L
    /** The page held, as the full pop-up holds (ShellNotices.HOLD_MS). */
    const val HOLD_MS = 5000L
    /** Out: the fold (800), the pill held (300), condensed (500), sunk into the top edge (360). */
    const val FOLD_MS = 800f
    const val EXIT_AFTER = 1100f
    const val CONDENSE_MS = 500f
    const val SINK_TO = 860f
    const val OUT_MS = 1960L
    const val TOTAL_MS = IN_MS + HOLD_MS + OUT_MS

    // ---- the sweep (Look.SWEEP; drafts2/12-face-sweep) ----------------------------------------------------------------
    const val SWEEP_FROM = OPEN_FROM + 130f
    const val SWEEP_IN = OPEN_FROM + 530f
    const val SWEEP_FADE = OPEN_FROM + 2350f
    const val SWEEP_TO = OPEN_FROM + 3200f

    /**
     * XIAOMI'S LIGHT: the measured apricot, lavender-violet and blue of its rear glow, clockwise from the top
     * in sRGB, 0..1.
     */
    val PALETTE: Array<FloatArray> = arrayOf("F7BE98", "E2A28C", "AE8ECE", "6E7FFE", "4B83FC", "8F8EF4", "BAA9DA", "E8C5BE", "F7BE98")
        .map { h -> FloatArray(3) { i -> h.substring(2 * i, 2 * i + 2).toInt(16) / 255f } }.toTypedArray()
    /** The first eight's mean: the colour the light mixes to deep in the glass. */
    val MEAN: FloatArray = FloatArray(3) { c -> (0 until 8).map { PALETTE[it][c] }.average().toFloat() }

    /** One moment of it. Filled in place ([at]): nothing is made per frame. */
    class Frame {
        /** Something is drawn: false before it begins and once it has sunk away. */
        var shown = false
        /** The shape (page units): centre, size, corner radius. */
        var cx = 0f; var cy = 0f; var w = 0f; var h = 0f; var r = 0f
        /** How far it has opened into the page (0 the pill .. 1 the whole back). */
        var p = 0f
        /** The pill's words' scale while it is not full size, and the pill's own centre down (before it opens). */
        var compact = 1f
        var pillCy = 0f
        /** The words' and the icon's strength (they come with the pill's widening), and each layout's own. */
        var alpha = 0f
        var pillInk = 0f
        var pageInk = 0f
        /** Xiaomi's light round the outline: how strong, and how far its colours have turned (one turn = 1). */
        var light = 0f
        var turn = 0f
        /** The page's own look coming in with the opening (0 the black pill .. 1 open), and the tint's (later). */
        var reveal = 0f
        var tint = 0f
        /** The page's edge, soft while it grows (page units; 0: the pill's own hard edge). */
        var feather = 0f
        /** The sweep's strength now and its band's centre across (page units). */
        var sweep = 0f
        var sweepX = 0f

        fun copyFrom(o: Frame) {
            shown = o.shown; cx = o.cx; cy = o.cy; w = o.w; h = o.h; r = o.r; p = o.p; compact = o.compact; pillCy = o.pillCy
            alpha = o.alpha; pillInk = o.pillInk; pageInk = o.pageInk; light = o.light; turn = o.turn; reveal = o.reveal
            tint = o.tint; feather = o.feather; sweep = o.sweep; sweepX = o.sweepX
        }

        /** The same picture as [o] (the hold: nothing on the island's layer moves). */
        fun same(o: Frame): Boolean = shown == o.shown && cx == o.cx && cy == o.cy && w == o.w && h == o.h && r == o.r && p == o.p &&
            compact == o.compact && pillCy == o.pillCy && alpha == o.alpha && pillInk == o.pillInk && pageInk == o.pageInk &&
            light == o.light && turn == o.turn && reveal == o.reveal && feather == o.feather && sweep == o.sweep && sweepX == o.sweepX
    }

    fun smooth(a: Float, b: Float, t: Float): Float {
        val u = ((t - a) / (b - a)).coerceIn(0f, 1f)
        return u * u * u * (u * (6f * u - 15f) + 10f)
    }

    private fun smoothstep(a: Float, b: Float, v: Float): Float {
        val u = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** render.py's spring: a finite step response that ends at its second crossing of 1 (one gentle overshoot). */
    fun spring(a: Float, b: Float, t: Float, z: Float = 0.84f): Float {
        val u = ((t - a) / (b - a)).coerceIn(0f, 1f)
        if (u == 0f || u == 1f) return u
        val v = sqrt(1f - z * z)
        val phase = 2f * PI.toFloat() - atan2(v, z)
        val k = phase * u
        return 1f - exp(-z / v * k) * (cos(k) + z / v * sin(k))
    }

    private fun mix(a: Float, b: Float, p: Float) = a + (b - a) * p

    /**
     * The island [t] ms after it arrived, its pill [pillW] wide (its words' width, [Layout.pillW]), into [out]. The hold is
     * [hold] ms (HOLD_MS). [sweeps]: the open page's look has the sweep (Look.sweep); without it the sweep is left at
     * nothing, so the held page is the same picture from one moment to the next ([Frame.same]) and is not drawn again.
     */
    fun at(t: Float, pillW: Float, out: Frame, hold: Float = HOLD_MS.toFloat(), sweeps: Boolean = true): Frame {
        val foldAt = IN_MS + hold
        val exit0 = foldAt + EXIT_AFTER
        if (t < 0f || t >= exit0 + SINK_TO) { out.shown = false; out.light = 0f; out.sweep = 0f; out.tint = 0f; out.reveal = 0f; return out }
        val drop = spring(0f, DROP_TO, t)
        var grow = spring(GROW_FROM, GROW_TO, t)
        var opening = spring(OPEN_FROM, OPEN_TO, t, 0.9f)
        if (t >= foldAt) opening = 1f - smooth(foldAt, foldAt + FOLD_MS, t)
        opening = opening.coerceIn(0f, 1f)
        if (t >= exit0) grow = 1f - spring(exit0, exit0 + CONDENSE_MS, t)
        grow = max(0f, grow)
        val sink = smooth(exit0 + CONDENSE_MS, exit0 + SINK_TO, t)
        val pw = pillW
        val w = mix(SMALL_W, pw, grow) * (1f - 0.75f * sink)
        val h = mix(SMALL_H, PILL_H, grow) * (1f - 0.7f * sink)
        var cy = mix(-28f, 30f, drop) + (PILL_CY - 30f) * grow
        cy = mix(cy, -26f, sink)
        val p = opening
        out.shown = true
        out.p = p
        out.cx = mix(CX, W / 2f, p); out.cy = mix(cy, H / 2f, p)
        out.w = mix(w, W, p); out.h = mix(h, H, p)
        out.r = mix(h / 2f, PAGE_R, p)
        out.compact = min(w / pw, h / PILL_H)
        out.pillCy = cy
        out.alpha = if (p == 0f) smooth(0.33f, 0.78f, grow) else 1f
        // The words: the pill's go over the first 80 ms of the opening and come back over the fold's last 80; the page's
        // only once it is open (98 to 100%), and go first as it folds.
        out.pillInk = if (t < foldAt) 1f - smooth(OPEN_FROM, OPEN_FROM + INK_MS, t) else smooth(foldAt + FOLD_MS - INK_MS, foldAt + FOLD_MS, t)
        out.pageInk = smooth(0.98f, 1f, p) * (1f - smooth(foldAt, foldAt + INK_MS, t))
        out.reveal = smooth(0.05f, 0.98f, p)
        out.tint = smooth(0.25f, 1f, p)
        out.feather = if (p > 0f) 1f + 9f * smooth(0.05f, 0.75f, p) else 0f
        // Xiaomi's light: once round as it arrives, then a faint glow while it is read; up as it grows and gone as it opens;
        // back round the folding page; gathered into the pill as it leaves, its colours unwinding a little.
        val arrival = smooth(0f, 190f, t)
        var strength = arrival * (0.14f + 0.86f * (1f - smooth(GROW_TO, OPEN_FROM, t)))
        var turn = smooth(0f, 1070f, t)
        if (t < foldAt) {
            strength += 0.62f * smooth(OPEN_FROM, OPEN_FROM + 230f, t)
            strength *= 1f - smooth(0.68f, 1f, p)
        } else {
            strength = 0.78f * smooth(foldAt, foldAt + 330f, t) * (1f - smooth(0.78f, 1f, p))
            strength = mix(strength, 0.14f, smooth(foldAt + FOLD_MS, foldAt + 1070f, t))
        }
        if (t >= exit0) {
            strength = mix(0.14f, 0.95f, smooth(exit0, exit0 + 200f, t)) * (1f - smooth(exit0 + 600f, exit0 + SINK_TO, t))
            turn -= 0.28f * smooth(exit0, exit0 + 800f, t)
        }
        out.light = strength
        out.turn = turn
        // The sweep: one broad band across, behind the words, while the page is open; then the page rests.
        if (!sweeps) { out.sweep = 0f; out.sweepX = 0f; return out }
        out.sweep = smooth(SWEEP_FROM, SWEEP_IN, t) * (1f - smooth(SWEEP_FADE, SWEEP_TO, t)) * smooth(0.55f, 0.99f, p)
        var travel = ((t - SWEEP_FROM) / (SWEEP_TO - SWEEP_FROM)).coerceIn(0f, 1f)
        travel = 0.65f * travel + 0.35f * smooth(0f, 1f, travel)
        out.sweepX = mix(260f, 1240f, travel)
        return out
    }

    /** The whole of one island's time on the back for a hold of [hold] ms. */
    fun total(hold: Long = HOLD_MS): Long = IN_MS + hold + OUT_MS

    /**
     * A NEW MESSAGE WHILE ONE IS UP, at [t] ms into it: where on its way in the next one goes on from, so the shape does not
     * jump. Open or opening: the same time (its words change in place, as the full pop-up's do). Folding or leaving: the
     * moment of the way in whose shape is nearest to this one's, from which it opens again. Allocation-free but for [scratch].
     */
    fun rejoin(t: Float, pillW: Float, hold: Float = HOLD_MS.toFloat(), scratch: Frame = Frame(), probe: Frame = Frame()): Float {
        if (t <= IN_MS + hold) return t.coerceAtLeast(0f)
        at(t, pillW, scratch, hold)
        if (!scratch.shown) return 0f
        var best = 0f; var bestD = Float.MAX_VALUE
        var s = 0f
        while (s <= IN_MS) {
            at(s, pillW, probe, hold)
            val d = abs(probe.cx - scratch.cx) + abs(probe.cy - scratch.cy) + abs(probe.w - scratch.w) + abs(probe.h - scratch.h) + 400f * abs(probe.p - scratch.p)
            if (d < bestD) { bestD = d; best = s }
            s += 4f
        }
        return best
    }

    // ==================================================================================================================
    // THE WORDS' PLACES (IslandArt measures them; these are the sums)

    /**
     * Where the words stand: in the pill, [pillW] wide, its icon at [pillIconX] and its lines at [pillTextX] (unscaled);
     * on the page as NoticePop lays its block out (head at [headCy], its icon at [pageIconX] and who at [pageTextX], the
     * message's first baseline [firstBase] and each next [step] down), and the block's box for the shade behind the words.
     */
    class Layout(
        val pillW: Float, val pillIconX: Float, val pillTextX: Float, val pillIcon: Boolean,
        val pageIconX: Float, val pageTextX: Float, val headCy: Float, val firstBase: Float, val step: Float,
        val boxCx: Float, val boxCy: Float, val boxHw: Float, val boxHh: Float,
    )

    /**
     * The places for a pill line of [senderW] and one of [messageW] (both already cut to [PILL_TEXT_MAX]), with or without
     * an [icon]; a page head of [headW] (icon, gap and who, NoticePop's), its widest message line [widest], the block's top
     * and bottom and lines from NoticePop.layout ([headCy], [firstBase], [step], [top], [bottom]).
     */
    fun layout(
        senderW: Float, messageW: Float, icon: Boolean,
        headW: Float, widest: Float, headCy: Float, firstBase: Float, step: Float, top: Float, bottom: Float,
    ): Layout {
        val iconW = if (icon) PILL_ICON + PILL_GAP else 0f
        val block = iconW + max(senderW, messageW)
        val pw = (block + 2 * PILL_PAD).coerceIn(PILL_MIN_W, PILL_MAX_W)
        val ix = CX - block / 2f
        val pageIx = CX - headW / 2f
        val wide = max(widest, headW)
        return Layout(
            pillW = pw, pillIconX = ix, pillTextX = ix + iconW, pillIcon = icon,
            pageIconX = pageIx, pageTextX = pageIx, headCy = headCy, firstBase = firstBase, step = step,
            boxCx = CX, boxCy = (top + bottom) / 2f, boxHw = wide / 2f + BOX_PAD_X, boxHh = (bottom - top) / 2f + BOX_PAD_Y,
        )
    }

    /** How far the shade behind the words reaches past them (page units), and over how much it softens away. */
    const val BOX_PAD_X = 40f
    const val BOX_PAD_Y = 30f
    const val BOX_SOFT_IN = 30f
    const val BOX_SOFT_OUT = 130f

    // ==================================================================================================================
    // THE OPEN PAGE'S LOOK ON THE ISLAND'S OWN LAYER

    /**
     * What the island's layer lays over what is under it once open: [fillTo] black (0 none .. 1 black; the draft's 20%
     * face is 0.8), [shade] a soft black behind the words only, [wash] the app's colour washed over (only where the scene
     * cannot tint itself), and whether one band of light sweeps across ([sweep]).
     */
    class Look(val fillTo: Float, val shade: Float, val wash: Float, val sweep: Boolean) {
        companion object {
            /**
             * "Island · Tint" over the live face: the face is not dimmed at all, the scene takes on the app's colour
             * (NoticeVeil). The soft shade is confined to the text block.
             */
            val TINT_OVER_FACE = Look(fillTo = 0f, shade = 0.5f, wash = 0f, sweep = false)
            /** "Island · Tint" where what is under is not a face the scene draws (another page, a Xiaomi face): a transparent colour wash over the live picture. */
            val TINT_OWN = Look(fillTo = 0f, shade = 0.5f, wash = 0.16f, sweep = false)
            /** "Island · Sweep" (draft 12): what is under dimmed to a fifth, one band of Xiaomi's light across behind the words. */
            val SWEEP = Look(fillTo = 0.8f, shade = 0f, wash = 0f, sweep = true)
        }
    }

    // ==================================================================================================================
    // THE FORMULA IN KOTLIN (the [AGSL] below is the same, line for line)

    /** The rounded rectangle's signed distance (render.py's sdf): negative inside. */
    fun sdf(x: Float, y: Float, cx: Float, cy: Float, w: Float, h: Float, r: Float): Float {
        val qx = abs(x - cx) - (w / 2f - r)
        val qy = abs(y - cy) - (h / 2f - r)
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    private fun q(a: Float): Float = a.coerceIn(0f, PI.toFloat() / 2f)

    private fun mod1(v: Float): Float { val m = v - floor(v); return if (m >= 1f) 0f else m }

    /** Where clockwise along THIS rounded rectangle's outline ([f]'s) the point is nearest, from the top's middle (0..1). */
    fun outlinePhase(px: Float, py: Float, f: Frame): Float {
        val w = f.w; val h = f.h; val r = f.r
        val x = px - (f.cx - w / 2f); val y = py - (f.cy - h / 2f)
        val lt = max(0f, w - 2f * r); val lr = max(0f, h - 2f * r); val arc = PI.toFloat() * r / 2f
        // The nearest side (the first of the least, as numpy's argmin): top, right, bottom, left.
        var side = 0; var least = y
        if (w - x < least) { side = 1; least = w - x }
        if (h - y < least) { side = 2; least = h - y }
        if (x < least) side = 3
        var s = when (side) {
            0 -> x - r
            1 -> lt + arc + y - r
            2 -> lt + 2 * arc + lr + w - r - x
            else -> 2 * lt + 3 * arc + lr + h - r - y
        }
        if (x > w - r && y < r) s = lt + r * q(atan2(x - w + r, r - y))
        if (x > w - r && y > h - r) s = lt + arc + lr + r * q(atan2(y - h + r, x - w + r))
        if (x < r && y > h - r) s = 2 * lt + 2 * arc + lr + r * q(atan2(r - x, y - h + r))
        if (x < r && y < r) s = 2 * lt + 3 * arc + 2 * lr + r * q(atan2(r - y, r - x))
        return mod1((s - lt / 2f) / (2 * lt + 2 * lr + 4 * arc))
    }

    /** Xiaomi's light at a point [d] from the outline (render.py's island_light), sRGB 0..1 into [out]. */
    fun light(x: Float, y: Float, d: Float, f: Frame, out: FloatArray) {
        val strength = f.light
        if (strength < 1e-4f) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return }
        val perimeter = outlinePhase(x, y, f)
        val phase = mod1(perimeter - f.turn) * 8f
        val i = floor(phase).toInt().coerceIn(0, 7)
        var fr = phase - i; fr = fr * fr * (3f - 2f * fr)
        val e = max(d, 0f); val depth = max(-d, 0f)
        val near = exp(-depth / 7f)
        val halo = 0.32f * exp(-(e / 13f) * (e / 13f)) + 0.34f * exp(-e / 30f) + 0.16f * exp(-e / 95f)
        val interior = 0.075f + 0.745f * exp(-depth / 7f)
        val body = if (d >= 0f) halo else interior
        val lip = 0.23f * exp(-(d / 3.5f) * (d / 3.5f))
        val hd = mod1(perimeter - f.turn + 0.5f) - 0.5f
        var head = 0.78f + 0.22f * exp(-(hd / 0.12f) * (hd / 0.12f))
        head = 1f - (1f - head) * near
        val light = strength * (body + lip) * head
        val c = light.coerceIn(0f, 1f)
        val hot = c * c * c * 0.10f
        for (k in 0..2) {
            var col = PALETTE[i][k] * (1f - fr) + PALETTE[i + 1][k] * fr
            col = col * near + MEAN[k] * (1f - near)
            col = col * (1f - hot) + hot
            out[k] = 1f - exp(-light * col * 1.15f)
        }
    }

    /** The sweep's light at a point (drafts2/12's face_sweep): sRGB 0..1 into [out], times [inside] (the page's cover). */
    fun sweep(x: Float, y: Float, f: Frame, inside: Float, out: FloatArray) {
        val g = f.sweep * inside
        if (g <= 0f) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return }
        val dd = x - f.sweepX + 0.19f * (y - MIDDLE)
        fun gs(v: Float, s: Float) = exp(-0.5f * (v / s) * (v / s))
        val blue = gs(dd + 34f, 112f); val warm = gs(dd - 105f, 89f); val bloom = gs(dd, 194f)
        val vertical = gs(y - MIDDLE, 265f)
        for (k in 0..2) {
            val energy = (0.20f * blue * PALETTE[4][k] + 0.15f * warm * PALETTE[0][k] + 0.016f * bloom * PALETTE[6][k]) * vertical * g
            out[k] = 1f - exp(-energy)
        }
    }

    /** The shade's reach at a point: 1 over the words' box, softening to 0 [BOX_SOFT_OUT] past it. */
    fun boxMask(x: Float, y: Float, l: Layout): Float {
        val qx = abs(x - l.boxCx) - l.boxHw
        val qy = abs(y - l.boxCy) - l.boxHh
        val sd = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f)
        return 1f - smoothstep(-BOX_SOFT_IN, BOX_SOFT_OUT, sd)
    }

    /** The static half-level noise against banding: the shader's own hash (sin's last bits differ a little on a GPU). */
    fun noise(x: Float, y: Float): Float { val v = sin(x * 12.9898f + y * 78.233f) * 43758.5453f; return v - floor(v) - 0.5f }

    /**
     * THE ISLAND'S LAYER AT ONE PIXEL (page units, its centre at [x], [y]; [u] canvas px per page unit): premultiplied
     * sRGB, as the compositor lays it over what is under (out = rgb + under x (1 - a)), into [out] (r, g, b, a). The pill's
     * black and its soft shadow, the open page's veil ([look]: its black, the shade behind the words, the app's colour
     * [wash]), the sweep, and the light, the light last (render.py: screen over everything). Text and icon are not in it.
     * [scratch] holds six floats.
     */
    fun pixel(x: Float, y: Float, u: Float, f: Frame, look: Look, l: Layout, wash: FloatArray, out: FloatArray, scratch: FloatArray) {
        if (!f.shown) { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f; return }
        val d = sdf(x, y, f.cx, f.cy, f.w, f.h, f.r)
        val surface = if (f.feather <= 0f) (0.5f - d * u).coerceIn(0f, 1f)
            else { val s = (0.5f - d / f.feather).coerceIn(0f, 1f); s * s * (3f - 2f * s) }
        val shadow = if (d > 0f) 0.18f * exp(-d / 9f) else 0f
        val fillA = surface * (1f - f.reveal * (1f - look.fillTo))
        val shadeA = if (look.shade > 0f) look.shade * f.reveal * surface * boxMask(x, y, l) else 0f
        val washA = look.wash * f.reveal * surface
        val keepBlack = (1f - shadow) * (1f - fillA) * (1f - shadeA)
        if (look.sweep) sweep(x, y, f, surface, scratch) else { scratch[0] = 0f; scratch[1] = 0f; scratch[2] = 0f }
        light(x, y, d, f, out)
        val sm = max(scratch[0], max(scratch[1], scratch[2]))
        val lm = max(out[0], max(out[1], out[2]))
        val keep = keepBlack * (1f - washA) * (1f - sm) * (1f - lm)
        val a = (1f - keep).coerceIn(0f, 1f)
        val n = noise(x * u, y * u) / 255f * 0.7f
        for (k in 0..2) {
            var c = out[k] + scratch[k] * (1f - lm) + wash[k] * washA * (1f - sm) * (1f - lm)
            if (c > 0.5f / 255f) c += n
            out[k] = c.coerceIn(0f, a)
        }
        out[3] = a
    }

    /** The farthest the light reaches past the outline that is still a level (its widest falloff at full strength: under 1.5 of 255 past it). */
    const val REACH = 360f

    // ==================================================================================================================
    // THE SAME ON THE GPU (AGSL, for IslandArt's RuntimeShader on the shell's hardware canvas)

    private fun f3(c: FloatArray) = "float3(%.6f, %.6f, %.6f)".format(java.util.Locale.ROOT, c[0], c[1], c[2])

    /**
     * [pixel], as AGSL. Uniforms: `u` (canvas px per page unit); `shape` (cx, cy, w, h) and `shapeR` (r, feather, p, shown);
     * `lit` (strength, turn); `veil` (reveal, fillTo, shade, wash); `washC`; `box` (cx, cy, hw, hh); `sweepS` (strength,
     * x). Returns premultiplied colour.
     */
    val AGSL: String by lazy {
        val pal = PALETTE.indices.joinToString("") { "const float3 P$it = ${f3(PALETTE[it])};\n" }
        """
uniform float u;
uniform float4 shape;
uniform float4 shapeR;
uniform float2 lit;
uniform float4 veil;
uniform float3 washC;
uniform float4 box;
uniform float2 sweepS;
$pal
const float3 MEANC = ${f3(MEAN)};
const float PI = 3.14159265;
float3 pal(float i) {
  return i < 0.5 ? P0 : i < 1.5 ? P1 : i < 2.5 ? P2 : i < 3.5 ? P3 : i < 4.5 ? P4 : i < 5.5 ? P5 : i < 6.5 ? P6 : i < 7.5 ? P7 : P8;
}
float mod1(float v) { return v - floor(v); }
float sdRound(float2 q0, float2 halfSize, float r) {
  float2 q = abs(q0) - (halfSize - float2(r));
  return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}
float outlinePhase(float2 pt) {
  float w = shape.z; float h = shape.w; float r = shapeR.x;
  float x = pt.x - (shape.x - w / 2.0); float y = pt.y - (shape.y - h / 2.0);
  float lt = max(0.0, w - 2.0 * r); float lr = max(0.0, h - 2.0 * r); float arc = PI * r / 2.0;
  float side = 0.0; float least = y;
  if (w - x < least) { side = 1.0; least = w - x; }
  if (h - y < least) { side = 2.0; least = h - y; }
  if (x < least) { side = 3.0; }
  float s = side < 0.5 ? x - r : side < 1.5 ? lt + arc + y - r : side < 2.5 ? lt + 2.0 * arc + lr + w - r - x : 2.0 * lt + 3.0 * arc + lr + h - r - y;
  if (x > w - r && y < r) { s = lt + r * clamp(atan(x - w + r, r - y), 0.0, PI / 2.0); }
  if (x > w - r && y > h - r) { s = lt + arc + lr + r * clamp(atan(y - h + r, x - w + r), 0.0, PI / 2.0); }
  if (x < r && y > h - r) { s = 2.0 * lt + 2.0 * arc + lr + r * clamp(atan(r - x, y - h + r), 0.0, PI / 2.0); }
  if (x < r && y < r) { s = 2.0 * lt + 3.0 * arc + 2.0 * lr + r * clamp(atan(r - y, r - x), 0.0, PI / 2.0); }
  return mod1((s - lt / 2.0) / (2.0 * lt + 2.0 * lr + 4.0 * arc));
}
float3 islandLight(float2 pt, float d) {
  float strength = lit.x;
  if (strength < 0.0001) { return float3(0.0); }
  float perimeter = outlinePhase(pt);
  float phase = mod1(perimeter - lit.y) * 8.0;
  float i = clamp(floor(phase), 0.0, 7.0);
  float fr = phase - i; fr = fr * fr * (3.0 - 2.0 * fr);
  float e = max(d, 0.0); float depth = max(-d, 0.0);
  float near = exp(-depth / 7.0);
  float halo = 0.32 * exp(-(e / 13.0) * (e / 13.0)) + 0.34 * exp(-e / 30.0) + 0.16 * exp(-e / 95.0);
  float interior = 0.075 + 0.745 * exp(-depth / 7.0);
  float body = d >= 0.0 ? halo : interior;
  float lip = 0.23 * exp(-(d / 3.5) * (d / 3.5));
  float hd = mod1(perimeter - lit.y + 0.5) - 0.5;
  float head = 0.78 + 0.22 * exp(-(hd / 0.12) * (hd / 0.12));
  head = 1.0 - (1.0 - head) * near;
  float light = strength * (body + lip) * head;
  float c = clamp(light, 0.0, 1.0);
  float hot = c * c * c * 0.10;
  float3 col = mix(pal(i), pal(i + 1.0), fr);
  col = col * near + MEANC * (1.0 - near);
  col = col * (1.0 - hot) + float3(hot);
  return float3(1.0) - exp(-light * col * 1.15);
}
float gs(float v, float s) { return exp(-0.5 * (v / s) * (v / s)); }
float3 sweepLight(float2 pt, float inside) {
  float g = sweepS.x * inside;
  if (g <= 0.0) { return float3(0.0); }
  float dd = pt.x - sweepS.y + 0.19 * (pt.y - $MIDDLE);
  float blue = gs(dd + 34.0, 112.0); float warm = gs(dd - 105.0, 89.0); float bloom = gs(dd, 194.0);
  float vertical = gs(pt.y - $MIDDLE, 265.0);
  float3 energy = (0.20 * blue * P4 + 0.15 * warm * P0 + 0.016 * bloom * P6) * vertical * g;
  return float3(1.0) - exp(-energy);
}
float boxMask(float2 pt) {
  float2 q = abs(pt - box.xy) - box.zw;
  float sd = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0);
  return 1.0 - smoothstep(-$BOX_SOFT_IN, $BOX_SOFT_OUT, sd);
}
half4 main(float2 fc) {
  if (shapeR.w < 0.5) { return half4(0.0); }
  float2 pt = fc / u;
  float d = sdRound(pt - shape.xy, shape.zw / 2.0, shapeR.x);
  float surface;
  if (shapeR.y <= 0.0) { surface = clamp(0.5 - d * u, 0.0, 1.0); }
  else { float s = clamp(0.5 - d / shapeR.y, 0.0, 1.0); surface = s * s * (3.0 - 2.0 * s); }
  float shadow = d > 0.0 ? 0.18 * exp(-d / 9.0) : 0.0;
  float fillA = surface * (1.0 - veil.x * (1.0 - veil.y));
  float shadeA = veil.z > 0.0 ? veil.z * veil.x * surface * boxMask(pt) : 0.0;
  float washA = veil.w * veil.x * surface;
  float keepBlack = (1.0 - shadow) * (1.0 - fillA) * (1.0 - shadeA);
  float3 S = sweepLight(pt, surface);
  float3 L = islandLight(pt, d);
  float sm = max(S.r, max(S.g, S.b));
  float lm = max(L.r, max(L.g, L.b));
  float keep = keepBlack * (1.0 - washA) * (1.0 - sm) * (1.0 - lm);
  float a = clamp(1.0 - keep, 0.0, 1.0);
  float v = sin(fc.x * 12.9898 + fc.y * 78.233) * 43758.5453;
  float n = (v - floor(v) - 0.5) / 255.0 * 0.7;
  float3 c = L + S * (1.0 - lm) + washC * washA * (1.0 - sm) * (1.0 - lm);
  c = c + float3(c.r > 0.5 / 255.0 ? n : 0.0, c.g > 0.5 / 255.0 ? n : 0.0, c.b > 0.5 / 255.0 ? n : 0.0);
  return half4(half3(clamp(c, float3(0.0), float3(a))), half(a));
}
"""
    }

    /** The uniforms of [AGSL] for one frame, as (name, values): what IslandArt sets, and what the tests bake into it. */
    fun uniforms(f: Frame, look: Look, l: Layout, wash: FloatArray, u: Float, out: Array<FloatArray>) {
        out[0][0] = u
        out[1][0] = f.cx; out[1][1] = f.cy; out[1][2] = f.w; out[1][3] = f.h
        out[2][0] = f.r; out[2][1] = f.feather; out[2][2] = f.p; out[2][3] = if (f.shown) 1f else 0f
        out[3][0] = f.light; out[3][1] = f.turn
        out[4][0] = f.reveal; out[4][1] = look.fillTo; out[4][2] = look.shade; out[4][3] = look.wash
        out[5][0] = wash[0]; out[5][1] = wash[1]; out[5][2] = wash[2]
        out[6][0] = l.boxCx; out[6][1] = l.boxCy; out[6][2] = l.boxHw; out[6][3] = l.boxHh
        out[7][0] = if (look.sweep) f.sweep else 0f; out[7][1] = f.sweepX
    }

    val UNIFORM_NAMES = arrayOf("u", "shape", "shapeR", "lit", "veil", "washC", "box", "sweepS")
    fun uniformArrays(): Array<FloatArray> = arrayOf(FloatArray(1), FloatArray(4), FloatArray(4), FloatArray(2), FloatArray(4), FloatArray(3), FloatArray(4), FloatArray(2))

    /**
     * Where what [pixel] draws can be anything but clear, in page units (left, top, right, bottom) into [out]: the shape and
     * the light's reach round it, the whole back once it has grown past the pill. The rest of the layer is left clear.
     */
    fun bounds(f: Frame, out: FloatArray) {
        if (!f.shown) { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f; return }
        if (f.p > 0f) { out[0] = 0f; out[1] = 0f; out[2] = W; out[3] = H; return }
        val reach = if (f.light > 1e-4f) REACH else 40f
        out[0] = max(0f, f.cx - f.w / 2f - reach); out[1] = 0f
        out[2] = min(W, f.cx + f.w / 2f + reach); out[3] = min(H, f.cy + f.h / 2f + reach)
    }
}
