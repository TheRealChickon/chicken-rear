package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * THE SENSORS' DIRECT CHANNEL, READ (DirectReports, for DustSensor): the reports as the hardware writes them (a ring,
 * each report whole before its counter), read whole, once, in time order; nothing half written, of another sensor or
 * stale ever given on; the ring gone round past started again at the newest; the channel kept only at its pace, on
 * the clock, at the dust's own rate and with the listener's values; the listener and the channel never both feeding,
 * no sample lost at the hand-over; a stall while it feeds not read as a push; and the same samples giving DustMotion
 * the very same motion.
 */
class DirectReportsTest {
    private val size = DirectReports.SIZE
    private val accel = 7
    private val gyro = 9

    /** The hardware's side, as Android's reference writer does it: the report, then its counter, then the next slot. */
    private class Hal(val slots: Int, start: Int = 1) {
        val mem: ByteBuffer = ByteBuffer.allocate(slots * DirectReports.SIZE).order(DirectReports.ORDER)
        var pos = 0
        var counter = start
        val memory = DirectReports.Memory { dst, from, count -> System.arraycopy(mem.array(), from, dst, 0, count) }

        /** The report written, its counter not yet. */
        fun body(token: Int, type: Int, t: Long, x: Float, y: Float, z: Float, size: Int = DirectReports.SIZE) {
            val at = pos * DirectReports.SIZE
            mem.putInt(at, size); mem.putInt(at + DirectReports.TOKEN, token); mem.putInt(at + DirectReports.TYPE, type)
            mem.putLong(at + DirectReports.TIME, t)
            mem.putFloat(at + DirectReports.DATA, x); mem.putFloat(at + DirectReports.DATA + 4, y); mem.putFloat(at + DirectReports.DATA + 8, z)
        }

        /** Its counter, last: the report is there. */
        fun publish() {
            mem.putInt(pos * DirectReports.SIZE + DirectReports.COUNTER, counter)
            counter = DirectReports.next(counter)
            pos = (pos + 1) % slots
        }

        fun write(token: Int, type: Int, t: Long, x: Float = 0f, y: Float = 0f, z: Float = 0f) { body(token, type, t, x, y, z); publish() }
    }

    private fun Hal.accel(t: Long, x: Float = 0f, y: Float = 0f, z: Float = 0f) = write(7, DirectReports.ACCEL_TYPE, t, x, y, z)
    private fun Hal.gyro(t: Long, x: Float = 0f, y: Float = 0f, z: Float = 0f) = write(9, DirectReports.GYRO_TYPE, t, x, y, z)

    // -- reading -----------------------------------------------------------------------------------------------------

    @Test fun `what is new is read whole and once, in time order across the two sensors`() {
        val hal = Hal(16); val r = DirectReports(16)
        assertEquals(0, r.read(hal.memory, accel, gyro))
        hal.accel(1_000, 0.5f, 9.75f, -1.25f)
        hal.gyro(900, 0.01f, -0.02f, 0.3f)         // written after, sampled before
        hal.accel(1_200, 0.25f, 9.5f, -1f)
        assertEquals(3, r.read(hal.memory, accel, gyro))
        assertEquals(listOf(900L, 1_000L, 1_200L), (0 until 3).map { r.time[it] })
        assertEquals(listOf(DirectReports.GYRO, DirectReports.ACCEL, DirectReports.ACCEL), (0 until 3).map { r.kind[it] })
        assertEquals(0.01f, r.x[0], 0f); assertEquals(-0.02f, r.y[0], 0f); assertEquals(0.3f, r.z[0], 0f)
        assertEquals(0.5f, r.x[1], 0f); assertEquals(9.75f, r.y[1], 0f); assertEquals(-1.25f, r.z[1], 0f)
        // Nothing new: nothing.
        assertEquals(0, r.read(hal.memory, accel, gyro))
        assertNull(r.broken)
    }

    @Test fun `equal times keep the order they were written in`() {
        val hal = Hal(8); val r = DirectReports(8)
        hal.gyro(500, 1f); hal.accel(500, 2f); hal.gyro(400, 3f)
        assertEquals(3, r.read(hal.memory, accel, gyro))
        assertEquals(listOf(3f, 1f, 2f), (0 until 3).map { r.x[it] })
    }

    @Test fun `a report whose counter is not written yet is not read, and is read whole once it is`() {
        val hal = Hal(8); val r = DirectReports(8)
        hal.accel(100)
        hal.body(accel, DirectReports.ACCEL_TYPE, 200, 1f, 2f, 3f)
        assertEquals(1, r.read(hal.memory, accel, gyro))
        assertEquals(100L, r.time[0])
        assertEquals(0, r.read(hal.memory, accel, gyro))
        hal.publish()
        assertEquals(1, r.read(hal.memory, accel, gyro))
        assertEquals(200L, r.time[0]); assertEquals(3f, r.z[0], 0f)
    }

    @Test fun `round the ring again and again, every report once and in order`() {
        val hal = Hal(8); val r = DirectReports(8)
        var t = 0L; var got = 0L; var last = -1L
        repeat(1_000) {
            repeat(it % 5) { k -> t += 10; if (k % 2 == 0) hal.accel(t, t.toFloat()) else hal.gyro(t, t.toFloat()) }
            val n = r.read(hal.memory, accel, gyro)
            for (i in 0 until n) { assertTrue(r.time[i] > last); assertEquals(r.time[i].toFloat(), r.x[i], 0f); last = r.time[i] }
            got += n
        }
        assertEquals(t / 10, got)
        assertEquals(0, r.restarts)
    }

    @Test fun `gone round past the reader - nothing of that ring given, on from the newest`() {
        val hal = Hal(8); val r = DirectReports(8)
        hal.accel(10); assertEquals(1, r.read(hal.memory, accel, gyro))
        // Twenty more before the next read: the slot it was to read now holds a later ring's report.
        for (k in 1..20) hal.accel(10L + k)
        assertEquals(0, r.read(hal.memory, accel, gyro))
        assertEquals(1, r.restarts)
        assertEquals(20L, r.passedOver)
        hal.gyro(100); hal.accel(110)
        assertEquals(2, r.read(hal.memory, accel, gyro))
        assertEquals(100L, r.time[0]); assertEquals(110L, r.time[1])
        // And after a long gap DustSensor starts at the newest itself: what is there is passed over.
        hal.accel(120); hal.accel(130)
        r.skipToNewest(hal.memory)
        assertEquals(0, r.read(hal.memory, accel, gyro))
        hal.accel(140); assertEquals(1, r.read(hal.memory, accel, gyro)); assertEquals(140L, r.time[0])
        // Nothing newer than what was read: the reading stays.
        r.skipToNewest(hal.memory)
        hal.accel(150); assertEquals(1, r.read(hal.memory, accel, gyro)); assertEquals(150L, r.time[0])
    }

    @Test fun `another sensor's report, or another type, is passed over - a wrong size gives the channel up`() {
        val hal = Hal(8); val r = DirectReports(8)
        hal.write(99, DirectReports.ACCEL_TYPE, 10)
        hal.write(accel, 33, 20)                  // its token, another type (additional information)
        hal.write(gyro, DirectReports.ACCEL_TYPE, 30)
        hal.accel(40)
        assertEquals(1, r.read(hal.memory, accel, gyro))
        assertEquals(40L, r.time[0]); assertEquals(3L, r.passedOver)
        hal.body(accel, DirectReports.ACCEL_TYPE, 50, 0f, 0f, 0f, size = 120); hal.publish()
        hal.accel(60)
        assertEquals(0, r.read(hal.memory, accel, gyro))
        assertTrue(r.broken!!.contains("120"))
    }

    @Test fun `a hardware that counts another way gives nothing at all`() {
        // Each sensor its own counter: the second slot says 1 again, never the 2 expected.
        val hal = Hal(8); val r = DirectReports(8)
        hal.accel(10)
        hal.counter = 1; hal.gyro(10)
        hal.counter = 2; hal.accel(20)
        assertEquals(1, r.read(hal.memory, accel, gyro))
        repeat(10) { assertEquals(0, r.read(hal.memory, accel, gyro)) }
    }

    @Test fun `the counter goes on through 0xFFFFFFFF to 1`() {
        assertEquals(1, DirectReports.next(-1))
        assertEquals(Int.MIN_VALUE, DirectReports.next(Int.MAX_VALUE))
        assertEquals(2, DirectReports.next(1))
        val hal = Hal(8, start = -2); val r = DirectReports(8, first = -2)
        hal.accel(1); hal.accel(2); hal.accel(3); hal.accel(4)   // -2, -1, then 1 (never 0), then 2
        assertEquals(4, r.read(hal.memory, accel, gyro))
        assertEquals(listOf(1L, 2L, 3L, 4L), (0 until 4).map { r.time[it] })
        hal.accel(5); assertEquals(1, r.read(hal.memory, accel, gyro))
    }

    @Test fun `read again from further back - never past a skip, never more than was read`() {
        val hal = Hal(16); val r = DirectReports(16)
        for (k in 1..5) hal.accel(k * 10L)
        assertEquals(5, r.read(hal.memory, accel, gyro))
        r.back(3)
        assertEquals(listOf(30L, 40L, 50L), (0 until r.read(hal.memory, accel, gyro)).map { r.time[it] })
        // No further back than what was read since the first.
        r.back(100)
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), (0 until r.read(hal.memory, accel, gyro)).map { r.time[it] })
        // Then on as before.
        hal.accel(60); assertEquals(listOf(60L), (0 until r.read(hal.memory, accel, gyro)).map { r.time[it] })
        // Gone round past the reader: nothing from before the skip is read again.
        for (k in 7..30) hal.accel(k * 10L)
        assertEquals(0, r.read(hal.memory, accel, gyro)); assertEquals(1, r.restarts)
        r.back(10); assertEquals(0, r.read(hal.memory, accel, gyro))
        hal.accel(310); assertEquals(1, r.read(hal.memory, accel, gyro))
        r.back(10); assertEquals(listOf(310L), (0 until r.read(hal.memory, accel, gyro)).map { r.time[it] })
        // Back across the counter's wrap (1 after 0xFFFFFFFF).
        assertEquals(-1, DirectReports.previous(1)); assertEquals(Int.MAX_VALUE, DirectReports.previous(Int.MIN_VALUE)); assertEquals(1, DirectReports.previous(2))
        val h2 = Hal(8, start = -2); val r2 = DirectReports(8, first = -2)
        h2.accel(1); h2.accel(2); h2.accel(3); h2.accel(4)
        assertEquals(4, r2.read(h2.memory, accel, gyro))
        r2.back(4); assertEquals(listOf(1L, 2L, 3L, 4L), (0 until r2.read(h2.memory, accel, gyro)).map { r2.time[it] })
        h2.accel(5); assertEquals(1, r2.read(h2.memory, accel, gyro))
    }

    @Test fun `a read allocates nothing, nor a skip`() {
        val hal = Hal(DirectReports.SLOTS); val r = DirectReports()
        val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val bytesOf = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        // The JVM makes its own accessor for a method called by reflection on about its sixteenth call, and the bytes
        // that takes are this thread's: called that often first, so neither this window nor another test's has them.
        repeat(20) { bytesOf.invoke(mx, id) }
        var t = 0L
        fun polls(n: Int) = repeat(n) { t += 10_000_000; hal.gyro(t); hal.accel(t + 1); if (it % 50 == 49) r.skipToNewest(hal.memory); r.read(hal.memory, accel, gyro) }
        polls(20_000)
        fun bytes(n: Int): Long { val before = bytesOf.invoke(mx, id) as Long; polls(n); return bytesOf.invoke(mx, id) as Long - before }
        val short = bytes(10_000)
        val long = bytes(60_000)
        val perRead = (long - short) / 50_000.0
        println("dust's direct channel: $perRead bytes allocated a read")
        assertTrue("allocated $perRead bytes a read", perRead < 0.05)
    }

    // -- the pace kept -----------------------------------------------------------------------------------------------

    /** [hz] a second of each sensor for [s] seconds from [fromNs], read every 1/60 s: what the watch says each read. */
    private fun watch(hzA: Float, hzG: Float, s: Float, fromNs: Long = 1_000_000_000L, w: DirectReports.Watch = DirectReports.Watch(fromNs)): List<Int> {
        val out = ArrayList<Int>()
        var nextA = fromNs + 5_000_000L; var nextG = fromNs + 7_000_000L
        var now = fromNs
        while (now < fromNs + (s * 1e9f).toLong()) {
            now += 16_666_667L
            while (hzA > 0f && nextA <= now) { w.report(DirectReports.ACCEL, nextA, now); nextA += (1e9f / hzA).toLong() }
            while (hzG > 0f && nextG <= now) { w.report(DirectReports.GYRO, nextG, now); nextG += (1e9f / hzG).toLong() }
            val v = w.check(now)
            // Passed: it feeds from now on (as Session takes it over).
            if (v == DirectReports.PASS && !w.feeding) w.feed(w.hz)
            out += v
        }
        return out
    }

    @Test fun `on trial - passed after a second at 50 a second, failed under 45 or with nothing`() {
        val ok = watch(50f, 50f, 1.5f)
        assertEquals(1, ok.count { it == DirectReports.PASS })
        assertFalse(ok.contains(DirectReports.FAIL))
        // About a second in (the first samples come a few ms after the start).
        assertTrue(ok.indexOf(DirectReports.PASS) in 59..66)
        assertTrue(watch(52f, 104f, 1.5f).contains(DirectReports.PASS))
        val slow = DirectReports.Watch(1_000_000_000L)
        assertEquals(DirectReports.FAIL, watch(40f, 50f, 1.5f, w = slow).last())
        assertTrue(slow.why, slow.why.contains("accelerometer at 40"))
        // A slow second is a passing failure (tried again at the next start).
        assertFalse(slow.structural)
        val oneSlow = watch(50f, 30f, 1.5f)
        assertEquals(DirectReports.FAIL, oneSlow.last()); assertFalse(oneSlow.contains(DirectReports.PASS))
        // Nothing comes: given up at the trial's end, not before, and for good (nothing at all is the channel's own make).
        val nothing = DirectReports.Watch(1_000_000_000L)
        val none = watch(0f, 0f, 2.2f, w = nothing)
        assertEquals(DirectReports.FAIL, none.last())
        assertTrue(none.indexOf(DirectReports.FAIL) in 119..121)
        assertTrue(nothing.why, nothing.structural && nothing.why.contains("no report at all"))
        // One sensor only: a passing failure.
        val half = DirectReports.Watch(1_000_000_000L)
        assertEquals(DirectReports.FAIL, watch(50f, 0f, 2.2f, w = half).last())
        assertTrue(half.why, !half.structural && half.why.contains("not a second of both"))
    }

    @Test fun `a stall on trial starts its second again - the trial runs from there`() {
        val w = DirectReports.Watch(0L)
        watch(50f, 50f, 0.6f, fromNs = 0L, w = w)
        w.restart(1_800_000_000L)
        assertEquals(DirectReports.WAIT, w.check(2_100_000_000L))
        assertEquals(1_800_000_000L, w.trialFrom)
        assertEquals(DirectReports.FAIL, w.check(3_900_000_000L))
        // Once it feeds a restart is not a trial again.
        val f = DirectReports.Watch(0L)
        watch(50f, 50f, 1.2f, fromNs = 0L, w = f)
        f.restart(1_500_000_000L)
        assertEquals(0L, f.trialFrom)
    }

    @Test fun `a report's time ahead of the clock, or a read's newest far behind it - another clock, given up for good`() {
        val ms = 1_000_000L
        val w = DirectReports.Watch(0L)
        // 15 ms ahead: written between the clock's reading and the ring's.
        w.report(DirectReports.ACCEL, 1_015 * ms, 1_000 * ms)
        assertEquals(DirectReports.WAIT, w.check(1_000 * ms))
        w.report(DirectReports.ACCEL, 1_030 * ms, 1_008 * ms)
        assertEquals(DirectReports.FAIL, w.check(1_008 * ms))
        assertTrue(w.why, w.structural && w.why.contains("22 ms ahead of the clock (not elapsedRealtime)"))
        val v = DirectReports.Watch(0L)
        v.report(DirectReports.GYRO, 500 * ms, 990 * ms)
        assertEquals(DirectReports.WAIT, v.check(990 * ms))
        v.report(DirectReports.GYRO, 510 * ms, 1_020 * ms)
        assertEquals(DirectReports.FAIL, v.check(1_020 * ms))
        assertTrue(v.why, v.structural && v.why.contains("the gyroscope's newest time 510 ms behind the clock"))
        // An old report beside a new one in the same read (after a stall): judged by the newest.
        val u = DirectReports.Watch(0L)
        u.report(DirectReports.ACCEL, 100 * ms, 1_000 * ms); u.report(DirectReports.ACCEL, 995 * ms, 1_000 * ms)
        assertEquals(DirectReports.WAIT, u.check(1_000 * ms))
        // A read with nothing in it says nothing of the clock.
        assertEquals(DirectReports.WAIT, u.check(1_900 * ms))
    }

    @Test fun `once feeding - a sensor silent a quarter of a second, or a slow second, gives it up`() {
        val w = DirectReports.Watch(0L)
        assertTrue(watch(50f, 50f, 1.2f, fromNs = 0L, w = w).contains(DirectReports.PASS))
        // The gyroscope stops.
        val after = watch(50f, 0f, 0.5f, fromNs = 1_200_000_000L, w = w)
        assertEquals(DirectReports.FAIL, after.last())
        assertTrue(w.why, w.why.contains("gyroscope silent"))
        // Or it slows down.
        val v = DirectReports.Watch(0L)
        watch(50f, 50f, 1.2f, fromNs = 0L, w = v)
        assertEquals(DirectReports.FAIL, watch(50f, 40f, 1.5f, fromNs = 1_200_000_000L, w = v).last())
        // A restart (reports skipped) is not silence.
        val q = DirectReports.Watch(0L)
        watch(50f, 50f, 1.2f, fromNs = 0L, w = q)
        q.restart(1_600_000_000L)
        assertEquals(DirectReports.WAIT, q.check(1_700_000_000L))
        // Silence while it feeds is of the channel's own make; a slow second is not.
        assertTrue(w.structural)
        // Faster than it took over at (by over a tenth): a passing failure. A little faster: kept.
        val fast = DirectReports.Watch(0L)
        watch(50f, 50f, 1.2f, fromNs = 0L, w = fast)
        assertFalse(watch(50f, 54f, 1.5f, fromNs = 1_200_000_000L, w = fast).contains(DirectReports.FAIL))
        assertTrue(watch(50f, 60f, 1.5f, fromNs = 2_700_000_000L, w = fast).contains(DirectReports.FAIL))
        assertTrue(fast.why, !fast.structural && fast.why.startsWith("the gyroscope at ") && fast.why.endsWith("a second, over the 50.0 it took over at"))
    }

    @Test fun `the channel takes over only at the listener's own pace`() {
        assertNull(DirectReports.sameRate(floatArrayOf(50f, 50f), floatArrayOf(50.2f, 49.9f)))
        assertNull(DirectReports.sameRate(floatArrayOf(52f, 48f), floatArrayOf(50f, 50f)))
        // Twice as fast (another rate the hardware chose for the channel): the listener stays.
        assertTrue(DirectReports.sameRate(floatArrayOf(50f, 100f), floatArrayOf(50f, 50f))!!.contains("gyroscope at 100.0 a second through it, 50.0 from the listener"))
        assertTrue(DirectReports.sameRate(floatArrayOf(44f, 50f), floatArrayOf(50f, 50f))!!.contains("accelerometer"))
        // Nothing measured from the listener: no comparison, no channel.
        assertTrue(DirectReports.sameRate(floatArrayOf(50f, 50f), floatArrayOf(0f, 50f)) != null)
        // The listener beside the channel runs at whatever the channel set: both at 100, not the dust's 50.
        assertTrue(DirectReports.sameRate(floatArrayOf(100f, 100f), floatArrayOf(100f, 100f))!!.contains("the accelerometer at 100.0 a second through it, not the 50 the dust is made for"))
        assertNull(DirectReports.sameRate(floatArrayOf(52f, 52f), floatArrayOf(52f, 52f)))
        assertEquals(50f, DirectReports.NOMINAL_HZ, 0f)
        // The listener's rate over the trial, as DustSensor counts it.
        val r = DirectReports.Rates()
        for (k in 0..50) r.sample(DirectReports.ACCEL, 7_000_000L + k * 19_900_000L)
        assertEquals(50.25f, r.hz(DirectReports.ACCEL), 0.01f)
        r.reset(); assertEquals(0f, r.hz(DirectReports.ACCEL), 0f)
        r.sample(DirectReports.ACCEL, 10_000_000_000L); assertEquals(0f, r.hz(DirectReports.ACCEL), 0f)
    }

    // -- who feeds ---------------------------------------------------------------------------------------------------

    @Test fun `the listener feeds until the channel is handed over, never both, never a sample twice or going back`() {
        val f = DirectReports.Feed()
        val L = DirectReports.LISTENER; val D = DirectReports.DIRECT; val A = DirectReports.ACCEL; val G = DirectReports.GYRO
        // Fresh: every sample of the listener, as it always was, even one out of order; none of the channel's.
        assertTrue(f.take(L, A, 100)); assertTrue(f.take(L, A, 90)); assertTrue(f.take(L, G, 95))
        assertFalse(f.take(D, A, 120))
        f.handTo(D)
        // The channel from past the listener's last of each sensor; the listener's no more.
        assertFalse(f.take(D, A, 100)); assertTrue(f.take(D, A, 101)); assertFalse(f.take(D, G, 95)); assertTrue(f.take(D, G, 96))
        assertFalse(f.take(L, A, 200))
        // Back to the listener (the channel given up): from past the channel's last.
        f.handTo(L)
        assertFalse(f.take(L, A, 101)); assertTrue(f.take(L, A, 102)); assertFalse(f.take(D, A, 300))
        f.fresh()
        assertTrue(f.take(L, A, 5)); assertFalse(f.take(D, A, 6))
    }

    @Test fun `the rates said in the log`() {
        val r = DirectReports.Rates()
        for (k in 0..100) { r.sample(DirectReports.ACCEL, k * 20_000_000L); r.sample(DirectReports.GYRO, k * 20_000_000L + 3) }
        // A gap with the sensors off between is not counted.
        r.sample(DirectReports.ACCEL, 5_000_000_000L); r.sample(DirectReports.ACCEL, 5_020_000_000L)
        assertEquals(50f, r.hz(DirectReports.ACCEL), 0.01f)
        assertEquals("the accelerometer 50 a second, the gyroscope 50, through the direct channel", r.said(DirectReports.BY_DIRECT))
        assertEquals("the phone's movement not heard (its sensors off)", r.said(DirectReports.OFF))
        r.sample(DirectReports.ACCEL, 5_040_000_000L)
        assertEquals("the accelerometer 50 a second, the gyroscope 0, from the listener, the direct channel on trial", r.said(DirectReports.ON_TRIAL))
    }

    // -- the same motion ---------------------------------------------------------------------------------------------

    @Test fun `the same samples through the channel give DustMotion the very same motion as through the listener`() {
        val held = floatArrayOf(0f, 8.5f, -4.9f)
        val moves = listOf(
            0.5f to DustMotion.Made(held, tilt = Math.toRadians(15.0).toFloat(), turnS = 1.5f),
            2.4f to DustMotion.Made(held, pushX = 3f, pushY = -1f, pushS = 0.5f),
            3.0f to DustMotion.Made(held, turn = Math.toRadians(30.0).toFloat(), turnS = 0.8f),
        )
        val byListener = DustMotion(); val byChannel = DustMotion()
        val hal = Hal(DirectReports.SLOTS); val r = DirectReports()
        val a = FloatArray(3); val w = FloatArray(3)
        var sampleNs = 1_000_000L
        var pictureNs = 0L
        repeat(5 * 120) {
            pictureNs += 8_333_333L
            // The samples up to this picture: straight into one, through the ring into the other.
            while (sampleNs <= pictureNs) {
                val s = sampleNs / 1e9f
                val mv = moves.lastOrNull { s >= it.first }
                if (mv != null) mv.second.at(s - mv.first, a, w) else { held.copyInto(a); w.fill(0f) }
                byListener.gyro(w[0], w[1], w[2], sampleNs); byListener.accel(a[0], a[1], a[2], sampleNs)
                hal.gyro(sampleNs, w[0], w[1], w[2]); hal.accel(sampleNs, a[0], a[1], a[2])
                sampleNs += DustMotion.RATE_US * 1000L
            }
            val n = r.read(hal.memory, accel, gyro)
            for (i in 0 until n) {
                if (r.kind[i] == DirectReports.ACCEL) byChannel.accel(r.x[i], r.y[i], r.z[i], r.time[i]) else byChannel.gyro(r.x[i], r.y[i], r.z[i], r.time[i])
            }
            byListener.step(1f / 120f); byChannel.step(1f / 120f)
            assertEquals(byListener.lagX, byChannel.lagX, 0f); assertEquals(byListener.lagY, byChannel.lagY, 0f)
            assertEquals(byListener.spin, byChannel.spin, 0f); assertEquals(byListener.stir, byChannel.stir, 0f)
            assertEquals(byListener.tiltX, byChannel.tiltX, 0f); assertEquals(byListener.tiltY, byChannel.tiltY, 0f)
        }
        // And it did move.
        assertTrue(byChannel.stir > 0f || byChannel.spin != 0f)
    }

    // -- the same second, two sources ---------------------------------------------------------------------------------

    @Test fun `the same brisk move sampled at other moments - the same mean over the same second`() {
        val ms = 1_000_000L
        val a = DirectReports.Mean(); val b = DirectReports.Mean()
        a.window(100 * ms, 1_100 * ms); b.window(100 * ms, 1_100 * ms)
        fun v(t: Long) = (3.0 * sin(2 * PI * 2.5 * t / 1e9) + 9.0).toFloat()
        var t = 3 * ms; while (t <= 1_200 * ms) { a.add(t, v(t), 0f, -v(t)); t += 20 * ms }
        t = 13 * ms; while (t <= 1_200 * ms) { b.add(t, v(t), 0f, -v(t)); t += 20 * ms }
        assertTrue(a.done && b.done)
        assertEquals(1_000 * ms, a.covered); assertEquals(1_000 * ms, b.covered)
        // The movement's own mean over the second is 9 (two and a half of its waves).
        assertEquals(9f, a.mean(0), 0.02f); assertEquals(9f, b.mean(0), 0.02f)
        assertTrue(abs(a.mean(0) - b.mean(0)) < 0.02f); assertEquals(-a.mean(0), a.mean(2), 1e-6f)
        // A sample not after the last is let be; before its first sample nothing is covered.
        a.add(500 * ms, 1_000f, 0f, 0f); assertEquals(9f, a.mean(0), 0.02f)
        val c = DirectReports.Mean(); c.window(100 * ms, 1_100 * ms)
        assertTrue(c.mean(0).isNaN()); assertFalse(c.done)
    }

    // -- tried again --------------------------------------------------------------------------------------------------

    @Test fun `tried again after a passing failure, three in a row at most - never after one of its own make`() {
        val t = DirectReports.Tries()
        assertFalse(t.failed(false)); assertFalse(t.failed(false)); assertTrue(t.failed(false)); assertTrue(t.off)
        // A channel that fed the dust to the end clears the count.
        val u = DirectReports.Tries()
        u.failed(false); u.failed(false); u.fedToTheEnd()
        assertEquals(0, u.count)
        assertFalse(u.failed(false)); assertFalse(u.failed(false)); assertTrue(u.failed(false))
        val v = DirectReports.Tries()
        assertTrue(v.failed(true)); assertTrue(v.off)
    }

    // -- a channel's life beside the listener (Session, as DustSensor plays it) -------------------------------------

    /** The phone held up, its back to him, tipped back a little: gravity in the phone's axes. */
    private val held = floatArrayOf(0f, 8.5f, -4.9f)
    private val stillPhone: (Double, FloatArray, FloatArray) -> Unit = { _, a, w -> held.copyInto(a); w.fill(0f) }

    /**
     * DUSTSENSOR'S TWO PATHS ON ONE CLOCK (ns, as elapsedRealtimeNanos), as it plays them: the hardware writes both
     * sensors at [channelHz] into the ring, their times [channelShift] off the clock; the listener, while [listenerOn],
     * is given the same phone's samples at [listenerHz], [late] after they were taken (its first long before the trial);
     * the scene reads the ring every 1/120 s ([DirectReports.Session.poll]) and steps DustMotion; then the sensors'
     * thread runs what was posted (DustSensor's letting the listener go and taking it back), unless [hold]. [move] gives
     * the phone's accelerometer and gyroscope at a time (s). [given]: every sample DustMotion took, by sensor.
     */
    private inner class Play(
        val move: (Double, FloatArray, FloatArray) -> Unit = stillPhone,
        val channelShift: Long = 0L,
        val channelPhase: Long = 0L,
        val late: Long = 4_000_000L,
        val gyroBias: Float = 0f,
        val accelScale: Float = 1f,
        val resyncs: Boolean = true,
    ) {
        val start = 20_000_000_000L
        var now = start
        var channelHz = 50f
        var listenerHz = 50f
        val hal = Hal(DirectReports.SLOTS)
        val motion = DustMotion()
        val feed = DirectReports.Feed()
        val session = DirectReports.Session(hal.memory, accel, gyro, start)
        val calls = ArrayList<String>()
        val verdicts = ArrayList<Pair<Long, Int>>()
        val given = arrayOf(ArrayList<Long>(), ArrayList<Long>())
        var listenerOn = true
        var hold = false
        val posts = ArrayDeque<() -> Unit>()
        var lagMax = 0f
        private var nextC = start + 3_000_000L + channelPhase
        private var nextL = start - 297_000_000L
        private val a = FloatArray(3)
        private val w = FloatArray(3)

        val host = object : DirectReports.Session.Host {
            override fun give(k: Int, x: Float, y: Float, z: Float, t: Long): Boolean {
                if (!feed.take(DirectReports.DIRECT, k, t)) return false
                took(k, x, y, z, t)
                return true
            }
            override fun takeOver() { calls += "takeOver"; feed.handTo(DirectReports.DIRECT) }
            override fun handBack() { calls += "handBack"; feed.handTo(DirectReports.LISTENER) }
            override fun resync() { calls += "resync"; if (resyncs) motion.resync() }
        }

        private fun took(k: Int, x: Float, y: Float, z: Float, t: Long) {
            given[k] += t
            if (k == DirectReports.ACCEL) motion.accel(x, y, z, t) else motion.gyro(x, y, z, t)
        }

        /** DustSensor.give for the listener. */
        private fun listen(k: Int, v: FloatArray, t: Long) {
            if (!feed.take(DirectReports.LISTENER, k, t)) return
            session.listened(k, v[0], v[1], v[2], t)
            took(k, v[0], v[1], v[2], t)
        }

        /** [s] seconds on; with [polls] false the scene's thread is stalled: no reads, no steps. */
        fun run(s: Double, polls: Boolean = true): Play {
            val end = now + (s * 1e9).toLong()
            while (now < end) {
                now += 8_333_333L
                while (channelHz > 0f && nextC <= now) {
                    move(nextC / 1e9, a, w)
                    val t = nextC + channelShift
                    hal.gyro(t, w[0], w[1], w[2] + gyroBias)
                    hal.accel(t, a[0] * accelScale, a[1] * accelScale, a[2] * accelScale)
                    nextC += (1e9f / channelHz).toLong()
                }
                while (nextL + late <= now) {
                    if (listenerOn) {
                        move(nextL / 1e9, a, w)
                        listen(DirectReports.GYRO, w, nextL); listen(DirectReports.ACCEL, a, nextL)
                    }
                    nextL += (1e9f / listenerHz).toLong()
                }
                if (!polls) continue
                when (val v = session.poll(now, host)) {
                    DirectReports.PASS -> {
                        verdicts += now to v
                        posts += { if (session.letGo(listenerOn)) { listenerOn = false; calls += "letGo" } }
                    }
                    DirectReports.FAIL -> {
                        verdicts += now to v
                        posts += { if (session.listenAgain(listenerOn, sensorsOn = true)) { listenerOn = true; calls += "listenAgain" } }
                    }
                }
                if (!hold) drain()
                motion.step(1f / 120f)
                lagMax = max(lagMax, hypot(motion.lagX, motion.lagY))
            }
            return this
        }

        fun drain() { while (posts.isNotEmpty()) posts.removeFirst()() }
        val said: List<Int> get() = verdicts.map { it.second }
        fun at(v: Int): Long? = verdicts.firstOrNull { it.second == v }?.first
        /** The steps between the samples of [k] DustMotion took: one sample period each, none twice, none going back. */
        fun steps(k: Int): Set<Long> = given[k].zipWithNext { p, q -> q - p }.toSet()
    }

    @Test fun `on trial beside the listener, then the channel feeds - every sample once, none lost, the listener let go`() {
        // The listener a little late, and later than a picture: what the channel read on trial is given past its last.
        for (late in listOf(4_000_000L, 30_000_000L)) {
            val p = Play(late = late).run(3.0)
            assertEquals(listOf(DirectReports.PASS), p.said)
            val at = p.at(DirectReports.PASS)!! - p.start
            assertTrue("passed ${at / 1e6} ms in", at in 1_100_000_000L..1_200_000_000L)
            assertEquals(listOf("takeOver", "letGo"), p.calls)
            assertFalse(p.listenerOn)
            assertEquals(DirectReports.Session.FEEDING, p.session.state)
            assertEquals(DirectReports.DIRECT, p.feed.from)
            for (k in 0..1) assertEquals(setOf(20_000_000L), p.steps(k))
            // Fed by the channel to the end.
            assertTrue(p.given[DirectReports.ACCEL].last() > p.now - 30_000_000L)
        }
    }

    @Test fun `a channel on another clock is never taken, and not tried again`() {
        for ((shift, words) in listOf(60_000_000L to "ahead", -600_000_000L to "behind", -3_600_000_000_000L to "behind")) {
            val p = Play(channelShift = shift).run(3.0)
            assertEquals(listOf(DirectReports.FAIL), p.said)
            assertTrue(p.session.why!!, p.session.why!!.contains(words) && p.session.why!!.contains("(not elapsedRealtime)"))
            assertTrue(p.session.structural)
            assertEquals(emptyList<String>(), p.calls)
            assertTrue(p.listenerOn)
            // The listener fed the dust throughout, every sample.
            for (k in 0..1) assertEquals(setOf(20_000_000L), p.steps(k))
        }
    }

    @Test fun `took over but DustMotion turned all its samples down - silent to the watch, the listener back at once`() {
        // Its times 400 ms behind the clock (within what a read allows): Feed's floor, the listener's last, turns every
        // one of its samples down after the hand-over.
        val p = Play(channelShift = -400_000_000L).run(3.0)
        assertEquals(listOf(DirectReports.PASS, DirectReports.FAIL), p.said)
        assertTrue(p.session.why!!, p.session.why!!.contains("silent"))
        assertTrue(p.session.structural)
        assertEquals(listOf("takeOver", "letGo", "handBack", "listenAgain"), p.calls)
        assertTrue(p.listenerOn)
        assertEquals(DirectReports.LISTENER, p.feed.from)
        assertTrue(p.at(DirectReports.FAIL)!! - p.at(DirectReports.PASS)!! in 250_000_000L..270_000_000L)
        // The listener's samples on after it, past the last given.
        assertTrue(p.given[DirectReports.ACCEL].last() > p.now - 30_000_000L)
        assertTrue(p.given.all { g -> g.zipWithNext().all { (x, y) -> y > x } })
    }

    @Test fun `values not the listener's - another bias, other units - the listener stays, tried again later`() {
        val bias = Play(gyroBias = 0.05f).run(3.0)
        assertEquals(listOf(DirectReports.FAIL), bias.said)
        assertTrue(bias.session.why!!, bias.session.why!!.contains("the gyroscope's z through it 0.050 rad/s from the listener's over the same second"))
        assertFalse(bias.session.structural)
        assertTrue(bias.calls.isEmpty()); assertTrue(bias.listenerOn)
        for (k in 0..1) assertEquals(setOf(20_000_000L), bias.steps(k))
        val units = Play(accelScale = 1f / 9.81f).run(3.0)
        assertEquals(listOf(DirectReports.FAIL), units.said)
        assertTrue(units.session.why!!, units.session.why!!.contains("the accelerometer's y through it"))
        // Within its bounds: taken.
        assertEquals(listOf(DirectReports.PASS), Play(gyroBias = 0.015f).run(2.0).said)
        // A brisk move, each sampling it at other moments: the same means, taken.
        val shake: (Double, FloatArray, FloatArray) -> Unit = { s, a, w ->
            held.copyInto(a)
            a[0] += (3.0 * sin(2 * PI * 2.5 * s)).toFloat(); a[1] += (2.0 * sin(2 * PI * 1.3 * s + 1.0)).toFloat()
            w[0] = (0.5 * sin(2 * PI * 1.1 * s)).toFloat(); w[1] = (0.4 * cos(2 * PI * 0.9 * s)).toFloat(); w[2] = (1.0 * sin(2 * PI * 1.5 * s)).toFloat()
        }
        val brisk = Play(move = shake, channelPhase = 10_000_000L).run(2.0)
        assertEquals(brisk.session.why, listOf(DirectReports.PASS), brisk.said)
    }

    @Test fun `not at the dust's own 50 a second, or faster once it fed - the listener`() {
        // The hardware ran both at 100 for the channel (the listener, beside it, at 100 too).
        val both = Play().apply { channelHz = 100f; listenerHz = 100f }.run(3.0)
        assertEquals(listOf(DirectReports.FAIL), both.said)
        assertTrue(both.session.why!!, both.session.why!!.contains("not the 50 the dust is made for"))
        assertFalse(both.session.structural); assertTrue(both.listenerOn)
        // Taken at 50, then at 100 a second once the listener went: given back.
        val faster = Play().run(1.5)
        assertNotNull(faster.at(DirectReports.PASS))
        faster.channelHz = 100f
        faster.run(2.0)
        assertEquals(listOf(DirectReports.PASS, DirectReports.FAIL), faster.said)
        assertTrue(faster.session.why!!, faster.session.why!!.contains("it took over at"))
        assertFalse(faster.session.structural)
        assertEquals(listOf("takeOver", "letGo", "handBack", "listenAgain"), faster.calls)
        assertEquals(DirectReports.LISTENER, faster.feed.from)
        assertTrue(faster.given[DirectReports.ACCEL].last() > faster.now - 30_000_000L)
    }

    /** From [u] = 0 s the phone tips back 30 degrees about its y over half a second and stays so. */
    private fun tipped(u: Double, a: FloatArray, w: FloatArray) {
        val th = Math.toRadians(30.0); val d = 0.5
        val f = (u / d).coerceIn(0.0, 1.0)
        val ph = th * 0.5 * (1 - cos(PI * f))
        val pace = if (u in 0.0..d) th * 0.5 * PI / d * sin(PI * f) else 0.0
        a[0] = (-held[2] * sin(ph)).toFloat(); a[1] = held[1]; a[2] = (held[2] * cos(ph)).toFloat()
        w[0] = 0f; w[1] = pace.toFloat(); w[2] = 0f
    }

    @Test fun `the scene stalled while the channel fed and the phone tipped meanwhile - no push read from it`() {
        val tipAt = 22.0
        val tip: (Double, FloatArray, FloatArray) -> Unit = { s, a, w -> tipped(s - tipAt, a, w) }
        // Fed by the channel from about 1.1 s; the scene's thread stalls 1.5 s from 2 s, the phone tips in it.
        val p = Play(move = tip).run(2.0).run(1.5, polls = false)
        p.lagMax = 0f
        p.run(3.0)
        assertEquals(listOf(DirectReports.PASS), p.said)
        assertEquals(listOf("takeOver", "letGo", "resync"), p.calls)
        assertTrue("lag ${p.lagMax}", p.lagMax < 2f)
        // Without the resync the gravity of before the gap reads the tip as a push: the lurch it saves.
        val q = Play(move = tip, resyncs = false).run(2.0).run(1.5, polls = false)
        q.lagMax = 0f
        q.run(3.0)
        assertTrue("lag ${q.lagMax}", q.lagMax > 30f)
        // The listener, which saw every sample through such a stall, read no push either.
        val l = DustMotion(); val a = FloatArray(3); val w = FloatArray(3)
        var t = 0.0; var lag = 0f
        while (t < 4.0) { tip(tipAt - 1.0 + t, a, w); val ns = 1_000_000_000L + (t * 1e9).toLong(); l.gyro(w[0], w[1], w[2], ns); l.accel(a[0], a[1], a[2], ns); t += 0.02; if (t > 1.0) { l.step(0.02f); lag = max(lag, hypot(l.lagX, l.lagY)) } }
        assertTrue("lag $lag", lag < 2f)
    }

    @Test fun `a failure before the pass's letting go ran - the listener kept, nothing let go or taken twice`() {
        val p = Play()
        p.hold = true
        p.run(1.5)
        assertNotNull(p.at(DirectReports.PASS))
        // The channel goes silent before the sensors' thread ran the pass's post.
        p.channelHz = 0f
        p.run(0.5)
        assertEquals(listOf(DirectReports.PASS, DirectReports.FAIL), p.said)
        assertEquals(listOf("takeOver", "handBack"), p.calls)
        p.drain()
        assertEquals(listOf("takeOver", "handBack"), p.calls)
        assertTrue(p.listenerOn)
        p.hold = false
        p.run(1.0)
        assertEquals(DirectReports.LISTENER, p.feed.from)
        assertTrue(p.given[DirectReports.ACCEL].last() > p.now - 30_000_000L)
        assertTrue(p.given.all { g -> g.zipWithNext().all { (x, y) -> y > x } })
    }

    @Test fun `a stall on trial - the trial again from there, not given up for it`() {
        val p = Play().run(0.6).run(1.2, polls = false).run(2.5)
        assertEquals(listOf(DirectReports.PASS), p.said)
        assertTrue(p.at(DirectReports.PASS)!! - p.start > 2_900_000_000L)
        for (k in 0..1) assertEquals(setOf(20_000_000L), p.steps(k))
    }

    @Test fun `a session's poll allocates nothing while it feeds, nor on trial`() {
        val hal = Hal(DirectReports.SLOTS)
        var t0 = 1_000_000_000L
        val s = DirectReports.Session(hal.memory, accel, gyro, t0)
        val host = object : DirectReports.Session.Host {
            override fun give(k: Int, x: Float, y: Float, z: Float, t: Long) = true
            override fun takeOver() = Unit
            override fun handBack() = Unit
            override fun resync() = Unit
        }
        val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val bytesOf = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        repeat(20) { bytesOf.invoke(mx, id) }
        var sample = t0 + 3_000_000L
        fun polls(n: Int) = repeat(n) {
            t0 += 8_333_333L
            while (sample <= t0) {
                hal.gyro(sample, 0f, 0f, 0f); hal.accel(sample, 0f, 8.5f, -4.9f)
                s.listened(DirectReports.GYRO, 0f, 0f, 0f, sample); s.listened(DirectReports.ACCEL, 0f, 8.5f, -4.9f, sample)
                sample += 20_000_000L
            }
            assertTrue(s.poll(t0, host) != DirectReports.FAIL)
        }
        fun bytes(n: Int): Long { val before = bytesOf.invoke(mx, id) as Long; polls(n); return bytesOf.invoke(mx, id) as Long - before }
        // On trial (its first second).
        val trial = bytes(100)
        assertEquals(DirectReports.Session.TRIAL, s.state)
        polls(200)
        assertEquals(DirectReports.Session.FEEDING, s.state)
        polls(20_000)
        val short = bytes(10_000)
        val long = bytes(60_000)
        val perPoll = (long - short) / 50_000.0
        println("dust's direct channel: $perPoll bytes allocated a poll while it feeds, $trial in the trial's first 100")
        assertTrue("allocated $perPoll bytes a poll", perPoll < 0.05)
        assertTrue("allocated $trial bytes on trial", trial < 2_000)
    }
}
