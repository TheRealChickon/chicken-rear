package dev.kb896.aiusage.auth

import dev.kb896.aiusage.data.Account
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A whole sign-in as the app runs it (auth/SignIns), with a real listener on this machine: the "browser" is this
 * test asking the address the app would have opened, the "exchange" a stand-in. What these hold: the sign-in
 * belongs to nobody's screen (it is started and then only watched), a new attempt ends the old one and has its
 * port, every way it can end is said in plain words for the settings row, a sign-in cut off by the process going
 * is said so by the next process, and nothing secret reaches the log.
 */
class SignInsTest {

    private val logged = Collections.synchronizedList(ArrayList<String>())
    private val asked = LinkedBlockingQueue<String>()
    private val notes = MapNotes()
    private val exchanged = Collections.synchronizedList(ArrayList<Triple<String, String, String>>())
    private val fresh = Collections.synchronizedList(ArrayList<Account>())
    private val verifiers = Collections.synchronizedList(ArrayList<String>())
    private val held = ArrayList<ServerSocket>()
    @Volatile private var exchangeFails: RuntimeException? = null
    @Volatile private var browser = true

    private class MapNotes : SignIns.Notes {
        val map = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @After fun tidy() { held.forEach { it.close() } }

    private fun freePort(): Int = ServerSocket(0, 0, LoopbackServer.V4).use { it.localPort }

    /** ChatGPT's shape: a 127.0.0.1 redirect, two ports, a state that may come back longer. */
    private fun signIns(ports: List<Int>, clock: () -> Long = LoopbackServer.MONOTONIC, wall: () -> Long = { 1_000_000L }) = SignIns(
        flow = {
            SignIns.Flow(
                service = "ChatGPT",
                ports = ports,
                redirect = { port -> ChatGptOAuth.redirectUri(port) },
                stateMayGrow = true,
                authorizeUrl = { challenge, redirectUri ->
                    verifiers.add(challenge.verifier)
                    "https://auth.example/oauth/authorize".toHttpUrl().newBuilder()
                        .addQueryParameter("redirect_uri", redirectUri)
                        .addQueryParameter("code_challenge", challenge.challenge)
                        .addQueryParameter("state", challenge.state).build().toString()
                },
                exchange = { code, challenge, redirectUri ->
                    exchanged.add(Triple(code, challenge.verifier, redirectUri))
                    exchangeFails?.let { throw it }
                },
            )
        },
        openBrowser = { url -> if (browser) asked.add(url); browser },
        signedIn = { fresh.add(it) },
        notes = notes,
        appName = "Chicken Rear",
        backLink = "dev.chicken.rear.dev://signin",
        log = { logged.add(it) },
        clock = clock,
        wall = wall,
    )

    /** The page the app asked the browser for, as its redirect address and its state. */
    private fun opened(): Pair<String, String> {
        val url = asked.poll(5, TimeUnit.SECONDS)!!.toHttpUrl()
        return url.queryParameter("redirect_uri")!! to url.queryParameter("state")!!
    }

    /** What a browser does at the end of the sign-in page: one GET of the redirect address. */
    private fun comeBack(redirect: String, query: String): String {
        val to = redirect.toHttpUrl()
        assertEquals("ChatGPT's redirect is the IPv4 literal", "127.0.0.1", to.host)
        return Socket().use { s ->
            s.connect(InetSocketAddress(LoopbackServer.V4, to.port), 2_000)
            s.soTimeout = 8_000
            s.getOutputStream().apply { write("GET ${to.encodedPath}?$query HTTP/1.1\r\nHost: 127.0.0.1:${to.port}\r\n\r\n".toByteArray()); flush() }
            String(s.getInputStream().readBytes(), Charsets.UTF_8)
        }
    }

    private fun until(what: String, ms: Long = 5_000, ok: () -> Boolean) {
        val by = System.nanoTime() + ms * 1_000_000
        while (!ok()) {
            assertTrue("not within $ms ms: $what", System.nanoTime() < by)
            Thread.sleep(10)
        }
    }

    private fun assertFree(port: Int) = ServerSocket(port, 0, LoopbackServer.V4).close()

    @Test fun `from the tap to signed in - with nothing but the app's own object alive`() {
        val port = freePort()
        val signIns = signIns(listOf(port, freePort()))
        signIns.start(Account.CHATGPT)
        assertEquals(Account.CHATGPT, signIns.state.value.waiting)
        val (redirect, state) = opened()
        assertEquals("http://127.0.0.1:$port/auth/callback", redirect)

        val page = comeBack(redirect, "code=CODE-1a2b3c&scope=openid+profile+email+offline_access&state=$state")
        assertTrue(page, page.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(page, page.contains("<h1>Signed in</h1>") && page.contains("ChatGPT is connected"))
        assertTrue(page, page.contains("""href="dev.chicken.rear.dev://signin""""))

        until("the sign-in is over") { signIns.state.value.waiting == null }
        val now = signIns.state.value
        assertEquals(SignIns.Finished(Account.CHATGPT, null, 1), now.last)
        assertTrue(now.failures.isEmpty())
        assertEquals(listOf(Triple("CODE-1a2b3c", verifiers.single(), redirect)), exchanged.toList())
        until("fresh figures are asked for") { fresh.toList() == listOf(Account.CHATGPT) }
        assertTrue("the mark of a waiting sign-in is gone", notes.map.isEmpty())
        assertTrue(signIns.takeToast(now.last!!))
        assertFalse("one toast for one sign-in", signIns.takeToast(now.last!!))
        assertFree(port)
    }

    @Test fun `the exchange refused - the browser's page and the settings row both say so, with OpenAI's own word`() {
        val port = freePort()
        val signIns = signIns(listOf(port))
        exchangeFails = ChatGptOAuth.Failure("exchange: HTTP 400 (invalid_grant)")
        signIns.start(Account.CHATGPT)
        val (redirect, state) = opened()
        val page = comeBack(redirect, "code=CODE-1a2b3c&state=$state")
        assertTrue(page, page.contains("<h1>Sign-in failed</h1>"))
        assertTrue(page, page.contains("ChatGPT did not accept its code (HTTP 400 (invalid_grant))"))
        assertFalse(page, page.contains("Signed in"))
        until("the sign-in is over") { signIns.state.value.waiting == null }
        val why = signIns.state.value.failures[Account.CHATGPT]!!
        assertEquals("The browser's answer came, but ChatGPT did not accept its code (HTTP 400 (invalid_grant)). Sign in again.", why)
        assertEquals("kept for the next time the settings are opened", why, notes.get("failure.chatgpt"))
        assertEquals(1, exchanged.size)
        assertTrue(fresh.isEmpty())
        assertFree(port)
    }

    @Test fun `what a failed exchange is, in words`() {
        assertEquals(
            "The browser's answer came, but ChatGPT could not be reached (UnknownHostException). Check the connection.",
            SignIns.exchangeWords("ChatGPT", ChatGptOAuth.Failure("exchange: UnknownHostException")),
        )
        assertEquals(
            "The browser's answer came, but Claude did not accept its code (HTTP 400 (invalid_grant)).",
            SignIns.exchangeWords("Claude", OAuthClient.Failure("exchange: HTTP 400 (invalid_grant)")),
        )
        assertEquals(
            "The browser's answer came, but ChatGPT's reply could not be used (token response had no access_token).",
            SignIns.exchangeWords("ChatGPT", ChatGptOAuth.Failure("token response had no access_token")),
        )
        // Anything else may carry anything in its message: only its kind is said.
        assertEquals(
            "The browser's answer came, but the sign-in could not be kept on this phone (IllegalStateException).",
            SignIns.exchangeWords("ChatGPT", IllegalStateException("token=SECRET")),
        )
    }

    @Test fun `ten minutes and no answer - 'timed out', in the row`() {
        val now = AtomicLong(5_000)
        val port = freePort()
        val signIns = signIns(listOf(port), clock = now::get)
        signIns.start(Account.CHATGPT)
        opened()
        // The browser is asked BEFORE the listener reads its starting time, so one step of the clock taken here
        // may come before that and the deadline would lie ten minutes past it (seen once with the PC loaded by
        // other builds). Ten minutes more on every look ends it whichever came first.
        until("the sign-in is over") { now.addAndGet(LoopbackServer.DEADLINE_MS); signIns.state.value.waiting == null }
        assertEquals(
            "Timed out after 10 minutes: the browser's answer did not reach the app. Sign in again.",
            signIns.state.value.failures[Account.CHATGPT],
        )
        assertTrue(exchanged.isEmpty())
        assertFree(port)
    }

    @Test fun `OpenAI's page sending him back with an error is said as that`() {
        val port = freePort()
        val signIns = signIns(listOf(port))
        signIns.start(Account.CHATGPT)
        val (redirect, state) = opened()
        val page = comeBack(redirect, "error=access_denied&state=$state")
        assertTrue(page, page.contains("Sign-in failed") && page.contains("ChatGPT&#39;s page was left without allowing it. Go back to Chicken Rear and sign in again."))
        until("the sign-in is over") { signIns.state.value.waiting == null }
        assertEquals("ChatGPT's page was left without allowing it. Sign in again.", signIns.state.value.failures[Account.CHATGPT])
        assertEquals("ChatGPT's page answered with an error: server_error.", SignInPage.refusedWords("ChatGPT", "server_error"))
    }

    @Test fun `a new attempt ends the one that waits and has the same port - the old page's answer is then nobody's`() {
        val port = freePort()
        val signIns = signIns(listOf(port))
        signIns.start(Account.CHATGPT)
        val (firstRedirect, firstState) = opened()
        signIns.start(Account.CHATGPT)
        val (redirect, state) = opened()
        assertEquals("the second attempt has the first one's port", firstRedirect, redirect)
        assertNotEquals(firstState, state)
        assertEquals(Account.CHATGPT, signIns.state.value.waiting)

        val stale = comeBack(redirect, "code=CODE-old&state=$firstState")
        assertTrue(stale, stale.startsWith("HTTP/1.1 400 Bad Request\r\n") && stale.contains("Sign-in failed"))
        assertTrue(exchanged.isEmpty())
        assertEquals("still waiting for the right one", Account.CHATGPT, signIns.state.value.waiting)

        assertTrue(comeBack(redirect, "code=CODE-new&state=$state").contains("Signed in"))
        until("the sign-in is over") { signIns.state.value.waiting == null }
        assertEquals(listOf("CODE-new"), exchanged.map { it.first })
        assertEquals(SignIns.Finished(Account.CHATGPT, null, 2), signIns.state.value.last)
        assertTrue(signIns.state.value.failures.isEmpty())
    }

    @Test fun `the first port taken by another app - the second, and its redirect says so`() {
        val taken = ServerSocket(0, 0, LoopbackServer.V4).also { held.add(it) }
        val second = freePort()
        val signIns = signIns(listOf(taken.localPort, second))
        signIns.start(Account.CHATGPT)
        val (redirect, state) = opened()
        assertEquals("http://127.0.0.1:$second/auth/callback", redirect)
        assertTrue(comeBack(redirect, "code=c&state=$state").contains("Signed in"))
        until("the sign-in is over") { signIns.state.value.waiting == null }
        assertEquals(redirect, exchanged.single().third)
        assertTrue(logged.toString(), logged.any { it.contains("port ${taken.localPort} is taken") })
    }

    @Test fun `both ports taken - said plainly, and no browser is opened`() {
        val a = ServerSocket(0, 0, LoopbackServer.V4).also { held.add(it) }
        val b = ServerSocket(0, 0, LoopbackServer.V4).also { held.add(it) }
        val signIns = signIns(listOf(a.localPort, b.localPort))
        signIns.start(Account.CHATGPT)
        until("the sign-in is over") { signIns.state.value.waiting == null }
        assertEquals(
            "Ports ${a.localPort} and ${b.localPort} are in use on this phone by another app, and ChatGPT's sign-in can come back to no other. Close that app. Sign in again.",
            signIns.state.value.failures[Account.CHATGPT],
        )
        assertNull(asked.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `no browser to open - said, and the port is free again`() {
        val port = freePort()
        browser = false
        val signIns = signIns(listOf(port))
        signIns.start(Account.CHATGPT)
        until("the sign-in is over") { signIns.state.value.waiting == null }
        assertEquals("No browser could be opened for ChatGPT's sign-in page. Sign in again.", signIns.state.value.failures[Account.CHATGPT])
        assertFree(port)
    }

    @Test fun `a tap on Cancel in the sign-in's first moments is the second tap on 'Sign in' - the browser's answer still arrives`() {
        val now = AtomicLong(5_000)
        val port = freePort()
        val signIns = signIns(listOf(port), clock = now::get)
        signIns.start(Account.CHATGPT)
        now.addAndGet(30)
        assertFalse("a tap 30 ms after the first cancelled the sign-in", signIns.cancel())
        val (redirect, state) = opened()
        now.addAndGet(SignIns.CANCEL_AFTER_MS - 31)
        assertFalse(signIns.cancel())
        assertEquals(Account.CHATGPT, signIns.state.value.waiting)
        assertEquals("chatgpt|1000000", notes.get("pending"))

        assertTrue(comeBack(redirect, "code=CODE-1a2b3c&state=$state").contains("Signed in"))
        until("the sign-in is over") { signIns.state.value.waiting == null }
        assertEquals(SignIns.Finished(Account.CHATGPT, null, 1), signIns.state.value.last)
        assertEquals(listOf("CODE-1a2b3c"), exchanged.map { it.first })
        assertTrue(logged.toString(), logged.any { it.contains("Cancel") && it.contains("30 ms") && it.contains("still waiting") })
    }

    @Test fun `how long Cancel is not offered - from each attempt's own beginning`() {
        val now = AtomicLong(5_000)
        val signIns = signIns(listOf(freePort()), clock = now::get)
        assertEquals("nothing waits", 0L, signIns.cancelInMs())
        assertEquals(0, signIns.state.value.attempt)
        signIns.start(Account.CHATGPT)
        opened()
        assertEquals(1, signIns.state.value.attempt)
        assertEquals(SignIns.CANCEL_AFTER_MS, signIns.cancelInMs())
        now.addAndGet(SignIns.CANCEL_AFTER_MS - 1)
        assertEquals(1L, signIns.cancelInMs())
        now.addAndGet(500)
        assertEquals(0L, signIns.cancelInMs())
        // A new attempt (the back's own "Sign in" button) begins its own time.
        signIns.start(Account.CHATGPT)
        opened()
        assertEquals(2, signIns.state.value.attempt)
        assertEquals(SignIns.CANCEL_AFTER_MS, signIns.cancelInMs())
        assertFalse(signIns.cancel())
        now.addAndGet(SignIns.CANCEL_AFTER_MS)
        assertTrue(signIns.cancel())
        assertEquals(0, signIns.state.value.attempt)
    }

    @Test fun `cancelled by hand - not waiting, nothing said, the port free`() {
        val now = AtomicLong(5_000)
        val port = freePort()
        val signIns = signIns(listOf(port), clock = now::get)
        signIns.start(Account.CHATGPT)
        opened()
        now.addAndGet(SignIns.CANCEL_AFTER_MS)
        assertTrue(signIns.cancel())
        assertNull(signIns.state.value.waiting)
        until("the port is free") { runCatching { assertFree(port) }.isSuccess }
        assertTrue(signIns.state.value.failures.isEmpty())
        assertNull(signIns.state.value.last)
        assertTrue(notes.map.isEmpty())
    }

    @Test fun `a sign-in the process did not live to finish is said by the next process`() {
        val port = freePort()
        signIns(listOf(port), wall = { 1_000_000L }).start(Account.CHATGPT)
        opened()
        assertEquals("chatgpt|1000000", notes.get("pending"))

        logged.clear()
        val next = signIns(listOf(freePort()), wall = { 1_043_000L })
        assertNull(next.state.value.waiting)
        assertEquals(SignIns.RESTARTED, next.state.value.failures[Account.CHATGPT])
        assertNull("said once", notes.get("pending"))
        assertTrue(logged.toString(), logged.any { it.contains("chatgpt: a sign-in begun 43 s ago was cut off by the app's process restarting") })

        // A new attempt takes the line away; signing out would too.
        next.forget(Account.CHATGPT)
        assertTrue(next.state.value.failures.isEmpty())
        assertNull(notes.get("failure.chatgpt"))
    }

    @Test fun `nothing secret in what is logged, from the tap to the end`() {
        val port = freePort()
        val signIns = signIns(listOf(port))
        // One attempt refused, one that works: every kind of line.
        exchangeFails = ChatGptOAuth.Failure("exchange: HTTP 400 (invalid_grant)")
        signIns.start(Account.CHATGPT)
        val (redirect1, state1) = opened()
        comeBack(redirect1, "code=CODE-wrong-attempt&state=not-$state1")
        comeBack(redirect1, "code=CODE-s3cr3t-first&state=$state1%3Aowner%40example.com")
        until("the first is over") { signIns.state.value.waiting == null }
        exchangeFails = null
        signIns.start(Account.CHATGPT)
        val (redirect2, state2) = opened()
        comeBack(redirect2, "code=CODE-s3cr3t-second&scope=openid+profile+email+offline_access&state=$state2")
        until("the second is over") { signIns.state.value.last?.n == 2 }

        val all = synchronized(logged) { logged.toList() }
        val secrets = listOf(state1, state2, "CODE-", "tester", "example.com", "auth.example") + verifiers
        for (line in all) for (secret in secrets) assertFalse("the log holds '$secret': $line", line.contains(secret))
        for (line in all) assertFalse("the log holds an address with a query: $line", line.contains("?") || line.contains("code=") || line.contains("state="))
        // And it does say each step.
        fun said(part: String) = assertTrue("no line says '$part': $all", all.any { it.contains(part) })
        said("chatgpt: sign-in 1 begins")
        said("listening on 127.0.0.1:$port")
        said("the browser was asked for ChatGPT's sign-in page; waiting up to 10 min on port $port")
        said("a callback with a wrong or missing state")
        said("the callback came, state ok, with a code")
        said("chatgpt: exchange failed after")
        said("HTTP 400 (invalid_grant)")
        said("chatgpt: sign-in 1 is over after")
        said("chatgpt: exchange done in")
        said("chatgpt: sign-in 2 is over after 0 s: signed in")
    }
}
