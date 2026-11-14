package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamedPageTest {

    // The pager with an account connected: clock 0, usage 1, and media 2 while music plays.
    private fun withAccount(name: String, current: Int = 1, pages: Int = 2) =
        NamedPage.resolve(name, current, pages, readoutGone = false, clock = 0, usage = 1, media = 2)

    // With none: clock 0 is home, and media, when there is any, is 1.
    private fun noAccount(name: String, current: Int = 0, pages: Int = 1) =
        NamedPage.resolve(name, current, pages, readoutGone = true, clock = 0, usage = 1, media = 1)

    @Test fun `the pages by their names, an account connected`() {
        assertEquals(0, withAccount("clock"))
        assertEquals(1, withAccount("usage"))
        assertEquals(1, withAccount("home"))
        assertEquals(1, withAccount(" Usage "))
    }

    @Test fun `media is a page only while there is one`() {
        assertNull(withAccount("media", pages = 2))
        assertEquals(2, withAccount("media", pages = 3))
        assertNull(noAccount("media", pages = 1))
        assertEquals(1, noAccount("media", pages = 2))
    }

    @Test fun `with no account there is no usage page, and home is the clock`() {
        assertNull(noAccount("usage"))
        // even with music playing, when page 1 exists: it is the media page, not usage
        assertNull(noAccount("usage", pages = 2))
        assertEquals(0, noAccount("home"))
        assertEquals(0, noAccount("clock"))
    }

    @Test fun `next and prev stop at the ends`() {
        assertEquals(2, withAccount("next", current = 1, pages = 3))
        assertEquals(2, withAccount("next", current = 2, pages = 3))
        assertEquals(0, withAccount("prev", current = 1))
        assertEquals(0, withAccount("prev", current = 0))
    }

    @Test fun `an unknown name, or no pages, is no page`() {
        assertNull(withAccount("settings"))
        assertNull(withAccount("3"))
        assertNull(NamedPage.resolve("clock", 0, 0, false, 0, 1, 2))
    }
}
