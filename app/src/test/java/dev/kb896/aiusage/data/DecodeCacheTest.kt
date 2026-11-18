package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The memo behind ConfigStore.load: the same blob is decoded once, any other blob at once. */
class DecodeCacheTest {
    private fun counting(): Pair<DecodeCache, IntArray> {
        val n = IntArray(1)
        return DecodeCache { raw -> n[0]++; PanelConfig.decode(raw) } to n
    }

    private val blobA = PanelConfig.encode(PanelConfig.DEFAULT.copy(pins = listOf(Pin("note one", 1L))))
    private val blobB = PanelConfig.encode(PanelConfig.DEFAULT.copy(pins = listOf(Pin("note two", 2L))))

    @Test fun `the same blob is decoded once and the same config handed out`() {
        val (cache, n) = counting()
        val first = cache.of(blobA)
        repeat(100) { assertSame(first, cache.of(blobA)) }
        assertEquals(1, n[0])
    }

    @Test fun `an equal blob in another String is a hit too`() {
        val (cache, n) = counting()
        val first = cache.of(blobA)
        val copy = String(blobA.toCharArray())
        assertNotSame(blobA, copy)
        assertSame(first, cache.of(copy))
        assertSame(first, cache.of(blobA))
        assertEquals(1, n[0])
    }

    @Test fun `another blob is decoded at once, and going back decodes again`() {
        val (cache, n) = counting()
        val a = cache.of(blobA)
        val b = cache.of(blobB)
        assertEquals("note one", a.pins.single().text)
        assertEquals("note two", b.pins.single().text)
        assertEquals("note one", cache.of(blobA).pins.single().text)
        assertEquals(3, n[0])
    }

    /** What it hands out is exactly what a fresh decode gives, the companion-mode migration and the colours included. */
    @Test fun `the answer equals a fresh decode, old configs included`() {
        val cache = DecodeCache()
        val unstamped = blobA.replace("\"${PanelConfig.REAR_MODE_KEY}\"", "\"notTheRearModeAnyMore\"")
        val broken = "{ not json"
        for (raw in listOf(blobA, blobB, unstamped, broken, "")) {
            assertEquals(PanelConfig.decode(raw), cache.of(raw))
            assertEquals(PanelConfig.decode(raw), cache.of(raw))
        }
    }

    @Test fun `threads asking together never get another blob's config`() {
        val cache = DecodeCache()
        val wantA = PanelConfig.decode(blobA)
        val wantB = PanelConfig.decode(blobB)
        val wrong = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(2_000) { i ->
                    val raw = if ((i + t) % 3 == 0) blobB else blobA
                    val got = cache.of(raw)
                    if (got != (if (raw === blobA) wantA else wantB)) wrong.incrementAndGet()
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(0, wrong.get())
    }

    /**
     * Before and after, on this JVM: a decode per load (what load() did) against the memo. Prints both; holds only
     * that a repeated load allocates (next to) nothing, where a decode is tens of KB.
     */
    @Test fun `a repeated load allocates next to nothing`() {
        fun allocated(): Long = runCatching {
            val mf = Class.forName("java.lang.management.ManagementFactory")
            val mx = mf.getMethod("getThreadMXBean").invoke(null)
            val m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            m.invoke(mx, Thread.currentThread().id) as Long
        }.getOrDefault(-1L)
        for (pinCount in listOf(0, 8)) {
            val pins = (0 until pinCount).map { Pin("A pinned note number $it with some words in it to read on the back", 1_794_600_000_000L + it) }
            val raw = PanelConfig.encode(PanelConfig.DEFAULT.copy(pins = pins))
            val cache = DecodeCache()
            repeat(2_000) { PanelConfig.decode(raw); cache.of(raw) }   // warm-up
            var keep = 0
            val n = 5_000
            val a0 = allocated(); val t0 = System.nanoTime()
            repeat(n) { keep += PanelConfig.decode(raw).pins.size }
            val decodeNs = (System.nanoTime() - t0) / n; val decodeBytes = (allocated() - a0) / n
            val a1 = allocated(); val t1 = System.nanoTime()
            repeat(n * 20) { keep += cache.of(raw).pins.size }
            val cachedNs = (System.nanoTime() - t1) / (n * 20); val cachedBytes = (allocated() - a1) / (n * 20)
            println("BENCH config json ${raw.length} chars, $pinCount pins: decode ${decodeNs / 1000.0} us and $decodeBytes bytes a load; " +
                "cached ${cachedNs} ns and $cachedBytes bytes a load ($keep)")
            if (a0 >= 0) {
                assertTrue("a decode should allocate tens of KB, saw $decodeBytes", decodeBytes > 10_000)
                assertTrue("a cached load should allocate nothing, saw $cachedBytes", cachedBytes < 64)
            }
        }
    }
}
