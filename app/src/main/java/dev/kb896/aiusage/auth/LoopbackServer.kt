package dev.kb896.aiusage.auth

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LoopbackServer private constructor(
    val port: Int,
    private val redirect: String,
    private val stateMayGrow: Boolean,
    private val log: (String) -> Unit,
    private val clock: () -> Long,
    private val listeners: List<ServerSocket>,
) : Closeable {

    /** How [serve] ended. */
    sealed interface End {
        /** The callback came with the right state and a code, `onCode` ran. [failure]: what it said went wrong; null when it worked. */
        class Answered(val failure: String?) : End
        /** The sign-in page itself sent him back with an error (OAuth's short word, e.g. `access_denied`). */
        class Refused(val error: String) : End
        /**
         * The deadline passed. [others]: connections that were not the callback; [wrongStates]: callbacks that
         * were not this sign-in's; [crowded]: connections closed or turned away because more than [MOST_AT_ONCE]
         * were there at once, which a browser alone never is.
         */
        class TimedOut(val others: Int, val wrongStates: Int, val crowded: Int = 0) : End
        /** [close] was called before a callback had come. */
        object Cancelled : End
    }

    private val path: String = runCatching { URI(redirect).path }.getOrNull().orEmpty().ifEmpty { "/" }
    private val served = AtomicBoolean(false)
    @Volatile private var cancelled = false
    private val outcome = CompletableFuture<End>()
    private val claimed = AtomicBoolean(false)
    private val connections = AtomicInteger(0)
    private val others = AtomicInteger(0)
    private val wrongStates = AtomicInteger(0)
    /** Connections closed unfinished to make room for a newer one. */
    private val crowdedOut = AtomicInteger(0)
    /** Connections that found no room at all (every place held by a request being answered) or no thread. */
    private val turnedAway = AtomicInteger(0)

    /** One connection being served. [n]: which it was, in the order they came. [placed]: when it was given its place. */
    private class Conn(val socket: Socket, val n: Int, val placed: Long = System.nanoTime()) {
        /** How far it is: [SILENT], [PARTIAL], [HANDLING], [ANSWERED]. */
        @Volatile var state = SILENT
        /** Closed to make room for a newer one. */
        @Volatile var crowdedOut = false
        /** Its reader has begun ([readingAt] is when). */
        @Volatile var reading = false
        @Volatile var readingAt = 0L

        /**
         * May be closed for room: its reader has had [HOLD_MS] (from when IT began, not from its place: a thread
         * of a process in the background may start late, and the request waiting in the kernel would be lost
         * with it), or it has had no reader at all for [STALLED_MS].
         */
        fun hadItsMoment(now: Long): Boolean =
            if (reading) now - readingAt >= HOLD_MS * 1_000_000L else now - placed >= STALLED_MS * 1_000_000L
    }

    /** Every connection being served. Places are given and taken away under its lock. */
    private val open = ConcurrentHashMap<Socket, Conn>()
    @Volatile private var doneAt = 0L
    private var began = 0L

    fun redirectUri(): String = redirect

    /**
     * Waits for the browser to come back. [onCode] is given the code of the one callback that carries
     * [expectedState] and buys the tokens with it while the browser waits for its answer; it returns null when that
     * worked, else the plain words of what went wrong, and the browser's page says which. It runs at most once,
     * however often the callback is sent.
     */
    fun serve(expectedState: String, page: SignInPage, deadlineMs: Long = DEADLINE_MS, onCode: (String) -> String?): End {
        check(served.compareAndSet(false, true)) { "a listener serves one sign-in" }
        began = clock()
        val deadlineAt = began + deadlineMs
        // More threads than places: the thread of a connection closed for room is still on its way out when the
        // newcomer needs one.
        val pool = ThreadPoolExecutor(0, THREADS_MOST, 5, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "signin-conn").apply { isDaemon = true }
        }
        try {
            val acceptors = listeners.map { listener ->
                Thread({ accept(listener, deadlineAt, pool) { c -> handle(c, expectedState, page, onCode) } }, "signin-accept").apply {
                    isDaemon = true
                    start()
                }
            }
            acceptors.forEach { it.join() }
        } finally {
            closeListeners()
            settle(pool)
        }
        val end = outcome.getNow(null)
            ?: if (cancelled) End.Cancelled else End.TimedOut(others.get(), wrongStates.get(), crowdedOut.get() + turnedAway.get())
        log(
            "listener on port $port closed after ${secs(clock() - began)}: " + when (end) {
                is End.Answered -> "the callback came"
                is End.Refused -> "the sign-in page sent an error"
                is End.TimedOut -> "the deadline of ${deadlineMs / 60_000} min passed with no callback"
                End.Cancelled -> "cancelled"
            } + "; ${connections.get()} connections, ${others.get()} that were not the callback, ${wrongStates.get()} with a wrong state" +
                (crowdedOut.get().takeIf { it > 0 }?.let { ", $it closed unfinished to make room" } ?: "") +
                (turnedAway.get().takeIf { it > 0 }?.let { ", $it turned away unread" } ?: "")
        )
        return end
    }

    /**
     * The listening is over; what is still connected is brought to an end. A callback already taken is let buy its
     * tokens and answer the browser. A connection that has said nothing is closed at once (it would otherwise hold
     * the end back for its whole reading time); one in the middle of its request gets the few seconds that takes;
     * one that has its answer and is only waiting for the browser to take it is not waited for.
     */
    private fun settle(pool: ThreadPoolExecutor) {
        pool.shutdown()
        val from = System.nanoTime()
        while (true) {
            val now = System.nanoTime()
            val exchanging = claimed.get() && !outcome.isDone
            val working = open.values.any { it.state == PARTIAL || it.state == HANDLING }
            when {
                exchanging && now - from < FINISH_MS * 1_000_000L -> Unit
                exchanging -> { closeOpen(SILENT, PARTIAL, HANDLING, ANSWERED); pool.shutdownNow(); return }
                !working -> { closeOpen(SILENT); return }
                cancelled && !claimed.get() -> { closeOpen(SILENT, PARTIAL, HANDLING); return }
                now - maxOf(from, doneAt) > (READ_MS + 1_000L) * 1_000_000L -> { closeOpen(SILENT, PARTIAL, HANDLING); return }
                else -> closeOpen(SILENT)
            }
            Thread.sleep(20)
        }
    }

    private fun closeOpen(vararg states: Int) {
        for (c in open.values) if (c.state in states) runCatching { c.socket.close() }
    }

    /** Ends the wait from any thread and frees the port at once. A callback already taken is still finished. */
    override fun close() {
        cancelled = true
        closeListeners()
    }

    private fun closeListeners() = listeners.forEach { runCatching { it.close() } }

    private fun accept(listener: ServerSocket, deadlineAt: Long, pool: ThreadPoolExecutor, each: (Conn) -> Unit) {
        var doneSince = 0L
        while (true) {
            val socket = try {
                listener.accept()
            } catch (_: SocketTimeoutException) {
                // Only with nothing waiting is the time looked at: what queued while this process could not run
                // (frozen with the browser in front) is answered first.
                if (outcome.isDone || cancelled || clock() >= deadlineAt) return
                continue
            } catch (_: IOException) {
                return // closed
            }
            val n = connections.incrementAndGet()
            // The newcomer has a place (see the class comment); only with every place held, for longer than the
            // wait for one, by a request that is being answered, or with no thread to be had, is it the one that goes.
            val conn = place(socket, n)
            if (conn == null || !begin(pool, conn, each)) {
                if (conn != null) open.remove(socket, conn)
                runCatching { socket.close() }
                if (!cancelled) {
                    others.incrementAndGet()
                    val count = turnedAway.incrementAndGet()
                    val why = if (conn == null) "every one of the $MOST_AT_ONCE places was held by a request being answered" else "no thread was to be had for it"
                    if (worthSaying(count)) log("connection $n at ${at()}: $why, closed unread ($count so far)" + stillWaiting())
                }
            }
            // After the callback, what is still queued (the page asked for twice, its favicon) is answered for a
            // moment and no longer.
            if (outcome.isDone) {
                val now = System.nanoTime()
                if (doneSince == 0L) doneSince = now
                if (now - doneSince > LINGER_MS * 1_000_000L) return
            }
        }
    }

    /**
     * A place among the connections served at once for [socket], waited for if need be; null when none came free
     * within [ROOM_WAIT_MS] (or the listener was closed meanwhile). With all taken, one that only waits for its
     * answer to be taken goes first, else the oldest whose request is not whole and that has had its moment
     * ([Conn.hadItsMoment]). Never one whose request is read and being answered.
     */
    private fun place(socket: Socket, n: Int): Conn? {
        val giveUpAt = System.nanoTime() + ROOM_WAIT_MS * 1_000_000L
        while (true) {
            var waitNs = 1_000_000L
            synchronized(open) {
                if (open.size < MOST_AT_ONCE) return Conn(socket, n).also { open[socket] = it }
                val now = System.nanoTime()
                var answered: Conn? = null
                var unfinished: Conn? = null
                for (c in open.values) when (c.state) {
                    ANSWERED -> if (answered == null || c.n < answered.n) answered = c
                    SILENT, PARTIAL -> if (c.hadItsMoment(now) && (unfinished == null || c.n < unfinished.n)) unfinished = c
                }
                val goes = answered ?: unfinished
                if (goes != null) {
                    open.remove(goes.socket, goes)
                    if (goes.state != ANSWERED) {
                        goes.crowdedOut = true
                        others.incrementAndGet()
                        val count = crowdedOut.incrementAndGet()
                        // A crowd comes hundreds a second: said by tens, not one by one.
                        if (worthSaying(count)) log("connection ${goes.n} at ${at()}: closed with its request unfinished, to make room for a newer one ($MOST_AT_ONCE at once; $count so far)" + stillWaiting())
                    }
                    runCatching { goes.socket.close() }
                    waitNs = 0L
                }
            }
            if (waitNs > 0L) {
                if (cancelled || System.nanoTime() >= giveUpAt) return null
                try { Thread.sleep((waitNs / 1_000_000L).coerceIn(1L, 5L)) } catch (_: InterruptedException) { return null }
            }
        }
    }

    /** Gives [conn] its thread. False when none was to be had within [THREAD_WAIT_MS]. */
    private fun begin(pool: ThreadPoolExecutor, conn: Conn, each: (Conn) -> Unit): Boolean {
        val task = Runnable {
            try { each(conn) } catch (_: Exception) { } finally {
                open.remove(conn.socket, conn)
                runCatching { conn.socket.close() }
            }
        }
        val by = System.nanoTime() + THREAD_WAIT_MS * 1_000_000L
        while (true) {
            try { pool.execute(task); return true } catch (_: RejectedExecutionException) {
                // Every thread busy: those of the connections just closed for room have not ended yet. A moment.
                if (conn.crowdedOut) return true // closed for a newer one meanwhile: there is nothing left to serve
                if (System.nanoTime() >= by) return false
                try { Thread.sleep(1) } catch (_: InterruptedException) { return false }
            }
        }
    }

    private fun handle(c: Conn, expectedState: String, page: SignInPage, onCode: (String) -> String?) {
        val s = c.socket
        val n = c.n
        val head = readHead(c)
        // From here it is being answered and is not closed for room any more. One that was closed unfinished to
        // make room has been counted and said where that was done.
        synchronized(open) {
            if (c.crowdedOut && !head.whole) return
            c.state = HANDLING
        }
        when (val ask = classify(head.text, head.whole, path, expectedState, stateMayGrow)) {
            Ask.Silent -> other(n, "sent nothing and was closed")
            Ask.Unfinished -> { other(n, "a request that did not finish in time"); answer(c, 408, "Request Timeout", PLAIN, "Too slow.") }
            Ask.NotHttp -> { other(n, "not an HTTP request"); answer(c, 400, "Bad Request", PLAIN, "Bad request.") }
            is Ask.Method -> {
                other(n, "a ${ask.kind} request (only GET is served)")
                answer(c, 405, "Method Not Allowed", PLAIN, if (ask.kind == "HEAD") "" else "GET only.", "Allow: GET\r\n")
            }
            Ask.Favicon -> { other(n, "the browser asking for an icon"); answer(c, 404, "Not Found", PLAIN, "Not found.") }
            Ask.OtherPath -> { other(n, "a GET of another path"); answer(c, 404, "Not Found", PLAIN, "Not found.") }
            Ask.Malformed -> { other(n, "a callback that could not be read"); answer(c, 400, "Bad Request", HTML, page.stale()) }
            Ask.NoCode -> { other(n, "a callback with the right state and no code"); answer(c, 400, "Bad Request", HTML, page.stale()) }
            Ask.WrongState -> {
                val count = wrongStates.incrementAndGet()
                others.incrementAndGet()
                log("connection $n at ${at()}: a callback with a wrong or missing state, answered 'Sign-in failed' and ignored ($count so far); still waiting")
                answer(c, 400, "Bad Request", HTML, page.stale())
            }
            is Ask.Error -> {
                if (claimed.compareAndSet(false, true)) {
                    log("connection $n at ${at()}: the callback came, state ok, with the sign-in page's error '${ask.word}'")
                    finish(End.Refused(ask.word))
                }
                answer(c, 200, "OK", HTML, pageFor(page))
            }
            is Ask.Code -> {
                if (claimed.compareAndSet(false, true)) {
                    log("connection $n at ${at()}: the callback came, state ok, with a code (${others.get()} other connections before it)")
                    val failure = try { onCode(ask.code) } catch (e: Exception) { "The sign-in could not be finished (${e.javaClass.simpleName})." }
                    finish(End.Answered(failure))
                } else {
                    log("connection $n at ${at()}: the callback again; answered with what the first one came to, no second exchange")
                }
                answer(c, 200, "OK", HTML, pageFor(page))
            }
        }
    }

    private fun finish(end: End) {
        doneAt = System.nanoTime()
        outcome.complete(end)
    }

    /** The page for what the sign-in came to; a second asker waits for the first one's exchange. */
    private fun pageFor(page: SignInPage): String {
        val end = try { outcome.get(FINISH_MS, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
        return when (end) {
            is End.Answered -> end.failure?.let(page::failed) ?: page.signedIn()
            is End.Refused -> page.refused(end.error)
            else -> page.failed("The sign-in is still being finished. Go back and look.")
        }
    }

    private fun other(n: Int, what: String) {
        others.incrementAndGet()
        log("connection $n at ${at()}: $what" + stillWaiting())
    }

    private fun stillWaiting() = if (outcome.isDone) "" else "; still waiting"

    private fun at() = "+" + secs(clock() - began)

    private class Head(val text: String?, val whole: Boolean)

    /** The request up to its blank line, within [READ_MS] for all of it and [HEAD_MOST] bytes. */
    private fun readHead(c: Conn): Head {
        val s = c.socket
        val started = System.nanoTime()
        c.readingAt = started
        c.reading = true
        val bytes = java.io.ByteArrayOutputStream(1024)
        val chunk = ByteArray(2048)
        try {
            val input = s.getInputStream()
            while (bytes.size() < HEAD_MOST) {
                val left = READ_MS - (System.nanoTime() - started) / 1_000_000L
                if (left <= 0) break
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val got = input.read(chunk)
                if (got < 0) break
                if (got > 0 && c.state == SILENT) c.state = PARTIAL
                bytes.write(chunk, 0, got)
                val text = bytes.toString(Charsets.ISO_8859_1.name())
                if (text.contains("\r\n\r\n") || text.contains("\n\n")) return Head(text, true)
            }
        } catch (_: IOException) {
            // timed out or broken off: what there is says what kind it was
        }
        return Head(if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name()), false)
    }

    /**
     * One whole answer, then the socket is let go only when the other side has taken it (or a second has passed):
     * closing with anything unread resets the connection and loses the answer.
     */
    private fun answer(c: Conn, status: Int, reason: String, type: String, body: String, extra: String = "") {
        val s = c.socket
        try {
            val payload = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
                extra +
                "Connection: close\r\n\r\n"
            s.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(payload)
                flush()
            }
            s.shutdownOutput()
            c.state = ANSWERED
            val until = System.nanoTime() + CLOSE_MS * 1_000_000L
            val sink = ByteArray(512)
            s.soTimeout = CLOSE_MS.toInt()
            val input = s.getInputStream()
            while (System.nanoTime() < until && input.read(sink) >= 0) { /* until the other side closes */ }
        } catch (_: IOException) {
            // The answer is a courtesy to the browser; the sign-in does not depend on its arriving.
        }
    }

    /** What one connection asked, as far as this listener tells things apart. Never kept, never logged with its content. */
    internal sealed interface Ask {
        object Silent : Ask
        object Unfinished : Ask
        object NotHttp : Ask
        class Method(val kind: String) : Ask
        object Favicon : Ask
        object OtherPath : Ask
        object Malformed : Ask
        object WrongState : Ask
        object NoCode : Ask
        class Error(val word: String) : Ask
        class Code(val code: String) : Ask
    }

    companion object {
        /** Ten minutes: a password, a password manager, a second factor, a consent page. */
        const val DEADLINE_MS = 10 * 60_000L
        /** One connection's whole request must be here within this. */
        private const val READ_MS = 4_000L
        private const val HEAD_MOST = 16 * 1024
        /** How long an accept waits before the time is looked at. */
        private const val POLL_MS = 200
        /** How many connections are served at once. A browser makes a few: the page, its icon, one it opened ahead. */
        internal const val MOST_AT_ONCE = 32
        /**
         * How long a connection has its place before it may be closed, its request unfinished, to make room for a
         * newer one. A browser's request is there within a millisecond or two of its connecting; this is that, with
         * room for a busy phone. It is also the pace of a crowd: [MOST_AT_ONCE] new connections per this.
         */
        internal const val HOLD_MS = 100L
        /** A connection whose reader has not begun this long after it was given its place may be closed for room all the same. */
        private const val STALLED_MS = 1_000L
        /** How long a newcomer waits for a place when every one is held by a request being answered (the exchange, a second asker waiting for it). */
        private const val ROOM_WAIT_MS = 500L
        /** Threads for them: the places, and as many again for connections closed for room that are still ending. */
        private const val THREADS_MOST = 2 * MOST_AT_ONCE
        /** How long a newcomer waits for one of those threads to end before it is turned away. */
        private const val THREAD_WAIT_MS = 250L
        /** After the callback: how long what was already queued is still answered. */
        private const val LINGER_MS = 1_000L
        /** How long a callback's exchange may still run after the listening has ended, and a second asker waits for it. */
        private const val FINISH_MS = 60_000L
        /** How long the browser is given to take an answer before the socket is closed anyway. */
        private const val CLOSE_MS = 1_000L
        /** Nothing has come from it. */
        private const val SILENT = 0
        /** Part of a request has come. */
        private const val PARTIAL = 1
        /** Its request is read (or given up on) and is being answered: the callback's exchange is here. */
        private const val HANDLING = 2
        /** It has its answer and only waits for the other side to take it. */
        private const val ANSWERED = 3
        private const val PLAIN = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"

        /** Milliseconds that only go forward. */
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000_000L }

        /**
         * 127.0.0.1 by its bytes. NOT `InetAddress.getLoopbackAddress()`: on Android that is ::1, and a redirect to
         * the literal 127.0.0.1 (ChatGPT's) then finds nobody. See the class comment.
         */
        val V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        /** ::1, for a browser that takes the name `localhost` (Claude's redirect) there first. */
        val V6: InetAddress = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })

        /**
         * Listens on [port] of 127.0.0.1 (and of ::1 where that can be had); null when 127.0.0.1's is taken. The
         * bind itself is the check that the port is free: nothing is probed first and found gone a moment later.
         */
        fun open(
            port: Int = 54545,
            redirect: String = "http://localhost:$port/callback",
            /**
             * The state may come back with something added after it (ChatGPT's side appends a note about its own
             * onboarding to the otherwise exact state; its Codex client strips that too). The state itself must
             * still be there, whole and first.
             */
            stateMayGrow: Boolean = false,
            log: (String) -> Unit = {},
            clock: () -> Long = MONOTONIC,
        ): LoopbackServer? {
            val v4 = try { listen(port, V4) } catch (_: IOException) { return null }
            // The other loopback address is taken where it can be: a phone without IPv6, or the port held there, leaves 127.0.0.1 alone.
            val v6 = try { listen(port, V6) } catch (_: IOException) { null }
            val server = LoopbackServer(port, redirect, stateMayGrow, log, clock, listOfNotNull(v4, v6))
            log("listening on 127.0.0.1:$port" + (if (v6 != null) " and [::1]:$port" else " ([::1]:$port could not be had)") + " for GET ${server.path}")
            return server
        }

        private fun listen(port: Int, address: InetAddress) = ServerSocket(port, 16, address).apply { soTimeout = POLL_MS }

        private fun secs(ms: Long) = "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)

        /** The 1st, 10th, 100th… of something that may come thousands of times a second: the log stays readable. */
        internal fun worthSaying(count: Int): Boolean {
            var step = 1
            while (step < count && step <= Int.MAX_VALUE / 10) step *= 10
            return step == count
        }

        /** OAuth's `error` is a short word (`access_denied`); anything else is not passed on. */
        internal fun errorWord(raw: String): String =
            if (raw.length in 1..64 && raw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }) raw else "an error it did not name"

        private fun sameState(got: String?, expected: String, mayGrow: Boolean): Boolean {
            if (got == null || expected.isEmpty()) return false
            val part = if (mayGrow && got.length > expected.length) got.substring(0, expected.length) else got
            return MessageDigest.isEqual(part.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        }

        /** Tells what [head] (a request up to its blank line, or what came of one) asks. */
        internal fun classify(head: String?, whole: Boolean, callbackPath: String, expectedState: String, stateMayGrow: Boolean): Ask {
            if (head.isNullOrEmpty()) return Ask.Silent
            val line = head.lineSequence().first().trimEnd('\r')
            val parts = line.split(' ')
            val http = parts.size == 3 && parts[2].startsWith("HTTP/") && parts[0].isNotEmpty() && parts[0].all { it in 'A'..'Z' }
            if (!http) return if (!whole && !head.contains('\n') && head.all { it in ' '..'~' }) Ask.Unfinished else Ask.NotHttp
            if (!whole) return Ask.Unfinished
            if (parts[0] != "GET") return Ask.Method(if (parts[0] == "HEAD" || parts[0] == "OPTIONS" || parts[0] == "POST") parts[0] else "non-GET")
            // origin-form, or absolute-form as a proxy would send it
            val target = parts[1].let { t -> if (t.startsWith("http://")) "/" + t.removePrefix("http://").substringAfter('/', "") else t }
            val asked = target.substringBefore('?')
            if (asked != callbackPath) return if (asked == "/favicon.ico") Ask.Favicon else Ask.OtherPath
            val params = HashMap<String, String>()
            for (pair in target.substringAfter('?', "").split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=')
                val value = try { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") } catch (_: IllegalArgumentException) { return Ask.Malformed }
                // A parameter given twice is nobody's honest callback.
                if (params.put(key, value) != null && key in setOf("code", "state", "error")) return Ask.Malformed
            }
            if (!sameState(params["state"], expectedState, stateMayGrow)) return Ask.WrongState
            params["error"]?.takeIf { it.isNotEmpty() }?.let { return Ask.Error(errorWord(it)) }
            val code = params["code"]
            return if (code.isNullOrEmpty()) Ask.NoCode else Ask.Code(code)
        }
    }
}
