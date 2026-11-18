package dev.kb896.aiusage.rear

class CameraWord {
    private val known = HashSet<String>()
    private val out = HashSet<String>()
    @Volatile var trusted = false
        private set
    @Volatile var blind = false
        private set
    /** When a dump was last held against the word (uptime); none yet: MIN_VALUE. */
    private var dumpedAt = Long.MIN_VALUE

    /** What a dump held against the word did to it: believed from now on, not yet, never, or no longer ([DROPPED]). */
    enum class Verdict { TRUST, NOT_YET, NEVER, DROPPED }

    /** The word: camera [id] can be opened (none has it). */
    @Synchronized fun available(id: String) { known += id; out -= id }

    /** The word: camera [id] cannot be (it is open, or gone). */
    @Synchronized fun unavailable(id: String) { known += id; out += id }

    /** Whether the word says a camera is open. */
    @Synchronized fun open(): Boolean = out.any { DIGITS.matches(it) }

    /**
     * Whether a look at [now] (uptime) takes the word alone, with no dump: only while it is believed, says no camera is
     * open, and a dump was held against it under [AUDIT_MS] ago.
     */
    @Synchronized fun byWord(now: Long): Boolean =
        trusted && !open() && dumpedAt != Long.MIN_VALUE && now - dumpedAt < AUDIT_MS

    /**
     * A [PROBE] read at [now] (uptime), held against the word; the dump's answer is the one taken either way. Not yet
     * believed: TRUST, NEVER or NOT_YET by [verdict]. Believed: DROPPED where the dump says otherwise than the word on
     * whether a camera is open (and from then on never believed), else null. Never believed: null.
     */
    @Synchronized fun held(dump: String, now: Long): Verdict? {
        dumpedAt = now
        if (blind) return null
        if (trusted) {
            if (open() == isOpen(dump)) return null
            distrust()
            return Verdict.DROPPED
        }
        val v = verdict(listedIn(dump), openIn(dump), known.filterTo(HashSet()) { DIGITS.matches(it) }, out.filterTo(HashSet()) { DIGITS.matches(it) })
        when (v) {
            Verdict.TRUST -> trusted = true
            Verdict.NEVER -> blind = true
            Verdict.NOT_YET, Verdict.DROPPED -> Unit
        }
        return v
    }

    /** Not believed any more, nor ever again: every look reads the dump, as before there was a word. */
    @Synchronized fun distrust() { trusted = false; blind = true }

    companion object {
        /** A believed word that says no camera is open is checked against the dump at the first look this long after the last dump. */
        const val AUDIT_MS = 60_000L

        /** The camera service's dump, the lines that name each camera and whether it is open. */
        const val PROBE = "dumpsys media.camera | grep -E 'Camera device [0-9]+ dynamic info|Device [0-9]+ is (open|closed)'"

        private val DIGITS = Regex("[0-9]+")
        private val OPEN = Regex("""Device ([0-9]+) is open""")
        private val LISTED = Regex("""(?:Camera device ([0-9]+) dynamic info|Device ([0-9]+) is (?:open|closed))""")

        /** Whether [dump] says a camera is open: a line as the probe counted before. */
        fun isOpen(dump: String): Boolean = OPEN.containsMatchIn(dump)

        /** The cameras [dump] says are open. */
        fun openIn(dump: String): Set<String> = OPEN.findAll(dump).map { it.groupValues[1] }.toSet()

        /** Every camera [dump] names. */
        fun listedIn(dump: String): Set<String> = LISTED.findAll(dump).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toSet()

        /**
         * The word held against one dump: [listed] and [open] by the dump, [known] and [out] (unavailable) by the word.
         * NEVER: the dump names a camera the word does not know (once the word has named any: its first statuses come a
         * moment after it is registered). TRUST: the same cameras, one or more open by the
         * dump, and exactly those unavailable by the word. Else NOT_YET (a moment between the two, or nothing open).
         */
        fun verdict(listed: Set<String>, open: Set<String>, known: Set<String>, out: Set<String>): Verdict = when {
            listed.isEmpty() || known.isEmpty() -> Verdict.NOT_YET
            !known.containsAll(listed) -> Verdict.NEVER
            listed == known && open.isNotEmpty() && open == out -> Verdict.TRUST
            else -> Verdict.NOT_YET
        }
    }
}
