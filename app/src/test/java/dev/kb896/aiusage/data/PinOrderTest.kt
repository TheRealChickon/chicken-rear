package dev.kb896.aiusage.data

import dev.kb896.aiusage.ui.PinActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pinned notes' order, put by him on the back and kept by the app (PinOrder). */
class PinOrderTest {
    private fun notes(n: Int) = (0 until n).map { Pin("note $it", 1_000L + it) }
    private fun ids(list: List<Pin>) = list.map { it.at }

    @Test fun `a note carried from any place to any other, with 2 to 12 notes - it is there, the others keep their order`() {
        for (n in 2..12) {
            val list = notes(n)
            for (from in 0 until n) for (to in 0 until n) {
                val out = PinOrder.move(list, from, to)
                assertEquals("$n: $from -> $to", n, out.size)
                assertEquals("$n: $from -> $to is a reordering", list.toSet(), out.toSet())
                assertEquals("$n: $from -> $to lands at $to", list[from], out[to])
                assertEquals("$n: $from -> $to, the others in their order", list - list[from], out - list[from])
                // Carried one place at a time (the carousel's way: each note passed is one step), the same.
                var stepwise = list; var at = from
                while (at != to) { val next = at + if (to > at) 1 else -1; stepwise = PinOrder.move(stepwise, at, next); at = next }
                assertEquals("$n: $from -> $to one place at a time", out, stepwise)
                // And back again: as it was.
                assertEquals(list, PinOrder.move(out, to, from))
            }
        }
    }

    @Test fun `a tap on another swaps the two and nothing else, with 2 to 12 notes`() {
        for (n in 2..12) {
            val list = notes(n)
            for (i in 0 until n) for (j in 0 until n) {
                val out = PinOrder.swap(list, i, j)
                assertEquals(list[i], out[j]); assertEquals(list[j], out[i])
                for (k in 0 until n) if (k != i && k != j) assertEquals("$n: $i<->$j leaves $k", list[k], out[k])
                assertEquals("twice is as it was", list, PinOrder.swap(out, i, j))
            }
        }
    }

    @Test fun `out of range nothing moves`() {
        val list = notes(3)
        assertEquals(list, PinOrder.move(list, -1, 2)); assertEquals(list, PinOrder.move(list, 0, 3))
        assertEquals(list, PinOrder.swap(list, 0, 5)); assertEquals(emptyList<Pin>(), PinOrder.move(emptyList<Pin>(), 0, 0))
    }

    @Test fun `the order told is kept on the list as it is - the same list, it is that order`() {
        for (n in 2..12) {
            val list = notes(n)
            // Another order than the list's: the first last, the rest shuffled.
            val order = ids(list).drop(1).shuffled(java.util.Random(n.toLong())) + list.first().at
            val out = PinOrder.applyPins(list, order)
            assertEquals(order, ids(out))
            assertTrue(PinOrder.agrees(out, order) { it.at })
            assertFalse(order == ids(list) || PinOrder.agrees(list, order) { it.at })
        }
    }

    @Test fun `a note pinned meanwhile stays in front, and the rest are in his order`() {
        for (n in 2..12) {
            val list = notes(n)
            val order = ids(list).reversed()
            // The app's list as it is when the order arrives: a new note was pinned on the phone meanwhile, in front.
            val now = PinActivity.added(list, "new one", 99_999L)
            val out = PinOrder.applyPins(now, order)
            assertEquals("the new note first", "new one", out.first().text)
            assertEquals(order, ids(out.drop(1)))
            assertTrue(PinOrder.agrees(out, order) { it.at })
        }
    }

    @Test fun `a note binned meanwhile is not there, and the rest are in his order`() {
        for (n in 2..12) for (gone in 0 until n) {
            val list = notes(n)
            val order = ids(list).reversed()
            val now = list.filter { it.at != list[gone].at }
            val out = PinOrder.applyPins(now, order)
            assertEquals(n - 1, out.size)
            assertEquals(order.filter { it != list[gone].at }, ids(out))
        }
    }

    @Test fun `pinned and binned meanwhile, both`() {
        val list = notes(6)
        val order = listOf(1005L, 1000L, 1003L, 1001L, 1004L, 1002L)
        val now = PinActivity.added(list.filter { it.at != 1003L }, "newer", 50_000L)
        val out = PinOrder.applyPins(now, order)
        assertEquals(listOf(50_000L, 1005L, 1000L, 1001L, 1004L, 1002L), ids(out))
    }

    @Test fun `the same words pinned again move to the front, as a new pin, his order for the rest`() {
        // PinActivity.added: pinning identical text again moves it to the front.
        val ordered = PinOrder.applyPins(notes(4), listOf(1002L, 1000L, 1003L, 1001L))
        val again = PinActivity.added(ordered, "note 3", 77_000L)
        assertEquals(listOf("note 3", "note 2", "note 0", "note 1"), again.map { it.text })
    }

    @Test fun `names it does not know, and a name said twice, count for nothing`() {
        val list = notes(3)
        // 1001 is not named: it keeps its place (as a note pinned since would); the two named take theirs in its order.
        assertEquals(listOf(1002L, 1001L, 1000L), ids(PinOrder.applyPins(list, listOf(5L, 1002L, 1002L, 1000L, 7L))))
        assertEquals(list, PinOrder.applyPins(list, emptyList()))
        assertEquals(list, PinOrder.applyPins(list, listOf(5L, 6L)))
        assertEquals(emptyList<Pin>(), PinOrder.applyPins(emptyList(), listOf(1L)))
    }

    @Test fun `the order survives the store - a config written and read again has it`() {
        val config = PanelConfig(pins = PinOrder.applyPins(notes(5), listOf(1004L, 1002L, 1000L, 1001L, 1003L)))
        val back = PanelConfig.decode(PanelConfig.encode(config))
        assertEquals(listOf(1004L, 1002L, 1000L, 1001L, 1003L), ids(back.pins))
    }
}
