package dev.kb896.aiusage.remote

import dev.kb896.aiusage.remote.RemotePolicy.Apps
import dev.kb896.aiusage.remote.RemotePolicy.Rules
import dev.kb896.aiusage.remote.RemotePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePolicyTest {

    private val pinned = listOf("com.spotify.music", "com.whatsapp")
    private fun denied(v: Verdict) = (v as Verdict.Denied).reason

    @Test fun `by default only what is pinned opens, and only on an unlocked phone`() {
        assertEquals(Verdict.Allowed, RemotePolicy.open("com.spotify.music", pinned, Rules(), locked = false))
        assertEquals(RemotePolicy.NOT_PINNED, denied(RemotePolicy.open("com.bank.app", pinned, Rules(), locked = false)))
        assertEquals(RemotePolicy.LOCKED, denied(RemotePolicy.open("com.spotify.music", pinned, Rules(), locked = true)))
    }

    @Test fun `a list adds to the pinned apps, and any is any`() {
        val listed = Rules(Apps.LISTED, listed = setOf("com.bank.app"))
        assertEquals(Verdict.Allowed, RemotePolicy.open("com.bank.app", pinned, listed, locked = false))
        assertEquals(Verdict.Allowed, RemotePolicy.open("com.whatsapp", pinned, listed, locked = false))
        assertEquals(RemotePolicy.NOT_PINNED, denied(RemotePolicy.open("com.other", pinned, listed, locked = false)))
        assertEquals(Verdict.Allowed, RemotePolicy.open("com.other", pinned, Rules(Apps.ANY), locked = false))
    }

    @Test fun `while locked only the apps named for it open, and still only allowed ones`() {
        val rules = Rules(whileLocked = setOf("com.spotify.music", "com.bank.app"))
        assertEquals(Verdict.Allowed, RemotePolicy.open("com.spotify.music", pinned, rules, locked = true))
        assertEquals(RemotePolicy.LOCKED, denied(RemotePolicy.open("com.whatsapp", pinned, rules, locked = true)))
        assertEquals(RemotePolicy.NOT_PINNED, denied(RemotePolicy.open("com.bank.app", pinned, rules, locked = true)))
    }

    @Test fun `what is not a package name never reaches a list`() {
        assertEquals(RemotePolicy.NOT_AN_APP, denied(RemotePolicy.open("com.a; id", listOf("com.a; id"), Rules(Apps.ANY), locked = false)))
    }

    @Test fun `hiding or closing a guest is always allowed, showing one is not while locked`() {
        for (which in listOf("close", "panel", "front")) assertEquals(Verdict.Allowed, RemotePolicy.guest(which, locked = true))
        assertEquals(RemotePolicy.LOCKED, denied(RemotePolicy.guest("resume", locked = true)))
        assertEquals(Verdict.Allowed, RemotePolicy.guest("resume", locked = false))
    }

    @Test fun `an unknown way to choose apps is the strict one`() {
        assertEquals(Apps.PINNED, Apps.fromWire("everything"))
        assertEquals(Apps.ANY, Apps.fromWire("any"))
    }
}
