package dev.kb896.aiusage.rear

import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * THE DUST'S TWO SENSORS THROUGH A DIRECT CHANNEL (DustSensor): the sensor hardware writes its samples straight into a
 * shared memory of ours, and the scene's thread reads what is new as it makes a picture's inputs, instead of every
 * sample being carried through system_server and a socket to a thread of ours that wakes for it (three processes woken
 * for each, 100 a second). The same samples go to the same DustMotion.accel and gyro, in time order: the maths is not
 * touched.
 *
 * THE MEMORY (Android's SensorDirectChannel, the HAL's sensors_event_t): a ring of [slots] reports of [SIZE] bytes, in
 * the phone's byte order: the size (always 104), the token configure() gave the sensor, its sensor type, a counter, the
 * time (ns, the sensors' clock, as SensorEvent.timestamp) and the values (as SensorEvent.values). The hardware writes
 * the reports one after another from the first slot, round again after the last, and each report's counter LAST: 1 for
 * the first, one more for each report of the channel (both sensors count together), 1 again after 0xFFFFFFFF. So a slot
 * holds a whole new report when its counter is the one expected next; the counter is read first and the report only
 * after it ([VarHandle.acquireFence]: the processor may not take the report's bytes before the counter's).
 *
 * WHAT IS NOT AS EXPECTED IS NEVER GIVEN ON: a slot without the counter expected is not read (nothing new yet; on a
 * hardware that counted another way nothing would ever come, and [Watch] gives the channel up); a report of another
 * token or type is passed over; a report whose size is not [SIZE] means another layout ([broken]: given up). A ring
 * gone round past the reader is never read in it: the reading starts again at the newest report ([skipToNewest]).
 * [Session] reads every picture and starts again at the newest after any longer gap ([STALL_NS]); a whole ring is over
 * two seconds of both sensors at the fastest this rate may run, so the hardware never writes a slot as it is read.
 *
 * Pure: no Android. The memory is read through [Memory].
 */
class DirectReports(
    val slots: Int = SLOTS,
    /** The first report's counter (1; another only for the tests of its wrapping round). */
    first: Int = 1,
) {
    fun interface Memory {
        /** [count] bytes of the shared memory from [from], into [dst] from its start. */
        fun read(dst: ByteArray, from: Int, count: Int)
    }

    /** What the last [read] found: the first [count] of these, in time order ([ACCEL] or [GYRO], ns, the three values). */
    val kind = IntArray(slots)
    val time = LongArray(slots)
    val x = FloatArray(slots)
    val y = FloatArray(slots)
    val z = FloatArray(slots)
    var count = 0
        private set

    /** Why this memory is not laid out as expected (the channel is to be given up); null while it is. */
    var broken: String? = null
        private set
    /** Reports passed over: another token or type, or skipped at [skipToNewest]. */
    var passedOver = 0L
        private set
    /** Each [skipToNewest] that moved the reading on: one more. */
    var restarts = 0
        private set

    /** The slot to read next, and the counter it must hold. */
    private var pos = 0
    private var expected = first
    /** Slots read since the first or the last skip, at most a ring: how far [back] may go. */
    private var readSince = 0
    private val word = ByteArray(4)
    private val wordView = ByteBuffer.wrap(word).order(ORDER)
    private val one = ByteArray(SIZE)
    private val oneView = ByteBuffer.wrap(one).order(ORDER)

    /** What is new since the last read, into [kind] … [z] (nothing allocated). Says how many. */
    fun read(mem: Memory, accelToken: Int, gyroToken: Int): Int {
        count = 0
        while (count < slots && broken == null) {
            val at = pos * SIZE
            mem.read(word, at + COUNTER, 4)
            val c = wordView.getInt(0)
            if (c != expected) {
                // Not written yet (the last ring's counter, or none), or written over by a later ring: start again there.
                if (c != 0 && c - expected > 0) skipToNewest(mem)
                break
            }
            // The counter before the report it stands for.
            VarHandle.acquireFence()
            mem.read(one, at, SIZE)
            if (oneView.getInt(COUNTER) != expected) { skipToNewest(mem); break }
            val size = oneView.getInt(0)
            if (size != SIZE) { broken = "a report of $size bytes, not $SIZE"; break }
            val token = oneView.getInt(TOKEN)
            val type = oneView.getInt(TYPE)
            val k = when {
                token == accelToken && type == ACCEL_TYPE -> ACCEL
                token == gyroToken && type == GYRO_TYPE -> GYRO
                else -> -1
            }
            if (k >= 0) {
                kind[count] = k
                time[count] = oneView.getLong(TIME)
                x[count] = oneView.getFloat(DATA)
                y[count] = oneView.getFloat(DATA + 4)
                z[count] = oneView.getFloat(DATA + 8)
                count++
            } else passedOver++
            if (++pos == slots) pos = 0
            expected = next(expected)
            if (readSince < slots) readSince++
        }
        sortByTime()
        return count
    }

    /**
     * Read on from the newest report there is, past everything before it (a gap of [STALL_NS] or more since the last
     * read, or a ring gone round past the reader). Only the counters are looked at, one at a time (nothing allocated);
     * nothing is given on.
     */
    fun skipToNewest(mem: Memory) {
        var best = -1
        var bestAhead = 0
        var bestCounter = 0
        for (s in 0 until slots) {
            mem.read(word, s * SIZE + COUNTER, 4)
            val c = wordView.getInt(0)
            if (c == 0) continue
            val ahead = c - expected
            if (best < 0 || ahead > bestAhead) { best = s; bestAhead = ahead; bestCounter = c }
        }
        // Nothing newer than what was read: the reading stays where it is.
        if (best < 0 || bestAhead < 0) return
        passedOver += bestAhead.toLong() + 1
        restarts++
        pos = if (best + 1 == slots) 0 else best + 1
        expected = next(bestCounter)
        readSince = 0
    }

    /**
     * The next [read] goes over the last [reports] read again (at most those since the first or the last skip): at a
     * hand-over, what the channel read on trial and the listener had not given yet (DirectReports.Feed turns down the
     * rest). The hardware is never more than a few reports past the reader, so half a ring back is never written over.
     */
    fun back(reports: Int) {
        val k = min(reports, readSince)
        pos = ((pos - k) % slots + slots) % slots
        repeat(k) { expected = previous(expected) }
        readSince -= k
    }

    /** Stable, by time: a picture's few reports are nearly in order already. */
    private fun sortByTime() {
        for (i in 1 until count) {
            val t = time[i]
            if (t >= time[i - 1]) continue
            val k = kind[i]; val a = x[i]; val b = y[i]; val c = z[i]
            var j = i - 1
            while (j >= 0 && time[j] > t) {
                time[j + 1] = time[j]; kind[j + 1] = kind[j]; x[j + 1] = x[j]; y[j + 1] = y[j]; z[j + 1] = z[j]
                j--
            }
            time[j + 1] = t; kind[j + 1] = k; x[j + 1] = a; y[j + 1] = b; z[j + 1] = c
        }
    }

    /**
     * WHETHER THE CHANNEL KEEPS ITS PACE, from [startNs] (the clock the reads are made by: SystemClock.elapsedRealtimeNanos).
     * ON TRIAL first, beside the listener that still feeds the dust: [PASS] while each sensor has given a second of
     * samples at [MIN_HZ] or more (by their own times), [FAIL] when one gives less, or neither has by [TRIAL_NS]. Then,
     * feeding the dust ([feed], told only of the samples DustMotion took): [FAIL] the moment a sensor is silent
     * [SILENT_NS] or a second of its samples comes under [MIN_HZ] or over the rate it took over at by more than
     * [SAME_RATE]. Under it DustMotion's tilt would move in spurts (DustMotion.RATE_US says why 50); over it the samples
     * are noisier past DustMotion's dead zones. ALWAYS: the reports' times must be on the clock the reads are made by
     * (as SensorEvent.timestamp is): none over [AHEAD_NS] ahead of it, and each read's newest of a sensor not over
     * [BEHIND_NS] behind it; on another clock DustMotion would be fed nothing (Feed's floor) or out of step.
     */
    class Watch(startNs: Long) {
        /** Feeding the dust ([feed]): its rates are held to those it took over at. */
        var feeding = false
            private set
        var why = ""
            private set
        /**
         * The last FAIL is of the channel's own make (another clock, nothing at all on trial, silent while feeding): it is
         * not tried again in this process. Otherwise a passing slow or fast second.
         */
        var structural = false
            private set
        /** The trial runs from here: its start, or the last restart while on trial. */
        var trialFrom = startNs
            private set
        /** The rate each gave over its last whole second (a second a sample at least), for the log. */
        val hz = FloatArray(2)
        private val accepted = FloatArray(2)
        private val first = LongArray(2)
        private val last = LongArray(2)
        private val n = IntArray(2)
        private val heardAt = longArrayOf(startNs, startNs)
        private val proven = BooleanArray(2)
        /** Any report at all since the start. */
        private var heard = false
        /** The newest time of each sensor among the reports since the last [check], and the most one was ahead of the clock. */
        private val newest = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
        private var ahead = Long.MIN_VALUE

        /** A report of [k] at [t] (its own time), read at [nowNs]. */
        fun report(k: Int, t: Long, nowNs: Long) {
            heardAt[k] = nowNs
            heard = true
            if (t - nowNs > ahead) ahead = t - nowNs
            if (t > newest[k]) newest[k] = t
            if (n[k] == 0) { first[k] = t; last[k] = t; n[k] = 1; return }
            if (t > last[k]) last[k] = t
            n[k]++
        }

        /** After reports were skipped (DirectReports.skipToNewest): what was counted is dropped, from [nowNs] afresh. */
        fun restart(nowNs: Long) {
            n.fill(0)
            heardAt.fill(nowNs)
            if (!feeding) trialFrom = nowNs
        }

        /** It feeds the dust from now on, at these rates (its last second's on trial). */
        fun feed(rates: FloatArray) {
            feeding = true
            rates.copyInto(accepted)
        }

        /** At [nowNs], after the reports read then: [PASS] (on trial, while it is proven), [FAIL] (then [why]) or [WAIT]. */
        fun check(nowNs: Long): Int {
            if (ahead > AHEAD_NS) return fail(true, String.format(Locale.ROOT, "its times %d ms ahead of the clock (not elapsedRealtime)", ahead / 1_000_000))
            ahead = Long.MIN_VALUE
            for (k in 0..1) {
                val t = newest[k]
                newest[k] = Long.MIN_VALUE
                if (t != Long.MIN_VALUE && nowNs - t > BEHIND_NS) {
                    return fail(true, String.format(Locale.ROOT, "the %s's newest time %d ms behind the clock (not elapsedRealtime)", NAMES[k], (nowNs - t) / 1_000_000))
                }
            }
            for (k in 0..1) {
                val span = last[k] - first[k]
                if (n[k] < 2 || span < WINDOW_NS) continue
                val rate = ((n[k] - 1) * 1e9 / span).toFloat()
                hz[k] = rate
                if (rate < MIN_HZ) return fail(false, String.format(Locale.ROOT, "the %s at %.1f a second", NAMES[k], rate))
                if (feeding && rate > accepted[k] * (1f + SAME_RATE)) {
                    return fail(false, String.format(Locale.ROOT, "the %s at %.1f a second, over the %.1f it took over at", NAMES[k], rate, accepted[k]))
                }
                proven[k] = true
                first[k] = last[k]; n[k] = 1
            }
            if (!feeding) {
                if (proven[0] && proven[1]) return PASS
                if (nowNs - trialFrom > TRIAL_NS) {
                    return if (heard) fail(false, "not a second of both sensors within ${TRIAL_NS / 1_000_000} ms")
                    else fail(true, "no report at all within ${TRIAL_NS / 1_000_000} ms")
                }
                return WAIT
            }
            for (k in 0..1) {
                if (nowNs - heardAt[k] > SILENT_NS) return fail(true, "the ${NAMES[k]} silent for ${(nowNs - heardAt[k]) / 1_000_000} ms")
            }
            return WAIT
        }

        private fun fail(structural: Boolean, why: String): Int {
            this.structural = structural
            this.why = why
            return FAIL
        }
    }

    /**
     * ONE SOURCE'S SAMPLES OF ONE SENSOR OVER [from, to) (ns, the samples' own times): the time mean of each of its three
     * values, the samples joined by straight lines, so two sources sampling the same movement at other moments give
     * the same mean (a plain mean of the samples would differ by a sample at either end of a brisk move).
     */
    class Mean {
        var from = 0L
            private set
        var to = 0L
            private set
        /** How much of [from, to) lies between two of its samples (ns). */
        var covered = 0L
            private set
        /** A sample at or past [to] has come: nothing more is added. */
        val done: Boolean get() = lastT != Long.MIN_VALUE && lastT >= to
        private var lastT = Long.MIN_VALUE
        private val lastV = FloatArray(3)
        private val sum = DoubleArray(3)

        /** Over [from, to) afresh, from no sample. */
        fun window(from: Long, to: Long) {
            this.from = from; this.to = to
            covered = 0L; lastT = Long.MIN_VALUE; sum.fill(0.0)
        }

        /** A sample at [t]; one not after the last is let be. */
        fun add(t: Long, x: Float, y: Float, z: Float) {
            val t0 = lastT
            if (t0 != Long.MIN_VALUE && t <= t0) return
            if (t0 != Long.MIN_VALUE && t > from && t0 < to) {
                val a = max(t0, from)
                val b = min(t, to)
                val span = (t - t0).toDouble()
                // The straight line from the last sample to this one, its area over [a, b].
                val mid = ((a - t0) + (b - t0)) * 0.5 / span
                sum[0] += (lastV[0] + (x - lastV[0]) * mid) * (b - a)
                sum[1] += (lastV[1] + (y - lastV[1]) * mid) * (b - a)
                sum[2] += (lastV[2] + (z - lastV[2]) * mid) * (b - a)
                covered += b - a
            }
            lastT = t
            lastV[0] = x; lastV[1] = y; lastV[2] = z
        }

        /** The mean of value [i] (0 x, 1 y, 2 z) over what is covered. */
        fun mean(i: Int): Float = if (covered > 0L) (sum[i] / covered).toFloat() else Float.NaN
    }

    /**
     * ONE DIRECT CHANNEL, FROM ITS TRIAL TO ITS END (DustSensor.poll calls [poll] on the scene's thread before each
     * picture's inputs, under its locks; DustSensor.give calls [listened] with each sample the listener gave DustMotion).
     * Pure: the memory through [Memory], the clock as given (SystemClock.elapsedRealtimeNanos), DustMotion and who feeds
     * it behind [Host].
     *
     * ON TRIAL beside the listener, which feeds the dust meanwhile. It passes ([PASS]) only when, besides its [Watch],
     * each sensor's rate is the listener's and the [NOMINAL_HZ] the dust is made for, each within [SAME_RATE] ([sameRate]),
     * and over the same second (from [LEAD_NS] after its start) each of its values has the listener's mean within
     * [SAME_ACCEL] or [SAME_GYRO] ([Mean]: the same axes, calibration and units). Then [Host.takeOver], and what it read
     * on trial past the listener's last sample is given ([DirectReports.back]). Feeding, only the samples DustMotion took are watched
     * ([Host.give]): a channel whose samples are all turned down (Feed's floor) is silent to it. After reports were
     * skipped while it fed, [Host.resync]. A failure ([FAIL], [why], [structural]) while it fed: [Host.handBack].
     */
    class Session(
        private val mem: Memory,
        private val accelToken: Int,
        private val gyroToken: Int,
        startNs: Long,
        val reports: DirectReports = DirectReports(),
    ) {
        /** DustSensor's side, under its lock. */
        interface Host {
            /** A sample of the channel: whether DustMotion took it (DirectReports.Feed.take). */
            fun give(k: Int, x: Float, y: Float, z: Float, t: Long): Boolean
            /** The trial passed: the channel feeds DustMotion from now on (Feed.handTo the channel). */
            fun takeOver()
            /** It failed while it fed: the listener again (Feed.handTo the listener). */
            fun handBack()
            /** Reports were skipped while it fed: DustMotion's gravity is taken afresh from its next sample (DustMotion.resync). */
            fun resync()
        }

        /** [TRIAL], [FEEDING] or [OVER] (failed). Read on the sensors' thread too ([letGo], [listenAgain]). */
        @Volatile var state = TRIAL
            private set
        val watch = Watch(startNs)
        /** Why it failed, and whether that was of its own make (Watch.structural): null while it has not. */
        var why: String? = null
            private set
        var structural = false
            private set
        private var lastReadNs = startNs
        private var restarts = 0
        /** The listener's samples on trial: their rates, and both sources' means over the same second ([LISTENER], [DIRECT]). */
        private val heard = Rates()
        private val heardHz = FloatArray(2)
        private val means = Array(2) { Array(2) { Mean() } }

        init { window(startNs) }

        /** What is new, at [nowNs]: [PASS] (once, as it takes over), [FAIL] (once, then [why]) or [WAIT]. Nothing allocated but a failure's words. */
        fun poll(nowNs: Long, host: Host): Int {
            if (state == OVER) return WAIT
            try {
                val r = reports
                // A long gap since the last read (the scene's thread stalled): on from the newest report, before this read.
                if (nowNs - lastReadNs > STALL_NS) r.skipToNewest(mem)
                lastReadNs = nowNs
                if (r.restarts != restarts) restarted(nowNs, host)
                val n = r.read(mem, accelToken, gyroToken)
                r.broken?.let { return fail(it, true, host) }
                if (state == FEEDING) {
                    for (i in 0 until n) {
                        if (host.give(r.kind[i], r.x[i], r.y[i], r.z[i], r.time[i])) watch.report(r.kind[i], r.time[i], nowNs)
                    }
                    // The ring gone round past the reader in this read: what it read is given, what it passed by is lost.
                    if (r.restarts != restarts) restarted(nowNs, host)
                    return if (watch.check(nowNs) == FAIL) fail(watch.why, watch.structural, host) else WAIT
                }
                for (i in 0 until n) {
                    watch.report(r.kind[i], r.time[i], nowNs)
                    means[DIRECT][r.kind[i]].add(r.time[i], r.x[i], r.y[i], r.z[i])
                }
                if (r.restarts != restarts) restarted(nowNs, host)
                when (watch.check(nowNs)) {
                    FAIL -> return fail(watch.why, watch.structural, host)
                    WAIT -> return WAIT
                }
                // Both sensors at the pace for a second: and over the same second as the listener, the same?
                if (!overTheSameSecond()) {
                    return if (nowNs - watch.trialFrom > TRIAL_NS) fail("not over the same second as the listener within ${TRIAL_NS / 1_000_000} ms", false, host) else WAIT
                }
                unlike()?.let { return fail(it, false, host) }
                state = FEEDING
                watch.feed(watch.hz)
                host.takeOver()
                // What it read on trial and the listener had not given yet: given now (the rest is under Feed's floor).
                r.back(BACK)
                val m = r.read(mem, accelToken, gyroToken)
                r.broken?.let { return fail(it, true, host) }
                for (i in 0 until m) host.give(r.kind[i], r.x[i], r.y[i], r.z[i], r.time[i])
                return PASS
            } catch (e: Exception) {
                return fail(e.toString(), true, host)
            }
        }

        /** A sample the listener gave DustMotion (DustSensor.give, under its lock): on trial, its rate and its mean. */
        fun listened(k: Int, x: Float, y: Float, z: Float, t: Long) {
            if (state != TRIAL) return
            heard.sample(k, t)
            means[LISTENER][k].add(t, x, y, z)
        }

        /** On the sensors' thread, after this passed: let the listener go (it is on, and this still feeds). */
        fun letGo(listenerOn: Boolean): Boolean = state == FEEDING && listenerOn

        /** On the sensors' thread, after this failed: the listener on again (it had been let go, the sensors still on). */
        fun listenAgain(listenerOn: Boolean, sensorsOn: Boolean): Boolean = state == OVER && sensorsOn && !listenerOn

        private fun restarted(nowNs: Long, host: Host) {
            restarts = reports.restarts
            watch.restart(nowNs)
            // Feeding: DustMotion missed those samples, its gravity is taken afresh. On trial: another second, from now.
            if (state == FEEDING) host.resync() else window(nowNs)
        }

        private fun window(fromNs: Long) {
            val a = fromNs + LEAD_NS
            for (src in means) for (m in src) m.window(a, a + WINDOW_NS)
        }

        private fun overTheSameSecond(): Boolean {
            for (src in means) for (m in src) if (!m.done || m.covered < WINDOW_NS * 9 / 10) return false
            return true
        }

        /** Why its samples are not the listener's (rates, then means), or null. */
        private fun unlike(): String? {
            heardHz[ACCEL] = heard.hz(ACCEL); heardHz[GYRO] = heard.hz(GYRO)
            sameRate(watch.hz, heardHz)?.let { return it }
            for (k in 0..1) for (i in 0..2) {
                val d = abs(means[DIRECT][k].mean(i) - means[LISTENER][k].mean(i))
                if (!(d <= (if (k == ACCEL) SAME_ACCEL else SAME_GYRO))) {
                    return String.format(Locale.ROOT, "the %s's %s through it %.3f %s from the listener's over the same second", NAMES[k], AXES[i], d, UNITS[k])
                }
            }
            return null
        }

        private fun fail(why: String, structural: Boolean, host: Host): Int {
            val fed = state == FEEDING
            this.why = why
            this.structural = structural
            state = OVER
            if (fed) host.handBack()
            return FAIL
        }

        companion object {
            const val TRIAL = 0
            const val FEEDING = 1
            const val OVER = 2
            /**
             * How far back the hand-over reads again: half a ring, over a second of both sensors at the fastest this rate
             * may run (the most one read can hold before the reading starts again at the newest), and the listener's delay.
             */
            const val BACK = SLOTS / 2
        }
    }

    /**
     * WHETHER THE CHANNEL IS TRIED AGAIN AT THE NEXT START (DustSensor, either thread): a failure of its own make
     * (Watch.structural, no channel to be had) never in this process; a passing one (a slow or fast second, another
     * pace or other values than the listener's) up to [TRIES] in a row. A channel that fed the dust to the end clears
     * the count.
     */
    class Tries {
        @Volatile var off = false
            private set
        private var failed = 0

        /** A failure: whether it is given up for good now. */
        @Synchronized fun failed(structural: Boolean): Boolean {
            if (structural || ++failed >= TRIES) off = true
            return off
        }

        @Synchronized fun fedToTheEnd() { failed = 0 }

        /** How many passing failures in a row so far. */
        val count: Int @Synchronized get() = failed
    }

    /**
     * WHO FEEDS DustMotion (DustSensor, under its lock): the listener or the direct channel, never both; at a hand-over
     * no sample is given twice or older than the last the other gave. Freshly started, every sample of the listener
     * goes on, as it always did.
     */
    class Feed {
        var from = LISTENER
            private set
        private val floor = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
        private val last = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)

        /** A sample of [k] at [t] from [src]: whether it goes to DustMotion. */
        fun take(src: Int, k: Int, t: Long): Boolean {
            if (src != from || t <= floor[k]) return false
            if (t > last[k]) last[k] = t
            return true
        }

        /** From now on [src] feeds, from past the last sample the other gave. */
        fun handTo(src: Int) {
            if (src == from) return
            from = src
            last.copyInto(floor)
        }

        /** The sensors on again: the listener, every sample. */
        fun fresh() {
            from = LISTENER
            floor.fill(Long.MIN_VALUE); last.fill(Long.MIN_VALUE)
        }
    }

    /** The rates the samples given to DustMotion came at, for the dust's ten-second line (DustPace.line). */
    class Rates {
        private val prev = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
        private val n = IntArray(2)
        private val span = LongArray(2)

        /** A sample of [k] at [t] given on. A gap of [OFF_NS] or more (the sensors off between) is not counted. */
        fun sample(k: Int, t: Long) {
            val p = prev[k]
            if (p != Long.MIN_VALUE && t > p && t - p < OFF_NS) { n[k]++; span[k] += t - p }
            prev[k] = t
        }

        /** Samples a second of [k] since the last [said] (0: none). */
        fun hz(k: Int): Float = if (span[k] > 0L) (n[k] * 1e9 / span[k]).toFloat() else 0f

        /** "the accelerometer 50 a second, the gyroscope 50, through the direct channel", and counted afresh. */
        fun said(path: Int): String {
            val s = if (path == OFF && n[0] == 0 && n[1] == 0) "the phone's movement not heard (its sensors off)"
            else String.format(Locale.ROOT, "the accelerometer %.0f a second, the gyroscope %.0f, %s", hz(ACCEL), hz(GYRO), PATHS[path])
            n.fill(0); span.fill(0L)
            return s
        }

        /** Counted afresh, from no sample. */
        fun reset() {
            n.fill(0); span.fill(0L); prev.fill(Long.MIN_VALUE)
        }
    }

    companion object {
        /** A report: its size and where its parts are (SensorDirectChannel's layout). */
        const val SIZE = 104
        const val TOKEN = 4
        const val TYPE = 8
        const val COUNTER = 12
        const val TIME = 16
        const val DATA = 24
        val ORDER: ByteOrder = ByteOrder.nativeOrder()
        /** The sensor types the reports name (android.hardware.Sensor's: the accelerometer 1, the gyroscope 4). */
        const val ACCEL_TYPE = 1
        const val GYRO_TYPE = 4
        /** What a report is, in [kind]. */
        const val ACCEL = 0
        const val GYRO = 1
        private val NAMES = arrayOf("accelerometer", "gyroscope")
        private val AXES = arrayOf("x", "y", "z")
        private val UNITS = arrayOf("m/s²", "rad/s")

        /**
         * The ring: 512 reports, 53 KB. The rate asked for (SensorDirectChannel.RATE_NORMAL) is about 50 a second and at
         * most 110, so the two sensors fill it in 2.3 s at the very least.
         */
        const val SLOTS = 512
        /** A gap this long between two reads and the reading starts again at the newest (far from a whole ring). */
        const val STALL_NS = 1_000_000_000L

        /** Under this many samples a second the channel is given up (DustMotion.RATE_US asks 50 of the listener). */
        const val MIN_HZ = 45f
        /** The rate the dust is made for: the listener's, DustMotion.RATE_US (50 a second). */
        const val NOMINAL_HZ = 1e6f / DustMotion.RATE_US
        /**
         * On trial the channel's rate must be the listener's and [NOMINAL_HZ], each sensor within this share of them: the
         * same pace of samples into DustMotion as before (a faster sensor is a noisier one past DustMotion's dead zones).
         * The listener's alone is not enough: measured beside the channel, it may run at whatever rate the channel set.
         */
        const val SAME_RATE = 0.1f
        /** On trial each value's mean over the same second must be the listener's within this (m/s², rad/s). */
        const val SAME_ACCEL = 0.3f
        const val SAME_GYRO = 0.02f
        /** The same second starts this long after the trial (the channel's first reports come within tens of ms). */
        const val LEAD_NS = 100_000_000L
        /** The rate is judged over a second of a sensor's samples. */
        const val WINDOW_NS = 1_000_000_000L
        /** On trial no longer than this. */
        const val TRIAL_NS = 2_000_000_000L
        /** A sensor silent this long while it feeds the dust: given up. */
        const val SILENT_NS = 250_000_000L
        /**
         * A report's time this far ahead of the clock, or a read's newest this far behind it: not on the clock the reads
         * are made by. Ahead: the clock is read before the ring, a report may be written between. Behind: a report is
         * written within a sample or two of being taken; reads come every picture, and after a stall the reading starts
         * at the newest.
         */
        const val AHEAD_NS = 20_000_000L
        const val BEHIND_NS = 500_000_000L
        /** Passing failures in a row before the channel is given up in this process ([Tries]). */
        const val TRIES = 3
        /** What [Watch.check] and [Session.poll] say. */
        const val WAIT = 0
        const val PASS = 1
        const val FAIL = 2

        /** Who feeds DustMotion ([Feed]). */
        const val LISTENER = 0
        const val DIRECT = 1
        /** How the samples come, for the log ([Rates.said]). */
        const val OFF = 0
        const val BY_LISTENER = 1
        const val ON_TRIAL = 2
        const val BY_DIRECT = 3
        private val PATHS = arrayOf("the sensors off", "from the listener", "from the listener, the direct channel on trial", "through the direct channel")
        /** Two samples further apart than this: the sensors were off between them (for [Rates]). */
        const val OFF_NS = 100_000_000L

        /** The counter after [c]: one more, and 1 again after 0xFFFFFFFF (never 0). */
        fun next(c: Int): Int = if (c == -1) 1 else c + 1

        /** The counter before [c] ([next] backwards). */
        fun previous(c: Int): Int = if (c == 1) -1 else c - 1

        /**
         * The channel's rates ([channel], per sensor) are the listener's ([listener], measured over the same trial) and
         * [NOMINAL_HZ], each within [SAME_RATE]: null, or why not.
         */
        fun sameRate(channel: FloatArray, listener: FloatArray): String? {
            for (k in 0..1) {
                val l = listener[k]
                if (l <= 0f || abs(channel[k] - l) > SAME_RATE * l) {
                    return String.format(Locale.ROOT, "the %s at %.1f a second through it, %.1f from the listener", NAMES[k], channel[k], l)
                }
                if (abs(channel[k] - NOMINAL_HZ) > SAME_RATE * NOMINAL_HZ) {
                    return String.format(Locale.ROOT, "the %s at %.1f a second through it, not the %.0f the dust is made for", NAMES[k], channel[k], NOMINAL_HZ)
                }
            }
            return null
        }
    }
}
