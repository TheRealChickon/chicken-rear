package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ONE FRAME OF THE DUST FACE (DustDriver) and when it is on at all: the idle act about every 15 s, what counts as
 * reacting, nothing allocated and next to nothing done on the CPU per picture (the inputs, the pace, the uniforms'
 * arrays, the count for the log); its sensors registered only while it is drawn on a lit back and not held still
 * (DustSensor.Gate, and where the shell throws it). The pace itself (120 where 60 would step): DustPaceTest.
 */
class DustDriverTest {
    @Test fun `the idle act - a breath of current about every 15 s, the first a few seconds in`() {
        val d = DustDriver()
        var t = 0L
        val starts = ArrayList<Double>()
        var was = 0f
        repeat(120 * 60) {
            val inp = d.next(1f / 60f, t); t += 16
            if (inp.gustX > 0.5f && was <= 0.5f) starts += d.time
            was = inp.gustX
            assertTrue(inp.gustX <= DustField.GUST_X + 1e-3f)
        }
        println("dust: breaths at ${starts.map { "%.1f".format(it) }}")
        assertEquals(DustField.GUST_FIRST_S.toDouble(), starts.first(), 0.5)
        for (k in 1 until starts.size) assertEquals(15.0, starts[k] - starts[k - 1], 3.5)
        assertEquals(DustField.GUST_X, DustField.GUST_X * DustDriver.gust(DustField.GUST_S / 2.0), 1e-3f)
        assertEquals(0f, DustDriver.gust(-0.1), 0f); assertEquals(0f, DustDriver.gust(DustField.GUST_S + 0.1), 0f)
    }

    @Test fun `reacting - a finger or the phone's movement, until the motes are back - every refresh for a finger, 60 at rest`() {
        // On the rear's grid: every refresh while quick, every other one otherwise (DustPace).
        val period = FramePace.periodNsOf(FramePace.REAR_HZ)
        assertEquals(2, FramePace.every(DustPace.FRAME_MS, period))
        assertEquals(1, FramePace.every(DustPace.QUICK_MS, period))
        val d = DustDriver()
        var t = 0L
        repeat(60) { d.next(1f / 60f, t); t += 16 }
        assertFalse(d.active); assertFalse(d.quick); assertFalse(d.touched)
        d.finger.down(500f, 300f, t)
        d.next(1f / 60f, t); assertTrue(d.active); assertTrue("a finger: every refresh", d.quick); assertTrue(d.touched)
        d.finger.up(t + 100)
        d.next(1f / 60f, t + 100 + DustFinger.SETTLE_MS - 10); assertTrue("still settling", d.active); assertTrue(d.quick)
        // Back: every other refresh again at once, as before.
        d.next(1f / 60f, t + 100 + DustFinger.SETTLE_MS + 10); assertFalse(d.active); assertFalse(d.touched); assertFalse(d.quick)
        assertEquals(DustMotion.PACE_FAST + DustField.GUST_PACE, DustMotion.PACE_SMOOTH, 1e-4f)
        // At rest no mote moves more than about a pixel and a half between two pictures at 60, even at the top of a
        // breath, over the current's whole period (every 2 s).
        val m = DustField.motes(); val v = FloatArray(2)
        val inp = DustField.Inputs().apply { gustX = DustField.GUST_X; gustY = DustField.GUST_Y }
        var fastest = 0f
        for (s in 0..156 step 2) {
            inp.phases(s.toDouble())
            for (i in 0 until m.count) { DustField.current(m.x0[i], m.y0[i], m.z[i], inp, v); fastest = maxOf(fastest, kotlin.math.hypot(v[0], v[1])) }
        }
        println("dust: the fastest mote at rest ${"%.1f".format(fastest)} px/s, ${"%.2f".format(fastest / 60f)} px a picture at 60")
        assertTrue(fastest / 60f < 1.6f)
    }

    @Test fun `a picture's work on the CPU - nothing allocated, a few microseconds`() {
        val d = DustDriver()
        d.finger.down(400f, 300f, 0L)
        // What the scene's loop does per picture besides the GL calls: the pace, the inputs (in two steps when one
        // comes at 60 while reacting), the two arrays of uniforms, the count for the log.
        val pace = DustPace()
        val tally = DustPace.Count()
        fun direct(n: Int) = java.nio.ByteBuffer.allocateDirect(n * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val inU = direct(4 * DustField.IN_U); val inD = direct(4 * DustField.IN_D)
        var now = 1_000_000_000L
        var k = 0
        fun picture(t: Long): DustField.Inputs {
            val dt = pace.picture(now)
            val n = DustPace.steps(dt, d.active)
            val inp = d.next(dt, t, n)
            DustField.packUpdate(inp, dt / n, n, inU); DustField.packDraw(inp, inD)
            if (tally.picture(d.touched, d.quick, d.active, 16_666_666L, 400_000L, 300_000L, 20_000L, 30_000L)) tally.reset()
            // Every third picture at 60 (two steps), the rest at 120 (one): both of the loop's ways are counted.
            now = pace.next(now, d.quick && ++k % 3 != 0)
            return inp
        }
        // The JVM's count of the bytes this thread made (HotSpot's ThreadMXBean; by reflection: the unit tests compile
        // against android.jar, which has no java.lang.management).
        val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val count = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val bean = object { fun getThreadAllocatedBytes(id: Long) = count.invoke(mx, id) as Long }
        val id = Thread.currentThread().id
        var t = 0L
        // Warm (the JIT), then measured: two runs of different lengths, so what is counted once (the count itself) drops out.
        repeat(20_000) { picture(t); d.finger.move(400f + (it % 50), 300f, t); t += 16 }
        fun bytes(n: Int): Long {
            val before = bean.getThreadAllocatedBytes(id)
            repeat(n) { picture(t); t += 16 }
            return bean.getThreadAllocatedBytes(id) - before
        }
        bytes(1_000)
        val short = bytes(10_000)
        val start = System.nanoTime()
        val long = bytes(60_000)
        val ns = (System.nanoTime() - start) / 60_000
        val perFrame = (long - short) / 50_000.0
        println("dust: a picture's CPU work besides GL $ns ns; allocated $short bytes in 10 000 pictures, $long in 60 000: $perFrame a picture")
        assertTrue("allocated $perFrame bytes a picture", perFrame < 0.05)
        assertTrue(ns < 50_000)
        // The arrays as the shaders read them (DustField.packUpdate's places), for a picture at 60 taken in two steps.
        val inp = d.next(1f / 60f, t, DustPace.steps(1f / 60f, true))
        DustField.packUpdate(inp, 1f / 120f, DustPace.steps(1f / 60f, true), inU); DustField.packDraw(inp, inD)
        assertEquals(inp.wave[3], inU.get(3), 0f)
        assertEquals(2f, inU.get(4 * DustField.WAVES + 1), 0f)
        assertEquals(inp.flow, inU.get(4 * (DustField.WAVES + 1)), 0f)
        assertEquals(inp.push, inU.get(4 * (DustField.WAVES + 3) + 2), 0f)
        assertEquals(inp.lagY, inU.get(4 * (DustField.WAVES + 4) + 3), 0f)
        assertEquals(inp.glow, inD.get(7), 0f)
        assertEquals(0, inU.position()); assertEquals(4 * DustField.IN_U, inU.remaining())
    }

    @Test fun `a sensor sample - nothing allocated, well under a microsecond`() {
        val m = DustMotion()
        val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val count = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        var t = 1_000_000L
        // As the sensors' thread hears them, 50 a second each: a hand's wobble with a turn in it.
        fun samples(n: Int) = repeat(n) {
            val k = (it % 37) / 37f
            m.gyro(0.01f * k, -0.02f * k, 0.3f * k, t); m.accel(0.2f * k, 8.5f + 0.3f * k, -4.9f, t)
            t += 20_000_000L
        }
        samples(50_000)
        fun bytes(n: Int): Long { val before = count.invoke(mx, id) as Long; samples(n); return count.invoke(mx, id) as Long - before }
        bytes(1_000)
        val short = bytes(10_000)
        val start = System.nanoTime()
        val long = bytes(60_000)
        val ns = (System.nanoTime() - start) / 60_000 / 2
        val perSample = (long - short) / 100_000.0
        println("dust: a sensor sample $ns ns on the JVM; $perSample bytes allocated a sample")
        assertTrue("allocated $perSample bytes a sample", perSample < 0.05)
        assertTrue(ns < 1_000)
    }

    // -- when the sensors are on --------------------------------------------------------------------------------------

    @Test fun `the gate - on while drawn and moving, off held still, off at the end, never asked twice`() {
        var on = 0; var off = 0; var answer = true
        val g = DustSensor.Gate({ on++; answer }, { off++ })
        repeat(100) { g.showing(still = false) }
        assertEquals(1, on); assertEquals(0, off); assertTrue(g.listening)
        // Covered by a page of ours (held still): off at once, and once.
        repeat(10) { g.showing(still = true) }
        assertEquals(1, off); assertFalse(g.listening)
        g.showing(still = false); assertEquals(2, on)
        // The show ends (dark, another face, the shell's end): off.
        g.end(); assertEquals(2, off); assertFalse(g.listening)
        g.end(); assertEquals(2, off)
        // Sensors that could not be had are not asked for again every frame.
        answer = false
        val h = DustSensor.Gate({ on++; answer }, { off++ })
        val was = on
        repeat(50) { h.showing(still = false) }
        assertEquals(was + 1, on)
        h.end(); assertEquals(2, off)
    }

    private val rear = File("src/main/java/dev/kb896/aiusage/rear")
    private fun src(name: String) = File(rear, name).readText()

    @Test fun `the gate is thrown only by the dust's own loop, and let go however the show ends`() {
        val engine = src("SceneEngine.kt")
        val play = engine.substringAfter("private fun playDust(").substringBefore("private fun face(")
        assertTrue(play.contains("gate.showing(still = true)"))
        assertTrue(play.contains("gate.showing(still = still)"))
        assertTrue("let go in the finally", play.substringAfter("} finally {").contains("gate.end()"))
        // And the finger it showed, however the show ends (a lift on a dark back never reaches the dust).
        assertTrue(play.substringAfter("} finally {").contains("io.finger.reset()"))
        // The loop ends on a dark back (the show's strength), so a dark back has no sensors and no frames.
        assertTrue(play.contains("val left = control.strength()\n                if (left < 0f) return"))
        val scene = src("ShellScene.kt")
        assertTrue(scene.contains("if (dust) it.dust = SceneEngine.Dust(dustFinger, dustMotion, { dustSensor.start() }, { dustSensor.stop() }, panelHz = panelHz, sensorPoll = { dustSensor.poll() }, sensorSaid = { dustSensor.said() }, sensorAllowed = { dustMoves })"))
        // The sensors' direct channel is read on the scene's thread before each picture's inputs.
        assertTrue(play.indexOf("io.sensorPoll()") in 0 until play.indexOf("driver.next("))
        assertTrue(scene.substringAfter("fun close()").substringBefore("}").contains("dustSensor.stop()"))
        // Nothing else registers them.
        val all = rear.listFiles()!!.filter { it.name.endsWith(".kt") }.filter { it.readText().contains("TYPE_GYROSCOPE") }.map { it.name }
        assertEquals(listOf("DustSensor.kt"), all)
    }

    @Test fun `a finger only shows - the touch does what it did, and the picker takes it from the dust`() {
        val service = src("RearUserService.kt")
        val touch = service.substringAfter("private fun faceTouch(e: RearTouches.Event) {").substringBefore("@Volatile private var faceFingerDown")
        // The dust is told first and the rest is as before.
        assertTrue(touch.trimStart().startsWith("// THE DUST SHOWS THE FINGER"))
        assertTrue(touch.contains("if (scene.dustShowing) scene.dustTouch("))
        val picker = service.substringAfter("private fun openPicker(").substringBefore("/**")
        assertTrue(picker.contains("scene.dustRelease()"))
    }
}
