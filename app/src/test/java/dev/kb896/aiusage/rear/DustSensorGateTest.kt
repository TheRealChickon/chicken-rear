package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DustSensor.Gate with the setting "Moves with the phone" (PanelConfig.dustMovesWithPhone). */
class DustSensorGateTest {
    private var ons = 0
    private var offs = 0
    private var allowed = true
    private val gate = DustSensor.Gate({ ons++; true }, { offs++ }, { allowed })

    @Test fun onWhileItMovesOffWhileHeld() {
        gate.showing(still = false); gate.showing(still = false)
        assertEquals(1, ons); assertTrue(gate.listening)
        gate.showing(still = true)
        assertEquals(1, offs); assertFalse(gate.listening)
    }

    @Test fun switchedOffTheSensorsGoAtOnce() {
        gate.showing(still = false)
        allowed = false
        gate.showing(still = false)
        assertEquals(1, offs); assertFalse(gate.listening)
        gate.showing(still = false)
        assertEquals("not asked again while off", 1, ons)
    }

    @Test fun switchedOnTheyComeWithTheNextPicture() {
        allowed = false
        gate.showing(still = false)
        assertEquals(0, ons)
        allowed = true
        gate.showing(still = false)
        assertEquals(1, ons); assertTrue(gate.listening)
    }

    @Test fun offFromTheStartNeverAsks() {
        allowed = false
        repeat(5) { gate.showing(still = it % 2 == 0) }
        gate.end()
        assertEquals(0, ons); assertEquals(0, offs)
    }
}
