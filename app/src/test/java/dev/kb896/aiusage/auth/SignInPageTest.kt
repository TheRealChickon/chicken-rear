package dev.kb896.aiusage.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page a browser shows when a sign-in has come back: few words, the way back, nothing that can run or load. */
class SignInPageTest {
    private val page = SignInPage("Chicken Rear (dev)", "ChatGPT", "dev.chicken.rear.dev://signin")

    @Test fun `signed in - what, and the way back`() {
        val html = page.signedIn()
        assertTrue(html.contains("<h1>Signed in</h1>"))
        assertTrue(html.contains("<p>ChatGPT is connected. Go back to Chicken Rear (dev).</p>"))
        assertTrue(html.contains("""<a href="dev.chicken.rear.dev://signin">Back to Chicken Rear (dev)</a>"""))
    }

    @Test fun `failed - the cause, and what to do`() {
        val html = page.failed("The browser's answer came, but ChatGPT did not accept its code (HTTP 400 (invalid_grant)).")
        assertTrue(html.contains("<h1>Sign-in failed</h1>"))
        assertTrue(html.contains("did not accept its code (HTTP 400 (invalid_grant)). Go back to Chicken Rear (dev) and sign in again.</p>"))
        assertFalse(html.contains("Signed in"))
    }

    @Test fun `light or dark as the phone is, and no colour of its own`() {
        val html = page.signedIn()
        assertTrue(html.contains("""<meta name="color-scheme" content="light dark">"""))
        assertTrue(html.contains("@media(prefers-color-scheme:dark)"))
        // Greys only: every colour in the page has its three parts equal or nearly so.
        val colours = Regex("#([0-9a-f]{3,6})\\b").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(colours.size >= 6)
        for (c in colours) {
            val full = if (c.length == 3) c.map { "$it$it" }.joinToString("") else c
            val parts = full.chunked(2).map { it.toInt(16) }
            assertTrue("#$c is a colour, not a grey", parts.max() - parts.min() <= 3)
        }
    }

    @Test fun `nothing in it runs or is fetched, and what is put in is only ever text`() {
        val html = SignInPage("<b>App</b>", "Chat\"GPT", "x://y\"><script>alert(1)</script>").refused("<img src=x onerror=alert(1)>")
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<b>App"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertFalse(Regex("(src|href)=\"https?:").containsMatchIn(page.signedIn()))
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&#39;f", SignInPage.esc("a&b<c>d\"e'f"))
    }

    @Test fun `without a way back there is no button`() {
        assertFalse(SignInPage("Chicken Rear", "Claude", null).signedIn().contains("<a "))
    }
}
