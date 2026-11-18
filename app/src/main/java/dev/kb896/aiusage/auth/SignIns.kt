package dev.kb896.aiusage.auth

import dev.kb896.aiusage.data.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class SignIns(
    private val flow: (Account) -> Flow,
    /** Opens the sign-in page in his browser; false when no browser took it. */
    private val openBrowser: (String) -> Boolean,
    /** Tokens are stored: fresh figures for the back. */
    private val signedIn: (Account) -> Unit,
    private val notes: Notes,
    private val appName: String,
    /** The address that opens this app from the browser's page (ui/SignInReturnActivity); null for none. */
    private val backLink: String?,
    private val log: (String) -> Unit,
    private val deadlineMs: Long = LoopbackServer.DEADLINE_MS,
    private val clock: () -> Long = LoopbackServer.MONOTONIC,
    private val wall: () -> Long = System::currentTimeMillis,
) {
    /** How one kind of account signs in. */
    class Flow(
        /** Whose page the browser shows, as a person calls it: "ChatGPT", "Claude". */
        val service: String,
        /** The ports its side accepts a redirect to, in the order to try them. */
        val ports: List<Int>,
        val redirect: (port: Int) -> String,
        val stateMayGrow: Boolean,
        val authorizeUrl: (PkceChallenge, redirectUri: String) -> String,
        /**
         * Buys the tokens with the code and stores them. Sent ONCE. Throws [OAuthClient.Failure] or
         * [ChatGptOAuth.Failure], whose messages hold an HTTP status and OAuth's error word and never a secret.
         */
        val exchange: (code: String, challenge: PkceChallenge, redirectUri: String) -> Unit,
    )

    /** A few words kept past the process: the mark of a waiting sign-in, and each account's last failure. */
    interface Notes {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    /** The last sign-in that ended: [failure] null when it worked. [n] tells one from the next. */
    data class Finished(val account: Account, val failure: String?, val n: Int)

    data class State(
        /** The account whose sign-in waits for the browser; null for none. */
        val waiting: Account? = null,
        /** Per account, why its last sign-in did not work, in words for the settings row. Gone when one works or begins. */
        val failures: Map<Account, String> = emptyMap(),
        val last: Finished? = null,
        /** Which attempt the waiting one is, counted from the app's start; 0 while none waits. Tells a new wait from the one before. */
        val attempt: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    private val lock = Any()
    private var current: Run? = null
    private var serial = 0
    private var shown = 0

    init {
        // A sign-in that waited when the process went: its browser's answer found nobody. Said, not mended.
        val cut = notes.get(PENDING)?.split('|')
        val cutAccount = Account.fromSlug(cut?.getOrNull(0))
        if (cutAccount != null) {
            val ago = cut?.getOrNull(1)?.toLongOrNull()?.let { (wall() - it) / 1000 }
            notes.put(PENDING, null)
            notes.put(failureKey(cutAccount), RESTARTED)
            log("${cutAccount.slug}: a sign-in begun ${ago ?: "?"} s ago was cut off by the app's process restarting; its code can no longer be used")
        }
        _state.value = State(failures = Account.entries.mapNotNull { a -> notes.get(failureKey(a))?.let { a to it } }.toMap())
    }

    /** Begins [account]'s sign-in in the browser. One that still waits (any account's) is ended first. */
    fun start(account: Account) {
        val run = synchronized(lock) {
            current?.let { old ->
                log("${old.account.slug}: sign-in ${old.n} ended for a new one")
                old.cancel()
            }
            Run(account, ++serial, clock()).also { current = it }
        }
        notes.put(failureKey(account), null)
        notes.put(PENDING, "${account.slug}|${wall()}")
        _state.update { it.copy(waiting = account, failures = it.failures - account, attempt = run.n) }
        Thread({ run.go() }, "signin").apply { isDaemon = true }.start()
    }

    /**
     * He gives up waiting: the listener is closed, nothing is said about it afterwards. True when that was done (or
     * nothing waited). False, with the sign-in going on, in its first [CANCEL_AFTER_MS]: see the class comment.
     */
    fun cancel(): Boolean {
        synchronized(lock) {
            val old = current ?: return true
            val since = clock() - old.began
            if (since < CANCEL_AFTER_MS) {
                log("${old.account.slug}: a tap on Cancel $since ms after sign-in ${old.n} began is taken as a second tap on Sign in, not as giving up; still waiting")
                return false
            }
            current = null
            log("${old.account.slug}: sign-in ${old.n} cancelled by hand")
            old.cancel()
        }
        notes.put(PENDING, null)
        _state.update { it.copy(waiting = null, attempt = 0) }
        return true
    }

    /** How long until [cancel] acts on the sign-in that waits; 0 when it does now, or when none waits. */
    fun cancelInMs(): Long = synchronized(lock) {
        val run = current ?: return 0L
        (CANCEL_AFTER_MS - (clock() - run.began)).coerceIn(0L, CANCEL_AFTER_MS)
    }

    /** Signed out: its old failure line has nothing to say any more. */
    fun forget(account: Account) {
        notes.put(failureKey(account), null)
        _state.update { it.copy(failures = it.failures - account) }
    }

    /** True once for each ended sign-in: for the one toast, whichever screen is there to show it. */
    fun takeToast(finished: Finished): Boolean = synchronized(lock) { (finished.n > shown).also { if (it) shown = finished.n } }

    private inner class Run(val account: Account, val n: Int, /** When he tapped, by [clock]. */ val began: Long) {
        private var server: LoopbackServer? = null
        private var cancelled = false

        fun cancel() = synchronized(this) {
            cancelled = true
            server?.close()
        }

        fun go() {
            val f = flow(account)
            log("${account.slug}: sign-in $n begins")
            val listener = synchronized(this) {
                if (cancelled) return
                f.ports.firstNotNullOfOrNull { port ->
                    LoopbackServer.open(port, f.redirect(port), f.stateMayGrow, log, clock)
                        .also { if (it == null) log("${account.slug}: port $port is taken on this phone") }
                }.also { server = it }
            } ?: return end(began, portsTaken(f))
            val challenge = PkceChallenge.generate()
            val redirectUri = f.redirect(listener.port)
            val opened = try { openBrowser(f.authorizeUrl(challenge, redirectUri)) } catch (_: Exception) { false }
            if (!opened) {
                listener.close()
                return end(began, "No browser could be opened for ${f.service}'s sign-in page.")
            }
            log("${account.slug}: the browser was asked for ${f.service}'s sign-in page; waiting up to ${deadlineMs / 60_000} min on port ${listener.port}")
            val served = listener.serve(challenge.state, SignInPage(appName, f.service, backLink), deadlineMs) { code ->
                val asked = clock()
                try {
                    f.exchange(code, challenge, redirectUri)
                    log("${account.slug}: exchange done in ${clock() - asked} ms; the sign-in is stored")
                    null
                } catch (e: RuntimeException) {
                    val said = if (e is OAuthClient.Failure || e is ChatGptOAuth.Failure) e.message.orEmpty() else e.javaClass.simpleName
                    log("${account.slug}: exchange failed after ${clock() - asked} ms: $said")
                    exchangeWords(f.service, e)
                }
            }
            when (served) {
                is LoopbackServer.End.Answered -> end(began, served.failure)
                is LoopbackServer.End.Refused -> end(began, SignInPage.refusedWords(f.service, served.error))
                is LoopbackServer.End.TimedOut -> end(
                    began,
                    "Timed out after ${deadlineMs / 60_000} minutes: the browser's answer did not reach the app" +
                        (if (served.wrongStates > 0) " (${served.wrongStates} came that belonged to another attempt)." else ".") +
                        // More at once than a browser makes: something else on the phone was at the sign-in's port.
                        (if (served.crowded > 0) " Another app on this phone kept connecting to the sign-in's port (${served.crowded} of its connections were closed to make room)." else ""),
                )
                LoopbackServer.End.Cancelled -> log("${account.slug}: sign-in $n is over, cancelled after ${(clock() - began) / 1000} s")
            }
        }

        /** [cause] null: signed in. */
        private fun end(began: Long, cause: String?) {
            val failure = cause?.let { "$it Sign in again." }
            val mine = synchronized(lock) { (current === this).also { if (it) current = null } }
            log("${account.slug}: sign-in $n is over after ${(clock() - began) / 1000} s: " + (cause ?: "signed in"))
            // Tokens are stored whoever waits now; a failure of an attempt he has already replaced is not his news.
            if (failure != null && !mine) return
            notes.put(failureKey(account), failure)
            if (mine) notes.put(PENDING, null)
            _state.update {
                it.copy(
                    waiting = if (mine) null else it.waiting,
                    attempt = if (mine) 0 else it.attempt,
                    failures = if (failure == null) it.failures - account else it.failures + (account to failure),
                    last = Finished(account, failure, n),
                )
            }
            if (failure == null) runCatching { signedIn(account) }
        }
    }

    companion object {
        private const val PENDING = "pending"

        const val CANCEL_AFTER_MS = 2_000L
        private fun failureKey(account: Account) = "failure.${account.slug}"

        const val RESTARTED = "The app was restarted during the sign-in, so the browser's answer did not reach it. Sign in again."

        internal fun portsTaken(f: Flow): String =
            if (f.ports.size == 1) "Port ${f.ports[0]} is in use on this phone by another app, and ${f.service}'s sign-in can come back to no other. Close that app."
            else "Ports ${f.ports.joinToString(" and ")} are in use on this phone by another app, and ${f.service}'s sign-in can come back to no other. Close that app."

        /** What a failed exchange is, as one sentence: the sign-in side refusing the code, or not reached at all. */
        internal fun exchangeWords(service: String, e: Exception): String {
            val said = (if (e is OAuthClient.Failure || e is ChatGptOAuth.Failure) e.message.orEmpty() else "").removePrefix("exchange: ")
            return when {
                said.startsWith("HTTP ") -> "The browser's answer came, but $service did not accept its code ($said)."
                said.isNotEmpty() && said.none { it == ' ' } -> "The browser's answer came, but $service could not be reached ($said). Check the connection."
                said.isNotEmpty() -> "The browser's answer came, but $service's reply could not be used ($said)."
                else -> "The browser's answer came, but the sign-in could not be kept on this phone (${e.javaClass.simpleName})."
            }
        }
    }
}
