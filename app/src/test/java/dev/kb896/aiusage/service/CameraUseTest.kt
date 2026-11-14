package dev.kb896.aiusage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUseTest {
    private val start = CameraUse.START_MS
    private val end = CameraUse.END_MS

    @Test
    fun `a camera held past the start debounce is a use, freed past the end debounce it is over`() {
        val u = CameraUse()
        u.unavailable("0", now = 1_000)
        assertEquals(1_000 + start, u.dueAt())
        assertNull(u.settle(1_000 + start - 1))
        assertFalse(u.inUse)
        assertEquals(CameraUse.Edge.START, u.settle(1_000 + start))
        assertTrue(u.inUse)
        assertNull(u.settle(1_000 + start + 10)) // said once
        u.available("0", now = 5_000)
        assertEquals(5_000 + end, u.dueAt())
        assertNull(u.settle(5_000 + end - 1))
        assertTrue(u.inUse)
        assertEquals(CameraUse.Edge.END, u.settle(5_000 + end))
        assertFalse(u.inUse)
        assertEquals(-1L, u.dueAt())
    }

    @Test
    fun `face unlock's glance is no use at all`() {
        val u = CameraUse()
        u.unavailable("1", now = 0)
        u.available("1", now = start - 200)
        assertEquals(-1L, u.dueAt())
        assertNull(u.settle(start * 3))
        assertFalse(u.inUse)
    }

    @Test
    fun `switching lens or mode inside the end debounce is the same use`() {
        val u = CameraUse()
        u.unavailable("0", 0)
        assertEquals(CameraUse.Edge.START, u.settle(start))
        u.available("0", 10_000)
        u.unavailable("2", 10_400) // the next lens, opened a moment later
        assertEquals(-1L, u.dueAt())
        assertNull(u.settle(10_000 + end * 2))
        assertTrue(u.inUse)
    }

    @Test
    fun `two cameras at once end only when both are free`() {
        val u = CameraUse()
        u.unavailable("0", 0)
        u.unavailable("1", 100)
        assertEquals(CameraUse.Edge.START, u.settle(start)) // counted from the first
        u.available("0", 5_000)
        assertTrue(u.held)
        assertNull(u.settle(5_000 + end * 2))
        u.available("1", 20_000)
        assertNull(u.settle(20_000 + end - 1))
        assertEquals(CameraUse.Edge.END, u.settle(20_000 + end))
    }

    @Test
    fun `a free camera reported free again changes nothing`() {
        val u = CameraUse()
        u.available("0", 0) // what registering reports for every camera
        u.available("1", 0)
        assertEquals(-1L, u.dueAt())
        assertFalse(u.held)
    }

    @Test
    fun `reset forgets everything`() {
        val u = CameraUse()
        u.unavailable("0", 0)
        u.settle(start)
        u.reset()
        assertFalse(u.inUse)
        assertFalse(u.held)
        assertEquals(-1L, u.dueAt())
    }
}
