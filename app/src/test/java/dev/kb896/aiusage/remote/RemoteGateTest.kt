package dev.kb896.aiusage.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGateTest {

    private fun token(secret: String, vararg scopes: RemoteScope, pinnedTo: String? = null, id: String = secret) =
        RemoteGate.Token(id, "name of $id", RemoteGate.digest(secret), scopes.toSet(), pinnedTo)

    private val ha = token("ha-secret", RemoteScope.DISPLAY, RemoteScope.CONTENT)
    private val tasker = token("tasker-secret", RemoteScope.APPS)
    private val tokens = listOf(ha, tasker)

    private fun reason(v: RemoteGate.Verdict) = (v as RemoteGate.Verdict.Denied).reason

    @Test fun `off is off, whatever is presented`() {
        val gate = RemoteGate()
        assertEquals(RemoteGate.OFF, reason(gate.check(false, tokens, "ha-secret", null, RemoteScope.DISPLAY, 0)))
    }

    @Test fun `the right token for a scope it has is let through, as that token`() {
        val gate = RemoteGate()
        val v = gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 0) as RemoteGate.Verdict.Allowed
        assertEquals("ha-secret", v.token.id)
    }

    @Test fun `a token does only what it was given`() {
        val gate = RemoteGate()
        assertEquals(RemoteGate.NO_SCOPE, reason(gate.check(true, tokens, "ha-secret", null, RemoteScope.APPS, 0)))
        assertTrue(gate.check(true, tokens, "tasker-secret", null, RemoteScope.APPS, 0) is RemoteGate.Verdict.Allowed)
    }

    @Test fun `no token and a wrong one are both refused`() {
        val gate = RemoteGate()
        assertEquals(RemoteGate.NO_TOKEN, reason(gate.check(true, tokens, null, null, RemoteScope.DISPLAY, 0)))
        assertEquals(RemoteGate.NO_TOKEN, reason(gate.check(true, tokens, "", null, RemoteScope.DISPLAY, 0)))
        assertEquals(RemoteGate.BAD_TOKEN, reason(gate.check(true, tokens, "ha-secreT", null, RemoteScope.DISPLAY, 0)))
    }

    @Test fun `a pinned token refuses another named sender, and takes an unnamed one`() {
        val gate = RemoteGate()
        val pinned = listOf(token("s", RemoteScope.DISPLAY, pinnedTo = "io.homeassistant.companion.android"))
        assertTrue(gate.check(true, pinned, "s", null, RemoteScope.DISPLAY, 0) is RemoteGate.Verdict.Allowed)
        assertTrue(gate.check(true, pinned, "s", "io.homeassistant.companion.android", RemoteScope.DISPLAY, 0) is RemoteGate.Verdict.Allowed)
        assertEquals(RemoteGate.WRONG_SENDER, reason(gate.check(true, pinned, "s", "com.evil.app", RemoteScope.DISPLAY, 0)))
    }

    @Test fun `five wrong tokens in a minute lock everything out, the right token included`() {
        val gate = RemoteGate()
        repeat(4) { assertEquals(RemoteGate.BAD_TOKEN, reason(gate.check(true, tokens, "guess$it", null, RemoteScope.DISPLAY, it * 1000L))) }
        assertEquals(RemoteGate.BAD_TOKEN, reason(gate.check(true, tokens, "guess4", null, RemoteScope.DISPLAY, 4000)))
        assertEquals(RemoteGate.LOCKED_OUT, reason(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 5000)))
        // and it ends
        assertTrue(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 4000 + RemoteGate.LOCK_FIRST_MS) is RemoteGate.Verdict.Allowed)
    }

    @Test fun `wrong tokens spread over time lock nothing`() {
        val gate = RemoteGate()
        repeat(20) {
            assertEquals(RemoteGate.BAD_TOKEN, reason(gate.check(true, tokens, "guess", null, RemoteScope.DISPLAY, it * 20_000L)))
        }
        assertTrue(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 400_001) is RemoteGate.Verdict.Allowed)
    }

    @Test fun `a second lockout is longer than the first`() {
        val gate = RemoteGate()
        repeat(5) { gate.check(true, tokens, "x", null, RemoteScope.DISPLAY, 0) }
        val afterFirst = RemoteGate.LOCK_FIRST_MS
        repeat(5) { gate.check(true, tokens, "x", null, RemoteScope.DISPLAY, afterFirst) }
        assertEquals(
            RemoteGate.LOCKED_OUT,
            reason(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, afterFirst + RemoteGate.LOCK_FIRST_MS + 1)),
        )
        assertTrue(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, afterFirst + 2 * RemoteGate.LOCK_FIRST_MS) is RemoteGate.Verdict.Allowed)
    }

    @Test fun `a burst fits the bucket, a flood does not, and it refills`() {
        val gate = RemoteGate(bucketSize = 3, refillPerSecond = 1.0)
        repeat(3) { assertTrue(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 0) is RemoteGate.Verdict.Allowed) }
        assertEquals(RemoteGate.TOO_FAST, reason(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 10)))
        // one token's flood is not another's
        assertTrue(gate.check(true, tokens, "tasker-secret", null, RemoteScope.APPS, 10) is RemoteGate.Verdict.Allowed)
        assertTrue(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 1100) is RemoteGate.Verdict.Allowed)
        assertEquals(RemoteGate.TOO_FAST, reason(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 1200)))
    }

    @Test fun `being too fast is not a wrong token, and locks nobody out`() {
        val gate = RemoteGate(bucketSize = 1, refillPerSecond = 0.001)
        gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 0)
        repeat(20) { assertEquals(RemoteGate.TOO_FAST, reason(gate.check(true, tokens, "ha-secret", null, RemoteScope.DISPLAY, 1))) }
        assertTrue(gate.check(true, tokens, "tasker-secret", null, RemoteScope.APPS, 2) is RemoteGate.Verdict.Allowed)
    }
}
