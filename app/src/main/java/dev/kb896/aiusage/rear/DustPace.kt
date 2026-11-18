package dev.kb896.aiusage.rear

import java.util.Locale
import kotlin.math.ceil

class DustPace(
    /** The rear's refresh at its highest rate: the grid the pictures go on. */
    private val rearPeriodNs: Long = FramePace.periodNsOf(FramePace.REAR_HZ),
) {
    /** This picture's time on the grid (System.nanoTime's base): its presentation time. 0: none yet (or after a hold). */
    var dueNs = 0L
        private set
    private var lastDueNs = 0L

    /**
     * A picture is drawn now ([nowNs]): the simulation's step for it, the real time between its presentation time and
     * the last one's ([FIRST_DT] for the first, or the first after a hold), at most [MAX_DT].
     */
    fun picture(nowNs: Long): Float {
        if (dueNs == 0L) dueNs = nowNs
        val dt = if (lastDueNs == 0L) FIRST_DT else ((dueNs - lastDueNs) / 1e9f).coerceIn(0f, MAX_DT)
        lastDueNs = dueNs
        return dt
    }

    /**
     * At [nowNs], after a picture: the next one's time, one refresh of the rear on from this one's when [quick]
     * (DustDriver.quick), else two, along the rear's grid (no drift: the time asked for, not the time it ran), or now
     * when the loop has fallen more than a refresh behind (FramePace.clockNextNs).
     */
    fun next(nowNs: Long, quick: Boolean): Long {
        dueNs = FramePace.clockNextNs(dueNs, nowNs, if (quick) QUICK_MS else FRAME_MS, rearPeriodNs)
        return dueNs
    }

    /** Held (nothing stepped meanwhile): the next picture starts afresh, with no step for the time held. */
    fun hold() { dueNs = 0L; lastDueNs = 0L }

    /**
     * THE TEN-SECOND LINE ([DUST_LOG_EVERY] pictures): counted per picture with nothing allocated, said once. What a
     * phone measurement reads: the rate the pictures came at and the panel's, what reacted (a finger, the phone's
     * movement fast, at 120, or slow, at 60), the CPU's time per picture on the scene's thread (all of it, and of it the
     * inputs, the calls of the motes' step and of the drawing, and the swap), the thread's own share of a core over the
     * ten seconds, the longest gap, and the rates the sensors' samples came at.
     */
    class Count {
        var pictures = 0; private set
        /** Of them: a finger's (at 120), the movement's fast ones (at 120), and its slow ones (at 60, in two steps). */
        var finger = 0; private set
        var quick = 0; private set
        var slow = 0; private set
        /** Gaps between two pictures in a row (not across a hold) and their sum. */
        var gaps = 0; private set
        var spanNs = 0L; private set
        var gapMaxNs = 0L; private set
        var cpuNs = 0L; private set
        var swapNs = 0L; private set
        /** Of [cpuNs]: the inputs (the sensors' channel read, DustDriver.next), the motes' step's calls, the drawing's. */
        var inputsNs = 0L; private set
        var stepNs = 0L; private set
        var drawNs = 0L; private set

        val reacting: Int get() = finger + quick + slow

        /**
         * One picture, as DustDriver left it ([touched], [quick], [active]): [gapNs] since the last one (0: the first,
         * or after a hold), its calls [cpuNs], of them [swapNs], [inputsNs] and [stepNs] (the drawing is the rest).
         * True when a line is due.
         */
        fun picture(touched: Boolean, quick: Boolean, active: Boolean, gapNs: Long, cpuNs: Long, swapNs: Long, inputsNs: Long, stepNs: Long): Boolean {
            pictures++
            when {
                touched -> finger++
                quick -> this.quick++
                active -> slow++
            }
            if (gapNs > 0L) { gaps++; spanNs += gapNs; if (gapNs > gapMaxNs) gapMaxNs = gapNs }
            this.cpuNs += cpuNs; this.swapNs += swapNs
            this.inputsNs += inputsNs; this.stepNs += stepNs; drawNs += cpuNs - swapNs - inputsNs - stepNs
            return pictures >= DUST_LOG_EVERY
        }

        fun reset() {
            pictures = 0; finger = 0; quick = 0; slow = 0; gaps = 0; spanNs = 0L; gapMaxNs = 0L; cpuNs = 0L; swapNs = 0L
            inputsNs = 0L; stepNs = 0L; drawNs = 0L
        }
    }

    companion object {
        /** Every other refresh of the rear: at rest and while the dust reacts slowly. */
        const val HZ = 60f
        const val FRAME_MS = 1000f / HZ
        /** Every refresh of the rear: a finger, the phone's movement fast (DustDriver.quick). */
        const val QUICK_HZ = 120f
        const val QUICK_MS = 1000f / QUICK_HZ
        /** The first picture's step (and the first after a hold). */
        const val FIRST_DT = 1f / HZ
        /** The longest step one picture takes: a stall slows the dust for a picture rather than throwing it. */
        const val MAX_DT = 1f / 30f
        /** The longest single step of the simulation while something reacts (as at 120 pictures a second), and at rest. */
        const val REACT_STEP_S = 1f / QUICK_HZ
        const val REST_STEP_S = 1f / HZ
        /** The pictures counted for one log line (ten seconds at 60, five at 120). */
        const val DUST_LOG_EVERY = 600

        /**
         * In how many equal steps a picture's [dt] is taken: steps of at most [REACT_STEP_S] while something reacts
         * ([reacting]: DustDriver.active, a finger or the phone's movement), of [REST_STEP_S] at rest; one picture's
         * steps are at most DustField.MAX_STEPS. A step a hair longer than its limit (the grid's rounding) is still one:
         * a picture drawn at 120 is one step, as before.
         */
        fun steps(dt: Float, reacting: Boolean): Int {
            val most = if (reacting) REACT_STEP_S else REST_STEP_S
            return ceil(dt / most - 0.05f).toInt().coerceIn(1, DustField.MAX_STEPS)
        }

        /**
         * The log line for [c], the scene thread's own CPU time [threadNs] over [wallNs] (each -1 when not known), the
         * rate the panel is at ([panelHz], 0: not known) and how the sensors' samples came ([sensors], DustSensor.said;
         * empty: not said).
         * "dust: 600 pictures in 8.2 s (73 a second, the panel at 120 Hz), 316 reacting (a finger 150, the phone fast
         * 70 at 120, slow 96 at 60), 950 µs each on the CPU (the inputs 40, the motes' step 60, the drawing 150, the
         * swap 700; the thread 7.8% of a core), longest gap 16 ms; the accelerometer 50 a second, the gyroscope 50,
         * through the direct channel".
         */
        fun line(c: Count, threadNs: Long, wallNs: Long, panelHz: Float, sensors: String = ""): String {
            val span = c.spanNs / 1e9
            val rate = if (c.spanNs > 0L) c.gaps / span else 0.0
            val panel = if (panelHz > 0f) "the panel at ${Math.round(panelHz)} Hz" else "the panel's rate not known"
            val n = c.pictures.coerceAtLeast(1)
            val thread = if (threadNs >= 0L && wallNs > 0L) String.format(Locale.ROOT, "; the thread %.1f%% of a core", 100.0 * threadNs / wallNs) else ""
            return String.format(
                Locale.ROOT,
                "dust: %d pictures in %.1f s (%.0f a second, %s), %d reacting (a finger %d, the phone fast %d at 120, slow %d at 60), %d µs each on the CPU (the inputs %d, the motes' step %d, the drawing %d, the swap %d%s), longest gap %d ms%s",
                c.pictures, span, rate, panel, c.reacting, c.finger, c.quick, c.slow, c.cpuNs / n / 1000,
                c.inputsNs / n / 1000, c.stepNs / n / 1000, c.drawNs / n / 1000, c.swapNs / n / 1000, thread, c.gapMaxNs / 1_000_000,
                if (sensors.isEmpty()) "" else "; $sensors",
            )
        }
    }
}
