package dev.kb896.aiusage.service

import dev.kb896.aiusage.rear.FaceDown
import dev.kb896.aiusage.rear.LyingOnBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableCadenceTest {
    private data class S(val name: String, val wakeUp: Boolean, val fifo: Int)

    private fun pick(wakeUp: S, plain: S?) =
        TableCadence.idleSensor(wakeUp, plain, 1_000_000, 30_000_000, { it.wakeUp }, { it.fifo })

    @Test fun `a wake-up sensor whose FIFO holds a whole idle batch is kept for the idle watch`() {
        val w = S("wake", true, 300)
        assertEquals(w, pick(w, S("plain", false, 0)))
    }

    @Test fun `with no FIFO for the wake-up sensor the idle watch reads the non-wake-up one`() {
        val plain = S("plain", false, 0)
        assertEquals(plain, pick(S("wake", true, 0), plain))
        assertEquals(plain, pick(S("wake", true, 29), plain)) // one short of the 30 a batch needs
    }

    @Test fun `with no non-wake-up sensor it keeps the one it has`() {
        val w = S("wake", true, 0)
        assertEquals(w, pick(w, null))
        assertEquals(w, pick(w, S("also wake", true, 0)))
    }

    @Test fun `active lasts until its time, then not`() {
        assertTrue(TableCadence.stillActive(now = 999, activeUntil = 1_000))
        assertFalse(TableCadence.stillActive(now = 1_000, activeUntil = 1_000))
    }

    @Test fun `settled either way the watch listens for the lift, else active until its time, then idle`() {
        assertEquals(TableCadence.Mode.LIFT, TableCadence.mode(now = 5_000, activeUntil = 1_000, settled = true))
        assertEquals(TableCadence.Mode.LIFT, TableCadence.mode(now = 500, activeUntil = 1_000, settled = true))
        assertEquals(TableCadence.Mode.ACTIVE, TableCadence.mode(now = 999, activeUntil = 1_000, settled = false))
        assertEquals(TableCadence.Mode.IDLE, TableCadence.mode(now = 1_000, activeUntil = 1_000, settled = false))
    }

    @Test fun `settled, both screens dark and a lift that wakes nothing (companion mode) is the slow watch, never LIFT`() {
        for (now in listOf(500L, 999L, 1_000L, 5_000L)) {
            assertEquals("at $now", TableCadence.Mode.IDLE,
                TableCadence.mode(now = now, activeUntil = 1_000, settled = true, liftWakes = false, screensDark = true))
        }
    }

    @Test fun `settled with a screen on, or with a lift that wakes the back, it still listens for the lift`() {
        assertEquals(TableCadence.Mode.LIFT, TableCadence.mode(now = 5_000, activeUntil = 1_000, settled = true, liftWakes = false, screensDark = false))
        assertEquals(TableCadence.Mode.LIFT, TableCadence.mode(now = 5_000, activeUntil = 1_000, settled = true, liftWakes = true, screensDark = true))
        assertEquals(TableCadence.Mode.LIFT, TableCadence.mode(now = 5_000, activeUntil = 1_000, settled = true, liftWakes = true, screensDark = false))
    }

    @Test fun `not settled, the screens and the lift change nothing`() {
        for (liftWakes in listOf(true, false)) for (dark in listOf(true, false)) {
            assertEquals(TableCadence.Mode.ACTIVE, TableCadence.mode(now = 999, activeUntil = 1_000, settled = false, liftWakes = liftWakes, screensDark = dark))
            assertEquals(TableCadence.Mode.IDLE, TableCadence.mode(now = 1_000, activeUntil = 1_000, settled = false, liftWakes = liftWakes, screensDark = dark))
        }
    }

    @Test fun `a lift is heard at once unless both screens are dark and it wakes nothing`() {
        assertTrue(TableCadence.liftAtOnce(liftWakes = true, screensDark = true))
        assertTrue(TableCadence.liftAtOnce(liftWakes = true, screensDark = false))
        assertTrue(TableCadence.liftAtOnce(liftWakes = false, screensDark = false))
        assertFalse(TableCadence.liftAtOnce(liftWakes = false, screensDark = true))
    }

    /**
     * What the slow watch's batch holds when he picks the phone up off its back and lays it on its face, both screens
     * dark: half a minute of readings one second apart, delivered at once and read in order by their own times. The
     * lift and the new lie are both found in that one batch, so the face-down lie is told within the batch.
     */
    @Test fun `in one idle batch a lift off its back and a face-down lie are both heard`() {
        val face = FaceDown()
        val back = LyingOnBack()
        // Settled on its back first (as LIFT or ACTIVE found it).
        var t = 0L
        repeat(5) { back.reading(0.02f, 0.01f, 9.81f, t); face.reading(0.02f, 0.01f, 9.81f, t); t += 1_000 }
        assertTrue(back.down)
        val batch = buildList {
            repeat(10) { add(floatArrayOf(0.03f, 0.0f, 9.80f)) } // still on its back
            add(floatArrayOf(3.5f, 6.0f, 6.0f))                 // picked up
            add(floatArrayOf(0.5f, -7.0f, -5.0f))               // turned over
            repeat(18) { add(floatArrayOf(0.05f, 0.02f, -9.79f)) } // on its face
        }
        assertEquals(30, batch.size) // one IDLE batch: 30 readings a second apart
        var lifted = -1L
        var faceDown = -1L
        for (v in batch) {
            if (back.reading(v[0], v[1], v[2], t) == false) lifted = t
            if (face.reading(v[0], v[1], v[2], t) == true) faceDown = t
            t += 1_000
        }
        assertTrue("the lift is heard", lifted > 0)
        assertTrue("the face-down lie is heard after it", faceDown > lifted)
        assertTrue("within the batch", faceDown - lifted < 30_000)
        assertFalse(back.down)
        assertTrue(face.down)
    }

    @Test fun `an idle batch is half a minute of readings a second apart`() {
        assertEquals(30, TableCadence.eventsPerBatch(1_000_000, 30_000_000))
    }
}
