package dev.kb896.aiusage.service

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

    @Test fun `an idle batch is half a minute of readings a second apart`() {
        assertEquals(30, TableCadence.eventsPerBatch(1_000_000, 30_000_000))
    }
}
