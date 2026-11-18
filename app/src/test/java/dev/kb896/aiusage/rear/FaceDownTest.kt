package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDownTest {
    private fun FaceDown.sample(now: Long, x: Float = 0f, z: Float = -9.8f, near: Boolean = true, active: Boolean = false) =
        reading(x, 0f, z, now, near, active)

    private fun FaceDown.settle() {
        for (t in 0L until 2_000L step 50L) assertNull(sample(t))
        assertEquals(true, sample(2_000L))
    }

    @Test fun `the table enters after two seconds of covered quiet stillness`() {
        FaceDown().settle()
    }

    @Test fun `holding the phone overhead with the front far never settles even if perfectly still`() {
        val face = FaceDown()
        for (t in 0L..10_000L step 50L) assertNull(face.sample(t, near = false))
        assertFalse(face.down)
    }

    @Test fun `hand tremor blocks entry even if the front sensor is covered`() {
        val face = FaceDown()
        for (t in 0L..10_000L step 50L) {
            val shake = if (t % 100L == 0L) 0.22f else -0.22f
            assertNull(face.sample(t, x = shake, z = -9.8f + shake))
        }
        assertFalse(face.down)
    }

    @Test fun `current or recent front use restarts the whole entry dwell`() {
        val face = FaceDown()
        for (t in 0L until 2_000L step 50L) assertNull(face.sample(t))
        for (t in 2_000L until 5_000L step 50L) assertNull(face.sample(t, active = true))
        for (t in 5_000L until 7_000L step 50L) assertNull(face.sample(t))
        assertEquals(true, face.sample(7_000L))
    }

    @Test fun `a lift leaves immediately whether the phone stays flat or tilts`() {
        FaceDown().apply {
            settle()
            assertEquals(false, conditions(near = false, frontActive = false, now = 2_010L))
            assertFalse(down)
        }
        FaceDown().apply {
            settle()
            assertEquals(false, sample(2_050L, z = -6f))
            assertFalse(down)
        }
        FaceDown().apply {
            settle()
            assertEquals(false, sample(2_050L, z = -13f))
            assertFalse(down)
        }
    }

    @Test fun `a front touch removes the dim immediately without waiting for another acceleration sample`() {
        val face = FaceDown()
        face.settle()
        assertEquals(false, face.conditions(near = true, frontActive = true, now = 2_010L))
        assertFalse(face.down)
    }

    @Test fun `tilt alternating around the entry threshold never settles`() {
        val face = FaceDown()
        for (t in 0L..10_000L step 50L) {
            val z = if (t % 100L == 0L) -8.55f else -8.65f
            assertNull(face.sample(t, z = z))
        }
        assertFalse(face.down)
    }

    @Test fun `tilt jitter stays down after entry and near far jitter cannot reenter after a lift`() {
        val face = FaceDown()
        face.settle()
        for (t in 2_050L..3_000L step 50L) {
            val z = if (t % 100L == 0L) -8.55f else -8.65f
            assertNull(face.sample(t, z = z))
            assertTrue(face.down)
        }
        assertEquals(false, face.sample(3_050L, near = false))
        for (t in 3_100L..10_000L step 50L) assertNull(face.sample(t, near = t % 100L == 0L))
        assertFalse(face.down)
    }

    @Test fun `a lift requires the full cooldown and a fresh settling run before the next entry`() {
        val face = FaceDown()
        face.settle()
        assertEquals(false, face.sample(2_050L, near = false))
        val enterAgain = 2_050L + FaceDown.REENTRY_MS + FaceDown.ENTER_MS
        for (t in 2_100L until enterAgain step 50L) assertNull(face.sample(t))
        assertEquals(true, face.sample(enterAgain))
    }

    @Test fun `missing or sparse readings cannot count as stillness`() {
        val face = FaceDown()
        for (t in 0L..6_000L step 1_000L) assertNull(face.sample(t))
        assertFalse(face.down)
        assertTrue(FaceDown.near(0f, 5f))
        assertFalse(FaceDown.near(5f, 5f))
        assertFalse(FaceDown.near(3f, 10f))
        assertFalse(FaceDown.near(Float.NaN, 5f))
    }
}
