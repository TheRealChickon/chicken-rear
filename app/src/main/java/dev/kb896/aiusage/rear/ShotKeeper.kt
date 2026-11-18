package dev.kb896.aiusage.rear

class ShotKeeper {
    enum class Custodian { WAITING, APP, SHELL, BINNED }

    /** The answer to the app's claim, as an int over the binder ([code]). */
    enum class Claim(val code: Int) {
        /** Yours: save it. Also for a shot the shell does not know (it was restarted): nobody else will. */
        SAVE(0),
        /** The shell saved it at BackShots.shellPath: ask the media store to look at it, no more. */
        SCAN(1),
        /** Nothing to do: binned, or already claimed. */
        NOTHING(2);

        companion object {
            /** An answer over the binder; anything unknown is [SAVE], so a shot is never dropped for a code. */
            fun of(code: Int): Claim = entries.firstOrNull { it.code == code } ?: SAVE
        }
    }

    private class Shot(var custodian: Custodian, val handedAt: Long)
    private val shots = LinkedHashMap<String, Shot>()

    fun handOver(name: String, at: Long, send: () -> Boolean): Boolean {
        waiting(name, at)
        return send()
    }

    /** [name] waits for the app's claim from [at]. */
    @Synchronized
    fun waiting(name: String, at: Long) {
        while (shots.size >= KEPT) shots.remove(shots.keys.first())
        shots[name] = Shot(Custodian.WAITING, at)
    }

    /** The hand-over failed: true when it is still unclaimed and unbinned, and the shell's now (it saves it). */
    @Synchronized
    fun unsent(name: String): Boolean {
        val s = shots[name] ?: return false
        if (s.custodian != Custodian.WAITING) return false
        s.custodian = Custodian.SHELL
        return true
    }

    /** The shell looks again, [CLAIM_MS] after the hand-over: true when it is still unclaimed, and the shell's now. */
    @Synchronized
    fun due(name: String, now: Long): Boolean {
        val s = shots[name] ?: return false
        if (s.custodian != Custodian.WAITING || now - s.handedAt < CLAIM_MS) return false
        s.custodian = Custodian.SHELL
        return true
    }

    /** The app has the file and asks whether to save it. */
    @Synchronized
    fun claim(name: String): Claim {
        val s = shots[name] ?: return Claim.SAVE
        return when (s.custodian) {
            Custodian.WAITING -> { s.custodian = Custodian.APP; Claim.SAVE }
            Custodian.SHELL -> Claim.SCAN
            Custodian.APP, Custodian.BINNED -> Claim.NOTHING
        }
    }

    /**
     * The shell could not write [name] itself: true when the app may still save it (it holds the shot open, handed over
     * before; its claim, whenever it comes, is SAVE again). False when it was binned meanwhile, or is not the shell's.
     */
    @Synchronized
    fun failed(name: String): Boolean {
        val s = shots[name] ?: return false
        if (s.custodian != Custodian.SHELL) return false
        s.custodian = Custodian.WAITING
        return true
    }

    @Synchronized
    fun bin(name: String): Custodian? {
        val s = shots[name] ?: return null
        val had = if (s.custodian == Custodian.WAITING) Custodian.BINNED else s.custodian
        s.custodian = Custodian.BINNED
        return had
    }

    @Synchronized
    fun custodian(name: String): Custodian? = shots[name]?.custodian

    companion object {
        /**
         * How long the app has to claim a shot. An app that runs answers within tens of milliseconds; one HyperOS has
         * frozen does not answer until something thaws it, which may be minutes. At 2 s the picture is in the gallery
         * within about three seconds either way, while the polaroid still stands on the back with its bin.
         */
        const val CLAIM_MS = 2_000L
        const val KEPT = 16
    }
}
