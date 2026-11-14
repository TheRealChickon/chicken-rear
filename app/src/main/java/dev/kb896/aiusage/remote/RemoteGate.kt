package dev.kb896.aiusage.remote

import java.security.MessageDigest

/**
 * Who may ask, and how often.
 *
 * THE TOKEN IS THE WHOLE OF IT, and that is a fact about Android rather than a choice. A
 * broadcast or a link from Home Assistant, Tasker or MacroDroid arrives with no name on it the
 * system vouches for: `getSentFromPackage` answers only for a sender that opted in to sharing its
 * identity, and none of the three does. A permission would have to be one they declare, which
 * they cannot. So what proves a caller is a secret only it and this app hold — one per caller, so
 * that one can be taken back without the others noticing, and each with its own list of what it
 * may do ([RemoteScope]).
 *
 * WHERE A SENDER DOES SAY WHO IT IS, it is believed against the token and never instead of it: a
 * token pinned to a package refuses a different named sender, and accepts an unnamed one.
 *
 * TWO LIMITS, for two different things. A wrong token counts towards a lockout that doubles, so
 * guessing is hopeless long before it is merely slow (the token is 256 bits; this is for the log's
 * sake and the battery's). A right token is still held to a bucket, because a looping automation
 * is a likelier flood than an attacker, and each command wakes three processes.
 *
 * Pure, with the clock passed in: all of this is sequences over time, which is what tests are for.
 */
class RemoteGate(
    private val bucketSize: Int = BUCKET_SIZE,
    private val refillPerSecond: Double = REFILL_PER_SECOND,
) {

    /** A caller's token as the gate needs it: never the secret itself, only its digest. */
    class Token(
        val id: String,
        val name: String,
        /** SHA-256 of the secret ([digest]). */
        val hash: ByteArray,
        val scopes: Set<RemoteScope>,
        /** A package this token is only for, when a sender names itself; null for any. */
        val pinnedTo: String? = null,
    )

    sealed interface Verdict {
        data class Allowed(val token: Token) : Verdict
        /** [reason] is one of the constants below: for the log, and the caller's result. */
        data class Denied(val reason: String, val token: Token? = null) : Verdict
    }

    private class Bucket(var level: Double, var at: Long)
    private val buckets = HashMap<String, Bucket>()

    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L
    private var lockMs = LOCK_FIRST_MS

    /**
     * May this be done? [enabled] is the feature's own switch; [presented] the token as it came;
     * [sender] the package the system named, or null when it named none; [scope] what the
     * command needs.
     */
    @Synchronized
    fun check(
        enabled: Boolean, tokens: List<Token>, presented: String?, sender: String?,
        scope: RemoteScope, nowMs: Long,
    ): Verdict {
        if (!enabled) return Verdict.Denied(OFF)
        // Locked out: nothing is even compared, so a flood of guesses costs a clock read each.
        if (nowMs < lockedUntil) return Verdict.Denied(LOCKED_OUT)
        if (presented.isNullOrEmpty()) return failed(nowMs, NO_TOKEN)
        val hash = digest(presented)
        // Every token is compared, the match kept: no early exit to time.
        var match: Token? = null
        for (t in tokens) if (MessageDigest.isEqual(t.hash, hash)) match = t
        val token = match ?: return failed(nowMs, BAD_TOKEN)
        if (token.pinnedTo != null && sender != null && sender != token.pinnedTo) {
            return Verdict.Denied(WRONG_SENDER, token)
        }
        if (scope !in token.scopes) return Verdict.Denied(NO_SCOPE, token)
        if (!take(token.id, nowMs)) return Verdict.Denied(TOO_FAST, token)
        return Verdict.Allowed(token)
    }

    private fun failed(nowMs: Long, reason: String): Verdict {
        failures.addLast(nowMs)
        while (failures.isNotEmpty() && nowMs - failures.first() > FAILURE_WINDOW_MS) failures.removeFirst()
        if (failures.size >= FAILURES_TO_LOCK) {
            failures.clear()
            lockedUntil = nowMs + lockMs
            lockMs = (lockMs * 2).coerceAtMost(LOCK_MAX_MS)
        }
        return Verdict.Denied(reason)
    }

    private fun take(id: String, nowMs: Long): Boolean {
        val b = buckets.getOrPut(id) { Bucket(bucketSize.toDouble(), nowMs) }
        b.level = (b.level + (nowMs - b.at).coerceAtLeast(0) / 1000.0 * refillPerSecond).coerceAtMost(bucketSize.toDouble())
        b.at = nowMs
        if (b.level < 1.0) return false
        b.level -= 1.0
        // A caller that behaves earns the lockout's patience back.
        lockMs = LOCK_FIRST_MS
        return true
    }

    companion object {
        const val OFF = "remote control is off"
        const val LOCKED_OUT = "locked out after wrong tokens"
        const val NO_TOKEN = "no token"
        const val BAD_TOKEN = "wrong token"
        const val WRONG_SENDER = "token is for another app"
        const val NO_SCOPE = "token may not do that"
        const val TOO_FAST = "too many commands"

        /** Thirty at once, three a second after: a dashboard's burst fits, a runaway loop does not. */
        const val BUCKET_SIZE = 30
        const val REFILL_PER_SECOND = 3.0

        const val FAILURES_TO_LOCK = 5
        const val FAILURE_WINDOW_MS = 60_000L
        const val LOCK_FIRST_MS = 60_000L
        const val LOCK_MAX_MS = 15 * 60_000L

        fun digest(secret: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
    }
}
