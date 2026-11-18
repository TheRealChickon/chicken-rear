package dev.kb896.aiusage.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LoopbackServerTest {

    private val pool = Executors.newCachedThreadPool()
    private val logged = Collections.synchronizedList(ArrayList<String>())
    private val opened = ArrayList<LoopbackServer>()
    private val page = SignInPage("Chicken Rear", "ChatGPT", "dev.chicken.rear.dev://signin")

    @After fun tidy() {
        opened.forEach { it.close() }
        pool.shutdownNow()
    }

    private fun freePort(): Int = ServerSocket(0, 0, LoopbackServer.V4).use { it.localPort }

    private fun open(port: Int = freePort(), redirect: String = "http://localhost:$port/callback", grow: Boolean = false, clock: () -> Long = LoopbackServer.MONOTONIC): LoopbackServer =
        LoopbackServer.open(port, redirect, grow, { logged.add(it) }, clock)!!.also { opened.add(it) }

    private class Served(val end: Future<LoopbackServer.End>, val codes: MutableList<String>)

    /** The listener serving on its own thread, as the app has it; `said` is what the exchange answers. */
    private fun serve(server: LoopbackServer, state: String, deadlineMs: Long = 20_000, said: String? = null): Served {
        val codes = Collections.synchronizedList(ArrayList<String>())
        return Served(pool.submit<LoopbackServer.End> { server.serve(state, page, deadlineMs) { codes.add(it); said } }, codes)
    }

    private fun connect(port: Int, to: InetAddress = LoopbackServer.V4): Socket =
        Socket().apply { connect(InetSocketAddress(to, port), 2_000); soTimeout = 8_000 }

    /** One request, and everything the listener answers until it closes the connection. */
    private fun ask(port: Int, request: String, to: InetAddress = LoopbackServer.V4): String = connect(port, to).use { s -> send(s, request) }

    private fun send(s: Socket, request: String): String {
        s.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
        return String(s.getInputStream().readBytes(), Charsets.UTF_8)
    }

    private fun get(target: String) = "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nUser-Agent: test\r\nAccept: text/html\r\n\r\n"

    private fun ended(served: Served): LoopbackServer.End = served.end.get(10, TimeUnit.SECONDS)

    private fun assertFree(port: Int) {
        ServerSocket(port, 0, LoopbackServer.V4).close()
        if (v6()) ServerSocket(port, 0, LoopbackServer.V6).close()
    }

    private fun v6(): Boolean = try { ServerSocket(0, 0, LoopbackServer.V6).close(); true } catch (_: Exception) { false }

    // -- where it listens ---------------------------------------------------------------------------------------

    @Test fun `the literal 127_0_0_1 is answered — the address ChatGPT's redirect names`() {
        val port = freePort()
        val server = open(port, "http://127.0.0.1:$port/auth/callback")
        val served = serve(server, "abc123")
        val answer = ask(port, get("/auth/callback?code=the-code&scope=openid+profile&state=abc123"), LoopbackServer.V4)
        assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(answer, answer.contains("Signed in"))
        assertNull((ended(served) as LoopbackServer.End.Answered).failure)
        assertEquals(listOf("the-code"), served.codes)
    }

    @Test fun `the other loopback address is answered too — where a browser may take the name localhost`() {
        assumeTrue("this machine has no ::1", v6())
        val port = freePort()
        val served = serve(open(port), "abc123")
        val answer = ask(port, get("/callback?code=the-code&state=abc123"), LoopbackServer.V6)
        assertTrue(answer, answer.contains("Signed in"))
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertEquals(listOf("the-code"), served.codes)
    }

    @Test fun `the addresses are named by their bytes, not by what the JVM calls loopback`() {
        // On Android InetAddress.getLoopbackAddress() is ::1; a listener bound to it is not on 127.0.0.1.
        assertEquals("127.0.0.1", LoopbackServer.V4.hostAddress)
        assertTrue(LoopbackServer.V4 is java.net.Inet4Address)
        assertTrue(LoopbackServer.V6 is java.net.Inet6Address)
        assertTrue(LoopbackServer.V4.isLoopbackAddress && LoopbackServer.V6.isLoopbackAddress)
    }

    @Test fun `no sign-in code asks the JVM which address is loopback, or listens without naming one`() {
        // A test on a PC cannot see the fault itself: here getLoopbackAddress() is 127.0.0.1, on the phone ::1.
        // So the call is kept out of the sign-in's sources altogether.
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val sources = File(src, "dev/kb896/aiusage/auth").listFiles()!!.filter { it.name.endsWith(".kt") } +
            File(src, "dev/kb896/aiusage/service/Graph.kt") + File(src, "dev/kb896/aiusage/ui/MainActivity.kt")
        assertTrue(sources.size > 8)
        for (file in sources) {
            val code = file.readLines().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }.joinToString("\n")
            assertFalse("${file.name} asks for getLoopbackAddress()", code.contains("getLoopbackAddress"))
            assertFalse("${file.name} listens without an address (the wildcard)", Regex("ServerSocket\\(\\s*\\w+\\s*\\)").containsMatchIn(code))
        }
    }

    @Test fun `nothing off this machine's loopback reaches it — it is not on the wildcard address`() {
        val outward = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
        assumeTrue("this machine has no address but loopback", outward != null)
        val port = freePort()
        serve(open(port), "s")
        val reached = try { Socket().use { it.connect(InetSocketAddress(outward, port), 1_000) }; true } catch (_: Exception) { false }
        assertFalse("the listener answered on $outward", reached)
    }

    @Test fun `a port that is taken is said so, and is had again once the other listener is closed`() {
        val port = freePort()
        val first = open(port)
        assertNull(LoopbackServer.open(port))
        first.close()
        val second = LoopbackServer.open(port)
        assertNotNull(second)
        second!!.close()
    }

    // -- what does not end the wait -----------------------------------------------------------------------------

    @Test fun `a connection that says nothing does not use the listener up`() {
        val port = freePort()
        val served = serve(open(port), "s")
        connect(port).use { silent ->
            val began = System.nanoTime()
            val answer = ask(port, get("/callback?code=c&state=s"))
            val tookMs = (System.nanoTime() - began) / 1_000_000
            assertTrue(answer, answer.contains("Signed in"))
            assertTrue("the callback waited $tookMs ms behind a silent connection", tookMs < 2_500)
            assertTrue(ended(served) is LoopbackServer.End.Answered)
            val overMs = (System.nanoTime() - began) / 1_000_000
            assertTrue("the end waited $overMs ms for a connection that said nothing", overMs < 2_000)
            assertFalse(silent.isClosed)
        }
        assertEquals(listOf("c"), served.codes)
        assertFree(port)
    }

    @Test fun `more connections that say nothing than there are places do not keep the callback out`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val silent = List(most + 20) { connect(port) }
        try {
            Thread.sleep(100)
            val began = System.nanoTime()
            val answer = ask(port, get("/callback?code=c&state=s"))
            assertTrue(answer, answer.contains("Signed in"))
            assertTrue("the callback waited behind silent connections", (System.nanoTime() - began) / 1_000_000 < 2_500)
            assertTrue(ended(served) is LoopbackServer.End.Answered)
            assertEquals(listOf("c"), served.codes)
        } finally {
            silent.forEach { it.close() }
        }
        assertFree(port)
    }

    // -- room: no connection keeps a newer one out --------------------------------------------------------------

    private val most = LoopbackServer.MOST_AT_ONCE

    /** A connection that has sent [first] and holds on. */
    private fun holding(port: Int, first: String): Socket = connect(port).also { s ->
        if (first.isNotEmpty()) s.getOutputStream().apply { write(first.toByteArray(Charsets.ISO_8859_1)); flush() }
    }

    /** True when the listener has closed [s]; false when it is still open and silent. */
    private fun closedByListener(s: Socket, waitMs: Int = 300): Boolean {
        s.soTimeout = waitMs
        return try {
            val sink = ByteArray(512)
            while (s.getInputStream().read(sink) >= 0) { /* its answer, if it had one */ }
            true
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: java.io.IOException) {
            true
        }
    }

    private fun said(part: String) = synchronized(logged) { logged.count { it.contains(part) } }

    @Test fun `every place held by a connection that sent a few bytes - the callback has a place, the oldest of them goes`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val held = List(most) { holding(port, "GET ").also { Thread.sleep(5) } }
        try {
            Thread.sleep(300) // each is being read by now
            val began = System.nanoTime()
            val answer = ask(port, get("/callback?code=c&state=s"))
            val tookMs = (System.nanoTime() - began) / 1_000_000
            assertTrue(answer, answer.contains("Signed in"))
            assertTrue("the callback waited $tookMs ms for a place", tookMs < 1_000)
            assertEquals(listOf("c"), served.codes)
            assertTrue("the oldest unfinished one was not the one closed", closedByListener(held[0]))
            assertFalse("a younger one was closed", closedByListener(held[1], 150))
            assertFalse("the youngest was closed", closedByListener(held.last(), 150))
            assertEquals(logged.toString(), 1, said("closed with its request unfinished, to make room for a newer one"))
        } finally {
            held.forEach { it.close() }
        }
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertTrue(logged.toString(), logged.any { it.contains("closed after") && it.contains("1 closed unfinished to make room") })
        assertFree(port)
    }

    @Test fun `one that has its answer and only holds on goes before any that is still unfinished`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val answered = holding(port, get("/favicon.ico"))
        Thread.sleep(50)
        val held = List(most - 1) { holding(port, "GET ") }
        try {
            Thread.sleep(300)
            assertTrue(ask(port, get("/callback?code=c&state=s")).contains("Signed in"))
            assertTrue("the answered one kept its place", closedByListener(answered))
            assertEquals("an unfinished one was closed while an answered one held a place: $logged", 0, said("to make room"))
            assertFalse(closedByListener(held[0], 150))
        } finally {
            held.forEach { it.close() }
            answered.close()
        }
        assertTrue(ended(served) is LoopbackServer.End.Answered)
    }

    @Test fun `the callback, once read, is not closed for room while its tokens are bought`() {
        val port = freePort()
        val server = open(port)
        val buying = CountDownLatch(1)
        val go = CountDownLatch(1)
        val codes = Collections.synchronizedList(ArrayList<String>())
        val end = pool.submit<LoopbackServer.End> {
            server.serve("s", page, 20_000) { code -> codes.add(code); buying.countDown(); go.await(10, TimeUnit.SECONDS); null }
        }
        val crowd = ArrayList<Socket>()
        try {
            connect(port).use { browser ->
                browser.getOutputStream().apply { write(get("/callback?code=c&state=s").toByteArray()); flush() }
                assertTrue(buying.await(5, TimeUnit.SECONDS))
                // More than every place, twice over, while the exchange runs.
                repeat(2 * most + 5) { crowd.add(holding(port, "GET ")) }
                Thread.sleep(300)
                go.countDown()
                val answer = String(browser.getInputStream().readBytes(), Charsets.UTF_8)
                assertTrue(answer, answer.contains("Signed in"))
            }
        } finally {
            crowd.forEach { it.close() }
        }
        assertTrue(end.get(10, TimeUnit.SECONDS) is LoopbackServer.End.Answered)
        assertEquals(listOf("c"), codes)
        assertTrue("the crowd did make the listener close some of its own: $logged", said("to make room") >= 1)
    }

    /**
     * What another app on the phone could do with some sockets: [count] connections that each send [first] and
     * then only hold on, and connect again the moment they are dropped. Until [stop].
     */
    private fun holders(port: Int, count: Int, first: String, stop: AtomicBoolean, theirs: MutableSet<Socket>): List<Future<*>> = List(count) {
        pool.submit {
            while (!stop.get()) {
                try {
                    connect(port).use { s ->
                        theirs.add(s)
                        try {
                            s.soTimeout = 15_000
                            if (first.isNotEmpty()) s.getOutputStream().apply { write(first.toByteArray(Charsets.ISO_8859_1)); flush() }
                            val input = s.getInputStream()
                            val sink = ByteArray(256)
                            while (!stop.get() && input.read(sink) >= 0) { /* until the listener lets go of it */ }
                        } finally {
                            theirs.remove(s)
                        }
                    }
                } catch (_: Exception) {
                    if (!stop.get()) Thread.sleep(1)
                }
            }
        }
    }

    /** One callback per fresh listener, [times] over, against [count] such connections. */
    private fun callbacksAgainstHolders(count: Int, first: String, times: Int, settleMs: Long) {
        repeat(times) { round ->
            val port = freePort()
            val served = serve(open(port), "s")
            val stop = AtomicBoolean(false)
            val theirs = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
            val running = holders(port, count, first, stop, theirs)
            try {
                Thread.sleep(settleMs) // every place is theirs by now
                val began = System.nanoTime()
                val answer = try { ask(port, get("/callback?code=c$round&state=s")) } catch (e: Exception) { "NO ANSWER: $e" }
                val tookMs = (System.nanoTime() - began) / 1_000_000
                assertTrue("round $round: the callback was kept out by $count connections that held on: $answer", answer.contains("Signed in"))
                assertTrue("round $round: the callback waited $tookMs ms behind them", tookMs < 2_500)
                assertEquals(listOf("c$round"), served.codes)
            } finally {
                stop.set(true)
                theirs.toList().forEach { runCatching { it.close() } }
                running.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
            }
            assertTrue(ended(served) is LoopbackServer.End.Answered)
            assertFree(port)
        }
    }

    @Test fun `more of them than there are places, each connecting again as it is dropped, do not keep the callback out`() {
        // The review's own attack, grown with the places: a few bytes, hold on, connect again when dropped.
        callbacksAgainstHolders(count = most + 2, first = "GET ", times = 6, settleMs = 300)
    }

    @Test fun `a crowd of them does not keep it out either`() {
        callbacksAgainstHolders(count = 4 * most, first = "GET /auth/callback?code=", times = 6, settleMs = 300)
    }

    @Test fun `connections that were answered and never close do not keep the callback out`() {
        // A whole request is answered at once; its connection then only waits for the other side to take the answer.
        callbacksAgainstHolders(count = 3 * most, first = get("/favicon.ico"), times = 3, settleMs = 300)
    }

    @Test fun `what is said of a crowd - the 1st, 10th, 100th, not each`() {
        assertEquals(listOf(1, 10, 100, 1_000, 10_000, 100_000), (1..250_000).filter(LoopbackServer::worthSaying))
        assertTrue(LoopbackServer.worthSaying(1_000_000_000))
        assertFalse(LoopbackServer.worthSaying(Int.MAX_VALUE))
    }

    @Test fun `the browser's favicon, a HEAD, another path and a TLS hello each get a small answer and the wait goes on`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val icon = ask(port, get("/favicon.ico"))
        assertTrue(icon, icon.startsWith("HTTP/1.1 404 Not Found\r\n"))
        val head = ask(port, "HEAD /callback?code=c&state=s HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertTrue(head, head.startsWith("HTTP/1.1 405 Method Not Allowed\r\n"))
        assertTrue(head, head.contains("Allow: GET\r\n") && head.contains("Content-Length: 0\r\n"))
        val elsewhere = ask(port, get("/?code=c&state=s"))
        assertTrue(elsewhere, elsewhere.startsWith("HTTP/1.1 404 Not Found\r\n"))
        // What a browser trying https on the port sends; it gives up by closing its side.
        val tls = connect(port).use { s ->
            s.getOutputStream().apply { write(byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, 0xfc.toByte(), 0x03, 0x03, 0x00, 0x7f)); flush() }
            s.shutdownOutput()
            String(s.getInputStream().readBytes(), Charsets.UTF_8)
        }
        assertTrue(tls, tls.startsWith("HTTP/1.1 400 Bad Request\r\n"))
        assertFalse("something that was not the callback ended the wait", served.end.isDone)
        assertTrue(served.codes.isEmpty())

        val answer = ask(port, get("/callback?code=c&state=s"))
        assertTrue(answer, answer.contains("Signed in"))
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertEquals(listOf("c"), served.codes)
    }

    @Test fun `a wrong state is answered 'Sign-in failed', counted, and does not end the wait — the right one then does`() {
        val port = freePort()
        val served = serve(open(port), "expected-state")
        val wrong = ask(port, get("/callback?code=attacker-supplied-code&state=wrong-state"))
        assertTrue(wrong, wrong.startsWith("HTTP/1.1 400 Bad Request\r\n"))
        assertTrue(wrong, wrong.contains("Sign-in failed"))
        val none = ask(port, get("/callback?code=some-code"))
        assertTrue(none, none.contains("Sign-in failed"))
        assertFalse("someone else's request ended his sign-in", served.end.isDone)
        assertTrue("a code with a wrong state reached the exchange", served.codes.isEmpty())

        val right = ask(port, get("/callback?code=his-code&state=expected-state"))
        assertTrue(right, right.contains("Signed in"))
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertEquals(listOf("his-code"), served.codes)
        assertTrue(logged.toString(), logged.any { it.contains("wrong or missing state") && it.contains("(2 so far)") })
    }

    @Test fun `only wrong states and junk until the deadline - timed out, with what came counted`() {
        val port = freePort()
        val served = serve(open(port), "expected-state", deadlineMs = 900)
        ask(port, get("/callback?code=x&state=nope"))
        ask(port, get("/callback?code=&state=expected-state"))
        ask(port, get("/favicon.ico"))
        val end = ended(served) as LoopbackServer.End.TimedOut
        assertEquals(1, end.wrongStates)
        assertEquals(3, end.others)
        assertTrue(served.codes.isEmpty())
        assertFree(port)
    }

    @Test fun `the state may have something after it only where that is allowed (ChatGPT's side adds a note)`() {
        val strict = freePort()
        val servedStrict = serve(open(strict), "abc", deadlineMs = 700)
        assertTrue(ask(strict, get("/callback?code=c&state=abc%3Aonboarding")).contains("Sign-in failed"))
        assertTrue(ended(servedStrict) is LoopbackServer.End.TimedOut)

        val port = freePort()
        val served = serve(open(port, "http://127.0.0.1:$port/auth/callback", grow = true), "abc")
        assertTrue(ask(port, get("/auth/callback?code=x&state=xabc")).contains("Sign-in failed"))
        assertTrue(ask(port, get("/auth/callback?code=c&state=abc%3Aonboarding")).contains("Signed in"))
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertEquals(listOf("c"), served.codes)
    }

    // -- what ends it -------------------------------------------------------------------------------------------

    @Test fun `the sign-in page's own error ends it and is said plainly`() {
        val port = freePort()
        val served = serve(open(port), "s")
        // Without this sign-in's state an error is nobody's: ignored.
        ask(port, get("/callback?error=access_denied&state=other"))
        assertFalse(served.end.isDone)
        val answer = ask(port, get("/callback?error=access_denied&error_description=The+user+said+no&state=s"))
        assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(answer, answer.contains("Sign-in failed") && answer.contains("left without allowing it"))
        assertEquals("access_denied", (ended(served) as LoopbackServer.End.Refused).error)
        assertTrue(served.codes.isEmpty())
        assertFree(port)
    }

    @Test fun `an error that is not a short word is not passed on`() {
        assertEquals("access_denied", LoopbackServer.errorWord("access_denied"))
        assertEquals("an error it did not name", LoopbackServer.errorWord("<script>alert(1)</script>"))
        assertEquals("an error it did not name", LoopbackServer.errorWord("a".repeat(65)))
    }

    @Test fun `the exchange's failure is on the browser's page — it does not say 'Signed in' before it is true`() {
        val port = freePort()
        val served = serve(open(port), "s", said = "The browser's answer came, but ChatGPT did not accept its code (HTTP 400 (invalid_grant)).")
        val answer = ask(port, get("/callback?code=c&state=s"))
        assertTrue(answer, answer.contains("Sign-in failed"))
        assertTrue(answer, answer.contains("did not accept its code"))
        assertFalse(answer, answer.contains("Signed in"))
        assertNotNull((ended(served) as LoopbackServer.End.Answered).failure)
    }

    @Test fun `two callbacks at once - one exchange, both answered with what it came to`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val a = connect(port)
        val b = connect(port)
        val answers = listOf(a, b).map { s -> pool.submit<String> { s.use { send(it, get("/callback?code=c&state=s")) } } }.map { it.get(10, TimeUnit.SECONDS) }
        for (answer in answers) {
            assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(answer, answer.contains("Signed in"))
        }
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertEquals("the code was exchanged more than once", listOf("c"), served.codes)
    }

    // -- time ---------------------------------------------------------------------------------------------------

    @Test fun `the callback after nine minutes is still taken (three minutes were not enough for a second factor)`() {
        val now = AtomicLong(1_000)
        val port = freePort()
        val begun = CountDownLatch(1)
        val served = serve(open(port, clock = { now.get().also { begun.countDown() } }), "s", deadlineMs = LoopbackServer.DEADLINE_MS)
        assertTrue(begun.await(5, TimeUnit.SECONDS)) // its start is taken; now the time may pass
        now.addAndGet(9 * 60_000L + 59_000L)
        Thread.sleep(500) // more than one look at the time
        assertFalse(served.end.isDone)
        assertTrue(ask(port, get("/callback?code=late&state=s")).contains("Signed in"))
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertEquals(listOf("late"), served.codes)
    }

    @Test fun `after ten minutes with nothing it is over, and the port is free`() {
        val now = AtomicLong(1_000)
        val port = freePort()
        val begun = CountDownLatch(1)
        val served = serve(open(port, clock = { now.get().also { begun.countDown() } }), "s", deadlineMs = LoopbackServer.DEADLINE_MS)
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertFalse("it ended before its time", served.end.isDone)
        now.addAndGet(LoopbackServer.DEADLINE_MS)
        val end = ended(served) as LoopbackServer.End.TimedOut
        assertEquals(0, end.others)
        assertFree(port)
        assertTrue(logged.toString(), logged.any { it.contains("deadline of 10 min passed") })
    }

    @Test fun `what queued before the time was looked at is answered first, though the time is up`() {
        // As after a freeze with the browser in front: the browser had connected and sent its request, this
        // process could not run, and the deadline passed meanwhile. The listener is open from open(): what comes
        // before serve() looks at anything waits in the kernel, as it does for a frozen process.
        val port = freePort()
        val server = open(port)
        val answer = connect(port).use { waiting ->
            waiting.getOutputStream().apply { write(get("/callback?code=c&state=s").toByteArray()); flush() }
            val served = serve(server, "s", deadlineMs = 0)
            val text = String(waiting.getInputStream().readBytes(), Charsets.UTF_8)
            assertTrue(ended(served) is LoopbackServer.End.Answered)
            assertEquals(listOf("c"), served.codes)
            text
        }
        assertTrue(answer, answer.contains("Signed in"))
        assertFree(port)
    }

    @Test fun `with the time up and nothing waiting it ends at once`() {
        val port = freePort()
        val served = serve(open(port), "s", deadlineMs = 0)
        assertTrue(ended(served) is LoopbackServer.End.TimedOut)
        assertFree(port)
    }

    @Test fun `closed from another thread it ends at once and the port is free for the next attempt`() {
        val port = freePort()
        val server = open(port)
        val served = serve(server, "s")
        Thread.sleep(150)
        val began = System.nanoTime()
        server.close()
        assertTrue(ended(served) === LoopbackServer.End.Cancelled)
        assertTrue((System.nanoTime() - began) / 1_000_000 < 2_000)
        assertFree(port)
    }

    @Test fun `after the callback the port is free`() {
        val port = freePort()
        val served = serve(open(port), "s")
        ask(port, get("/callback?code=c&state=s"))
        ended(served)
        assertFree(port)
    }

    // -- the answer ---------------------------------------------------------------------------------------------

    @Test fun `the answer is a whole HTTP response with a page that has the way back`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val raw = connect(port).use { s ->
            s.getOutputStream().apply { write(get("/callback?code=c&state=s").toByteArray()); flush() }
            s.getInputStream().readBytes()
        }
        ended(served)
        val text = String(raw, Charsets.UTF_8)
        val split = text.indexOf("\r\n\r\n")
        assertTrue("no blank line after the headers", split > 0)
        val lines = text.substring(0, split).split("\r\n")
        assertEquals("HTTP/1.1 200 OK", lines[0])
        val headers = lines.drop(1).associate { it.substringBefore(": ").lowercase() to it.substringAfter(": ") }
        assertEquals("text/html; charset=utf-8", headers["content-type"])
        assertEquals("close", headers["connection"])
        assertEquals("no-store", headers["cache-control"])
        val body = raw.copyOfRange(String(raw, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4, raw.size)
        assertEquals("Content-Length must be the body's bytes", body.size, headers["content-length"]!!.toInt())
        val html = String(body, Charsets.UTF_8)
        assertTrue(html, html.startsWith("<!doctype html>"))
        assertTrue(html, html.contains("<h1>Signed in</h1>"))
        assertTrue(html, html.contains("""<a href="dev.chicken.rear.dev://signin">Back to Chicken Rear</a>"""))
        assertTrue("the page follows the phone's light or dark", html.contains("prefers-color-scheme:dark") && html.contains("""name="color-scheme" content="light dark""""))
        assertFalse("the page runs no script", html.contains("<script"))
    }

    @Test fun `a request that arrives in pieces is read whole before it is answered`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val answer = connect(port).use { s ->
            val out = s.getOutputStream()
            out.write("GET /callback?code=c&st".toByteArray()); out.flush()
            Thread.sleep(150)
            out.write("ate=s HTTP/1.1\r\nHost: localhost\r\n".toByteArray()); out.flush()
            Thread.sleep(150)
            out.write("Accept: */*\r\n\r\n".toByteArray()); out.flush()
            String(s.getInputStream().readBytes(), Charsets.UTF_8)
        }
        assertTrue(answer, answer.contains("Signed in"))
        assertTrue(ended(served) is LoopbackServer.End.Answered)
        assertEquals(listOf("c"), served.codes)
    }

    // -- what a request is --------------------------------------------------------------------------------------

    private fun kind(request: String?, whole: Boolean = true, path: String = "/auth/callback", state: String = "st", grow: Boolean = true) =
        LoopbackServer.classify(request, whole, path, state, grow)

    @Test fun `what each request is told to be`() {
        assertTrue(kind(null) === LoopbackServer.Ask.Silent)
        assertTrue(kind("GET /auth/callb", whole = false) === LoopbackServer.Ask.Unfinished)
        assertTrue(kind("GET /auth/callback?code=c&state=st HTTP/1.1\r\nHost: x\r\n", whole = false) === LoopbackServer.Ask.Unfinished)
        assertTrue(kind(String(byteArrayOf(0x16, 0x03, 0x01), Charsets.ISO_8859_1), whole = false) === LoopbackServer.Ask.NotHttp)
        assertTrue(kind("hello there\r\n\r\n") === LoopbackServer.Ask.NotHttp)
        assertEquals("HEAD", (kind("HEAD /auth/callback?code=c&state=st HTTP/1.1\r\n\r\n") as LoopbackServer.Ask.Method).kind)
        assertEquals("non-GET", (kind("DELETE /auth/callback HTTP/1.1\r\n\r\n") as LoopbackServer.Ask.Method).kind)
        assertTrue(kind("GET /favicon.ico HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.Favicon)
        assertTrue(kind("GET /callback?code=c&state=st HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.OtherPath)
        assertTrue(kind("GET /auth/callback/?code=c&state=st HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.OtherPath)
        assertTrue(kind("GET /auth/callback?code=c&state=%zz HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.Malformed)
        assertTrue(kind("GET /auth/callback?code=c&state=st&state=st HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.Malformed)
        assertTrue(kind("GET /auth/callback?code=c&code=d&state=st HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.Malformed)
        assertTrue(kind("GET /auth/callback?code=c HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.WrongState)
        assertTrue(kind("GET /auth/callback?code=c&state= HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.WrongState)
        assertTrue(kind("GET /auth/callback?code=c&state=s HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.WrongState)
        assertTrue(kind("GET /auth/callback?code=c&state=stx HTTP/1.1\r\n\r\n", grow = false) === LoopbackServer.Ask.WrongState)
        assertTrue(kind("GET /auth/callback?state=st HTTP/1.1\r\n\r\n") === LoopbackServer.Ask.NoCode)
        assertEquals("server_error", (kind("GET /auth/callback?error=server_error&state=st HTTP/1.1\r\n\r\n") as LoopbackServer.Ask.Error).word)
        assertEquals("a b/c", (kind("GET /auth/callback?code=a+b%2Fc&scope=openid+profile+email+offline_access&state=st%3Anote HTTP/1.1\r\n\r\n") as LoopbackServer.Ask.Code).code)
        // As a proxy would send it.
        assertEquals("c", (kind("GET http://127.0.0.1:1455/auth/callback?code=c&state=st HTTP/1.1\r\n\r\n") as LoopbackServer.Ask.Code).code)
        // A bare \n ends a line too.
        assertEquals("c", (kind("GET /auth/callback?code=c&state=st HTTP/1.0\n\n") as LoopbackServer.Ask.Code).code)
    }

    @Test fun `redirectUri is the redirect the sign-in's side is told`() {
        val port = freePort()
        assertEquals("http://localhost:$port/callback", open(port).redirectUri())
        val other = freePort()
        assertEquals("http://127.0.0.1:$other/auth/callback", open(other, ChatGptOAuth.redirectUri(other)).redirectUri())
    }

    // -- what is said -------------------------------------------------------------------------------------------

    @Test fun `no line of the log holds a code, a state, a path that was asked for or a header`() {
        val port = freePort()
        val state = "STATE-s3cr3t-9f8e7d"
        val served = serve(open(port, "http://127.0.0.1:$port/auth/callback", grow = true), state)
        val secrets = listOf(state, "CODE-s3cr3t-1a2b3c", "CODE-other-4d5e6f", "TOKEN-in-a-path", "COOKIE-s3cr3t", "wrong-STATE-77", "tester")
        connect(port).use { silent ->
            ask(port, get("/favicon.ico?TOKEN-in-a-path"))
            ask(port, get("/TOKEN-in-a-path/tester"))
            ask(port, "GET /auth/callback?code=CODE-other-4d5e6f&state=wrong-STATE-77 HTTP/1.1\r\nCookie: COOKIE-s3cr3t\r\n\r\n")
            ask(port, "POST /auth/callback?code=CODE-other-4d5e6f&state=$state HTTP/1.1\r\nContent-Length: 0\r\n\r\n")
            ask(port, "TOKEN-in-a-path tester\r\n\r\n")
            ask(port, get("/auth/callback?code=CODE-s3cr3t-1a2b3c&scope=openid+profile+email+offline_access&state=$state%3Aowner%40example.com"))
            ended(served)
            assertFalse(silent.isClosed)
        }
        assertEquals(listOf("CODE-s3cr3t-1a2b3c"), served.codes)
        val all = synchronized(logged) { logged.toList() }
        assertTrue("every step is said: $all", all.size >= 8)
        for (line in all) for (secret in secrets) assertFalse("the log holds '$secret': $line", line.contains(secret))
        for (line in all) assertFalse("the log holds a query: $line", line.contains("?") || line.contains("code=") || line.contains("state="))
        assertTrue(all.toString(), all.first().startsWith("listening on 127.0.0.1:$port"))
        assertTrue(all.toString(), all.any { it.contains("the callback came, state ok, with a code") })
        assertTrue(all.toString(), all.any { it.contains("closed after") && it.contains("the callback came") && it.contains("1 with a wrong state") })
    }

    @Test fun `it counts what came`() {
        val port = freePort()
        val served = serve(open(port), "s")
        val n = AtomicInteger()
        repeat(3) { ask(port, get("/favicon.ico")); n.incrementAndGet() }
        ask(port, get("/callback?code=c&state=s"))
        ended(served)
        assertTrue(logged.toString(), logged.any { it.contains("(3 other connections before it)") })
    }
}
