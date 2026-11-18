package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.ShotKeeper.Claim
import dev.kb896.aiusage.rear.ShotKeeper.Custodian
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screenshot of the back is never lost to a frozen app, and never saved twice: the app claims it; not claimed within
 * two seconds, or not handed over at all, the shell saves it itself, and a claim that comes later is told so. A claim
 * that races the hand-over finds the shot known; a shell copy that fails gives it back to the app.
 */
class ShotKeeperTest {
    private val a = "Screenshot_2001-02-03-05-05-06-789_rear.png"

    /** Handed over, the call gone out. */
    private fun ShotKeeper.sent(name: String, at: Long) = assertTrue(handOver(name, at) { true })

    @Test fun `the app claims it in time - the app saves it, the shell does not`() {
        val k = ShotKeeper()
        k.sent(a, 1_000L)
        assertEquals(Custodian.WAITING, k.custodian(a))
        assertEquals(Claim.SAVE, k.claim(a))
        assertFalse("the shell's look finds it claimed", k.due(a, 1_000L + ShotKeeper.CLAIM_MS))
        assertEquals(Custodian.APP, k.custodian(a))
        // A second claim is nothing to do: never saved twice.
        assertEquals(Claim.NOTHING, k.claim(a))
    }

    @Test fun `a claim that comes while the hand-over call is still going out finds it waiting - saved once, by the app`() {
        // A running app may claim before the shell's call has even returned: the shot is known before the call.
        val k = ShotKeeper()
        var said: Claim? = null
        assertTrue(k.handOver(a, 1_000L) { said = k.claim(a); true })
        assertEquals(Claim.SAVE, said)
        assertEquals(Custodian.APP, k.custodian(a))
        assertFalse("the shell does not save it a second time", k.due(a, 1_000L + ShotKeeper.CLAIM_MS))
        assertFalse(k.unsent(a))
        // And the bin goes to the app, which has it.
        assertEquals(Custodian.APP, k.bin(a))
    }

    @Test fun `not claimed within two seconds the shell saves it, and the app that thaws later only has it scanned`() {
        val k = ShotKeeper()
        k.sent(a, 1_000L)
        assertFalse("not before its time", k.due(a, 1_000L + ShotKeeper.CLAIM_MS - 1))
        assertTrue(k.due(a, 1_000L + ShotKeeper.CLAIM_MS))
        assertFalse("once", k.due(a, 9_000L))
        assertEquals(Custodian.SHELL, k.custodian(a))
        assertEquals(Claim.SCAN, k.claim(a))
        assertEquals(2_000L, ShotKeeper.CLAIM_MS)
    }

    @Test fun `not handed over at all, the shell saves it at once`() {
        val k = ShotKeeper()
        assertFalse(k.handOver(a, 1_000L) { false })
        assertTrue(k.unsent(a))
        assertFalse("once", k.unsent(a))
        assertEquals(Custodian.SHELL, k.custodian(a))
        assertFalse("already the shell's: no second save at the look", k.due(a, 5_000L))
        assertEquals(Claim.SCAN, k.claim(a))
        // Binned before the shell took it: nobody saves it.
        val binned = ShotKeeper()
        binned.handOver(a, 0L) { false }
        assertEquals(Custodian.BINNED, binned.bin(a))
        assertFalse(binned.unsent(a))
    }

    @Test fun `the shell's copy failing gives it back to the app, which still holds it open`() {
        val k = ShotKeeper()
        k.sent(a, 0L)
        assertTrue(k.due(a, ShotKeeper.CLAIM_MS))
        assertTrue(k.failed(a))
        assertEquals("the app thaws: its claim saves it", Claim.SAVE, k.claim(a))
        assertEquals(Custodian.APP, k.custodian(a))
        assertFalse("only the shell's own", k.failed(a))
        // Binned while the shell was writing it: the failure gives nothing back, and the app saves nothing.
        val b = ShotKeeper()
        b.sent(a, 0L)
        b.due(a, ShotKeeper.CLAIM_MS)
        assertEquals(Custodian.SHELL, b.bin(a))
        assertFalse(b.failed(a))
        assertEquals(Claim.NOTHING, b.claim(a))
        assertFalse(ShotKeeper().failed(a))
    }

    @Test fun `the bin goes to whoever has it, and one still waiting is saved by nobody`() {
        val waiting = ShotKeeper()
        waiting.sent(a, 0L)
        assertEquals(Custodian.BINNED, waiting.bin(a))
        assertFalse(waiting.due(a, 10_000L))
        assertEquals(Claim.NOTHING, waiting.claim(a))
        val app = ShotKeeper()
        app.sent(a, 0L); app.claim(a)
        assertEquals(Custodian.APP, app.bin(a))
        val shell = ShotKeeper()
        shell.sent(a, 0L); shell.due(a, ShotKeeper.CLAIM_MS)
        assertEquals(Custodian.SHELL, shell.bin(a))
        // Binned is binned: a later claim does nothing, a second bin deletes nothing.
        assertEquals(Claim.NOTHING, shell.claim(a))
        assertEquals(Custodian.BINNED, shell.bin(a))
        assertEquals(Custodian.BINNED, app.bin(a))
        assertNull(ShotKeeper().bin(a))
    }

    @Test fun `a shot the shell does not know is the app's to save, and any unknown answer means save`() {
        assertEquals(Claim.SAVE, ShotKeeper().claim(a))
        assertEquals(Claim.SAVE, Claim.of(0))
        assertEquals(Claim.SCAN, Claim.of(1))
        assertEquals(Claim.NOTHING, Claim.of(2))
        assertEquals(Claim.SAVE, Claim.of(77))
    }

    @Test fun `it keeps the last sixteen`() {
        val k = ShotKeeper()
        for (i in 0 until 20) k.sent("s$i", i.toLong())
        assertNull(k.custodian("s3"))
        assertEquals(Custodian.WAITING, k.custodian("s4"))
        assertEquals(Custodian.WAITING, k.custodian("s19"))
    }
}
