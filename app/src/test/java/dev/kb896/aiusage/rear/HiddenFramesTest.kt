package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.HiddenFrames.Layer
import dev.kb896.aiusage.rear.RearNav.Input
import dev.kb896.aiusage.rear.RearNav.Live
import dev.kb896.aiusage.rear.RearNav.Row
import dev.kb896.aiusage.rear.RearNav.Seen
import dev.kb896.aiusage.rear.RearNav.State
import dev.kb896.aiusage.rear.RearNav.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No pictures for nobody (HiddenFrames): over every state the navigation can reach and every way the layers can stand
 * (each of the seven in its place or not), the scene is held exactly while an opaque page of ours covers it in its place,
 * and the live page is drawn whenever any of it can be seen.
 */
class HiddenFramesTest {
    private fun bits(vararg k: Live) = k.fold(0) { a, l -> a or (1 shl l.ordinal) }

    /** Every state that can be reached from a dark back with nothing live (as RearNavTest has them). */
    private val reachable: List<State> by lazy {
        val seen = LinkedHashSet<State>(); val todo = ArrayDeque<State>()
        seen += State(); todo += State()
        while (todo.isNotEmpty()) {
            val s = todo.removeFirst()
            for (i in RearNav.INPUTS) { val n = RearNav.next(s, i).state; if (seen.add(n)) todo += n }
        }
        seen.toList()
    }

    /** Every way the seven layers can stand: a bit each, in its place or not. */
    private val placings: List<(Layer) -> Boolean> = (0 until (1 shl Layer.values().size)).map { m -> { l: Layer -> m and (1 shl l.ordinal) != 0 } }
    private fun only(vararg l: Layer): (Layer) -> Boolean = { it in l }
    private val none: (Layer) -> Boolean = { false }
    private val all: (Layer) -> Boolean = { true }

    private fun cardLive(s: State) = s.live == Live.NAVIGATION || s.live == Live.TIMER || s.live == Live.CALL || s.live == Live.MUSIC
    /** The live page's own layer in its place: a card's, or the charging page's. */
    private fun liveIn(s: State, p: (Layer) -> Boolean) = cardLive(s) && p(Layer.LIVE) || s.live == Live.CHARGE && p(Layer.CHARGE)

    @Test fun `every kind of seen is reached, so the table below speaks of all of them`() {
        val kinds = reachable.map { RearNav.seen(it) }.toSet()
        assertEquals(Seen.values().toSet(), kinds)
    }

    @Test fun `THE FACE IS NEVER HELD WHILE IT CAN BE SEEN - on the moon and in the picker nothing a layer says holds it`() {
        for (s in reachable) {
            val seen = RearNav.seen(s)
            if (seen != Seen.MOON && seen != Seen.PICKER) continue
            for (p in placings) {
                assertFalse("$s ($seen) held", HiddenFrames.sceneStill(dimStill = false, musicUp = false, s = s, inPlace = p))
            }
        }
    }

    @Test fun `the scene is held exactly while what is seen is an opaque page of ours standing in its place, or the live page stands under it`() {
        var held = 0; var moving = 0
        for (s in reachable) for (p in placings) {
            val seen = RearNav.seen(s)
            val coverIn = when (seen) {
                Seen.POP -> p(Layer.POP)
                Seen.SHADE -> p(Layer.SHADE)
                Seen.ARRIVAL -> p(Layer.ARRIVAL)
                Seen.NOTES -> p(Layer.NOTES)
                Seen.USAGE -> p(Layer.USAGE)
                Seen.LIVE -> liveIn(s, p)
                else -> false
            }
            // The live page in its place under whatever is over it covers the scene too.
            val liveUnder = s.liveShown && liveIn(s, p)
            val want = s.lit && s.ours && (coverIn || liveUnder)
            val got = HiddenFrames.sceneStill(false, false, s, p)
            assertEquals("$s ($seen)", want, got)
            if (got) held++ else moving++
        }
        assertTrue("both happen: $held held, $moving moving", held > 0 && moving > 0)
    }

    @Test fun `as before, a dim still's posture and the music page hold it whatever else is so`() {
        for (s in reachable) {
            assertTrue(HiddenFrames.sceneStill(dimStill = true, musicUp = false, s = s, inPlace = none))
            assertTrue(HiddenFrames.sceneStill(dimStill = false, musicUp = true, s = s, inPlace = none))
        }
    }

    @Test fun `nothing in its place holds nothing - a page on its way in or out, or under a finger, leaves the scene moving`() {
        for (s in reachable) assertFalse("$s", HiddenFrames.sceneStill(false, false, s, none))
    }

    @Test fun `under the notifications' pile the scene is held while the pile stands settled, or the live page stands in its place under it`() {
        val shade = reachable.filter { RearNav.seen(it) == Seen.SHADE && it.lit && it.ours }
        assertTrue(shade.isNotEmpty())
        for (s in shade) {
            assertEquals(Layer.SHADE, HiddenFrames.cover(s))
            assertTrue("settled: $s", HiddenFrames.sceneStill(false, false, s, only(Layer.SHADE)))
            // Moving, or a finger on it (not in its place): only the live page in its place under it holds the scene.
            assertEquals("$s", s.liveShown && (cardLive(s) || s.live == Live.CHARGE), HiddenFrames.sceneStill(false, false, s, all.let { a -> { l: Layer -> l != Layer.SHADE && a(l) } }))
            assertFalse("$s", HiddenFrames.sceneStill(false, false, s, only(Layer.POP, Layer.ARRIVAL, Layer.NOTES, Layer.USAGE)))
        }
    }

    @Test fun `the pile settled holds nothing once the navigation has shut it, nor on a dark back or over another's app`() {
        for (s in reachable.filter { RearNav.seen(it) != Seen.SHADE }) {
            // Its word alone (left standing a moment after the table shut it) holds nothing.
            assertFalse("$s", HiddenFrames.sceneStill(false, false, s, only(Layer.SHADE)))
        }
    }

    @Test fun `the whole charging page in its place holds the scene - on its way, under a finger or leaving it does not`() {
        val charging = State(lit = true, row = Row.LIVE, lives = bits(Live.CHARGE))
        assertEquals(Seen.LIVE, RearNav.seen(charging))
        assertEquals(Layer.CHARGE, HiddenFrames.cover(charging))
        assertTrue(HiddenFrames.sceneStill(false, false, charging, only(Layer.CHARGE)))
        assertFalse("not in its place", HiddenFrames.sceneStill(false, false, charging, only(Layer.POP, Layer.ARRIVAL, Layer.NOTES, Layer.USAGE, Layer.LIVE, Layer.SHADE)))
        // A card's word is not the charging page's.
        assertFalse(HiddenFrames.sceneStill(false, false, charging, only(Layer.LIVE)))
        // Sent away by the navigation: the moon moves the moment it is, whatever the page's layer still says.
        val sent = RearNav.next(charging, Input.Stroke(Way.RIGHT)).state
        assertFalse(HiddenFrames.sceneStill(false, false, sent, only(Layer.CHARGE)))
        // Usage over it, on its way in: the charging page in its place under it holds the scene.
        val usage = RearNav.next(charging, Input.Stroke(Way.LEFT)).state
        assertEquals(Seen.USAGE, RearNav.seen(usage))
        assertTrue(HiddenFrames.sceneStill(false, false, usage, only(Layer.CHARGE)))
        // A dark back: nothing is held by it (the scene does not run on a dark back).
        assertFalse(HiddenFrames.sceneStill(false, false, charging.copy(lit = false), only(Layer.CHARGE)))
    }

    @Test fun `the charging page's word holds nothing where it is not the live page`() {
        for (s in reachable) {
            if (s.live == Live.CHARGE && s.liveShown) continue
            val seen = RearNav.seen(s)
            val p = only(Layer.CHARGE)
            assertFalse("$s ($seen)", HiddenFrames.sceneStill(false, false, s, p))
        }
    }

    @Test fun `THE NAVIGATION LEADS - a live page it has sent away holds nothing, even while its layer still stands`() {
        val timer = State(lit = true, row = Row.LIVE, lives = bits(Live.TIMER))
        assertTrue(HiddenFrames.sceneStill(false, false, timer, only(Layer.LIVE)))
        val sent = RearNav.next(timer, Input.Stroke(Way.RIGHT)).state
        assertEquals(Seen.MOON, RearNav.seen(sent))
        assertFalse("the moon moves the moment the timer is sent away", HiddenFrames.sceneStill(false, false, sent, only(Layer.LIVE)))
        // The usage page sent back over the timer: the timer's page is in its place under it, so the scene stays held.
        val usage = RearNav.next(timer, Input.Stroke(Way.LEFT)).state
        assertEquals(Seen.USAGE, RearNav.seen(usage))
        assertTrue(HiddenFrames.sceneStill(false, false, usage, only(Layer.LIVE)))
        val back = RearNav.next(usage, Input.Stroke(Way.RIGHT)).state
        assertEquals(Seen.LIVE, RearNav.seen(back))
        assertTrue(HiddenFrames.sceneStill(false, false, back, only(Layer.LIVE)))
    }

    @Test fun `the live page is drawn whenever any of it can be seen`() {
        for (s in reachable) for (p in placings) {
            val seen = RearNav.seen(s)
            val over = when (seen) { Seen.USAGE -> Layer.USAGE; Seen.NOTES -> Layer.NOTES; Seen.ARRIVAL -> Layer.ARRIVAL; else -> null }
            val want = !(s.liveShown && cardLive(s) && over != null && p(over))
            assertEquals("$s ($seen)", want, HiddenFrames.liveDrawn(s, p))
        }
    }

    @Test fun `the live page itself, and the live page under a pop-up or the pile, is always drawn`() {
        for (s in reachable) {
            val seen = RearNav.seen(s)
            if (seen == Seen.LIVE || seen == Seen.POP || seen == Seen.SHADE || seen == Seen.MOON || seen == Seen.PICKER) {
                assertTrue("$s ($seen)", HiddenFrames.liveDrawn(s, all))
            }
        }
    }

    @Test fun `usage over the timer - drawn again the moment usage leaves its place, or is sent away`() {
        val usage = State(lit = true, row = Row.USAGE, lives = bits(Live.TIMER))
        assertFalse("under usage in its place: no pictures", HiddenFrames.liveDrawn(usage, only(Layer.USAGE, Layer.LIVE)))
        assertTrue("usage under a finger, or on its way: drawn", HiddenFrames.liveDrawn(usage, only(Layer.LIVE)))
        val gone = RearNav.next(usage, Input.Stroke(Way.RIGHT)).state
        assertTrue("sent away by the navigation, its layer still in place: drawn", HiddenFrames.liveDrawn(gone, only(Layer.USAGE, Layer.LIVE)))
    }

    @Test fun `a layer is in its place only when up, all the way in, staying, and no finger has it`() {
        assertTrue(HiddenFrames.inPlace(isUp = true, at = 1f, goal = 1f, peeking = false))
        assertFalse("on its way in", HiddenFrames.inPlace(true, 0.99f, 1f, false))
        assertFalse("starting to leave", HiddenFrames.inPlace(true, 1f, 0f, false))
        assertFalse("sent away", HiddenFrames.inPlace(false, 1f, 1f, false))
        assertFalse("under a finger", HiddenFrames.inPlace(true, 1f, 1f, true))
        assertFalse(HiddenFrames.inPlace(false, 0f, 0f, false))
    }

    @Test fun `a covered page's tick looks now and then, never sooner than its own pace`() {
        assertEquals(HiddenFrames.HIDDEN_LOOK_MS, HiddenFrames.hiddenDelay(16L))
        assertEquals(HiddenFrames.HIDDEN_LOOK_MS, HiddenFrames.hiddenDelay(33L))
        assertEquals(1_000L, HiddenFrames.hiddenDelay(1_000L))
    }

    // -- the shell: every opaque page tells when it is in its place, and the scene's hold is the one rule -------------

    private val rear = File("src/main/java/dev/kb896/aiusage/rear")

    @Test fun `every page and opaque card the shell makes says when it is in its place`() {
        val service = File(rear, "RearUserService.kt").readText()
        val pages = Regex("""ShellPage\(\s*(?://[^\n]*\n\s*)*"([a-z]+)"""").findAll(service).map { it.groupValues[1] }.toList()
        assertTrue("the pages are found: $pages", pages.containsAll(listOf("usage", "notes", "arrival")))
        for (m in Regex("""ShellPage\(\s*(?://[^\n]*\n\s*)*"([a-z]+)"[\s\S]*?\n        \)""").findAll(service)) {
            assertTrue("${m.groupValues[1]} tells its place", m.value.contains("onPlace = { coverMoved() }"))
        }
        val cards = Regex("""ShellCard\("([a-z]+)"[^\n]*opaque = true[^\n]*""").findAll(service).toList()
        assertEquals(setOf("music", "timer", "call", "navigation"), cards.map { it.groupValues[1] }.toSet())
        for (c in cards) assertTrue("${c.groupValues[1]} tells its place", c.value.contains("onPlace = { coverMoved() }"))
        assertTrue("the pop-up tells its place", service.contains("onPopPlace = { popInPlace = it; coverMoved() }"))
        // The charging page and the pile, each read by its own word (the charging page is made lazily: never for this).
        val charge = service.substring(service.indexOf("ShellCharge(")).substringBefore("\n    }")
        assertTrue("the charging page tells its place", charge.contains("onPlace = { chargeInPlace = it; coverMoved() }"))
        assertTrue("the pile tells it stands settled", service.contains("onPileCover = { pileInPlace = it; coverMoved() }"))
        val inPlace = service.substring(service.indexOf("private fun layerInPlace(")).substringBefore("\n    }")
        assertTrue(inPlace.contains("HiddenFrames.Layer.CHARGE -> chargeInPlace") && inPlace.contains("HiddenFrames.Layer.SHADE -> pileInPlace"))
        // The pile's word comes from its sheet: told out before any frame that moves is posted, and as it ends.
        assertTrue(File(rear, "ShellNotices.kt").readText().contains("made.onCover = onPileCover"))
        val overlay = File(rear, "ShellOverlay.kt").readText()
        val post = overlay.indexOf("surface.unlockCanvasAndPost(canvas)")
        assertTrue("released before the post", overlay.lastIndexOf("if (covering && !covers) { covering = false; cover(r, false) }", post) in (post - 200) until post)
        val end = overlay.substring(overlay.indexOf("fun end()")).substringBefore("\n    }")
        assertTrue("released before its layers are hidden", end.indexOf("tellCover(false)") in 0 until end.indexOf("hide(r.blocker, r.sheet)"))
        // Told only a change, and what the word throws never stops a post or the hide.
        assertTrue(overlay.contains("if (gen == r.gen && told != covers) { told = covers; tellCover(covers) }"))
        assertTrue(overlay.contains("runCatching { onCover?.invoke(covers) }"))
        // The charging page: told out of its place before the frame's alpha or place is set, into it after.
        val chargeSrc = File(rear, "ShellCharge.kt").readText()
        val out = chargeSrc.indexOf("if (!whole) placedNow(false)")
        assertTrue(out > 0 && out < chargeSrc.indexOf("tx.setAlpha(sc, alpha)") && out < chargeSrc.indexOf("RearShape.place(tx, sc, rear, x, y)"))
        assertTrue(chargeSrc.indexOf("if (whole) placedNow(true)") > chargeSrc.indexOf("RearShape.place(tx, sc, rear, x, y)"))
    }

    @Test fun `the scene's hold is set by the one rule, wherever it is set`() {
        val service = File(rear, "RearUserService.kt").readText()
        val sets = Regex("""scene\.still = ([^\n]+)""").findAll(service).map { it.groupValues[1].trim() }.toList()
        assertTrue("set somewhere: $sets", sets.isNotEmpty())
        for (v in sets) assertTrue("set by stillNow: $v", v.startsWith("stillNow("))
    }
}

/** The stay loop's own truth read of the back's focus (FocusReread). */
class FocusRereadTest {
    @Test fun `while the log is followed it reads every ten seconds, not every tick`() {
        val since = 1_000L
        val now = since + FocusReread.FAST_FOR_MS + 5_000L
        assertFalse(FocusReread.due(now, lastReadAt = now - 2_000L, logSince = since))
        assertFalse(FocusReread.due(now, now - FocusReread.SLOW_MS + 1L, since))
        assertTrue(FocusReread.due(now, now - FocusReread.SLOW_MS, since))
    }

    @Test fun `with no log followed, or just after it began again, every tick reads as before`() {
        assertTrue("no reader", FocusReread.due(50_000L, lastReadAt = 49_000L, logSince = 0L))
        assertTrue("a reader of a moment ago", FocusReread.due(50_000L, lastReadAt = 49_000L, logSince = 45_000L))
        assertTrue("never read", FocusReread.due(50_000L, lastReadAt = 0L, logSince = 1_000L))
    }
}

/** The charging page's word that it stands whole in its place (ShellCharge.wholeInPlace), frame by frame of its slide. */
class ChargeInPlaceTest {
    private val w = 976f; private val h = 596f

    private fun whole(s: PageSlide, moving: Boolean, alpha: Float = 1f, stays: Boolean = true, leaving: Boolean = false, draining: Boolean = false, cut: Boolean = false) =
        ShellCharge.wholeInPlace(stays, leaving, draining, cut, alpha, moving, s.x(w), s.y(h), s.held, s.goal)

    @Test fun `in its place at full strength the whole page is whole - the small one, a fade, a drain, a cut never are`() {
        val s = PageSlide(); s.inPlace()
        val moving = s.step(1_000L)
        assertFalse(moving)
        assertTrue(whole(s, moving))
        assertFalse("the small one over an app", whole(s, moving, stays = false))
        assertFalse("fading in", whole(s, moving, alpha = 0.999f))
        assertFalse("draining", whole(s, moving, draining = true))
        assertFalse("cut short", whole(s, moving, cut = true))
        assertFalse("sent away", whole(s, moving, leaving = true))
    }

    @Test fun `sent away it is out of its place from the frame it is sent, before it has moved a pixel`() {
        for (edge in PageSlide.Edge.values()) {
            val s = PageSlide(); s.inPlace()
            s.leaveTo(edge)
            var now = 5_000L
            var moving = s.step(now)
            assertEquals(0f, s.x(w), 0f); assertEquals(0f, s.y(h), 0f)
            assertFalse("$edge: the first frame of its way out, not moved yet", whole(s, moving))
            while (moving) { now += 8; moving = s.step(now); assertFalse("$edge at $now", whole(s, moving)) }
            assertTrue(s.gone)
        }
    }

    @Test fun `under a finger it is out of its place before it moves, and whole again only once it has slid home`() {
        val s = PageSlide(); s.inPlace()
        s.peek(1f, PageSlide.Edge.RIGHT)
        assertFalse("a finger on it, not moved yet", whole(s, s.step(1_000L)))
        s.peek(0.8f, PageSlide.Edge.RIGHT)
        assertFalse(whole(s, s.step(1_008L)))
        s.unpeek()
        var now = 1_016L
        var moving = s.step(now)
        while (moving) { assertFalse("at $now", whole(s, moving)); now += 8; moving = s.step(now) }
        assertEquals(0f, s.x(w), 0f)
        assertTrue("home again", whole(s, moving))
    }

    @Test fun `coming in by its slide it is whole only once it has arrived`() {
        val s = PageSlide(); s.comeFrom(PageSlide.Edge.RIGHT)
        var now = 1_000L
        var moving = s.step(now)
        while (moving) { assertFalse("at $now", whole(s, moving)); now += 8; moving = s.step(now) }
        assertTrue(whole(s, moving))
    }
}
