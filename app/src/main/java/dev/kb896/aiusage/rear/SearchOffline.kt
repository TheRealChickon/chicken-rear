package dev.kb896.aiusage.rear

import android.util.Log
import java.io.File

object SearchOffline {
    const val TAG = RearCover.RESTART_TAG
    const val PACKAGE = "com.android.quicksearchbox"
    /** This boot's record of what this app did: the boot id, then "applied" and "chain" lines. */
    const val MARKER = "/data/local/tmp/chicken-rear-search-offline"

    const val BLOCK = "cmd connectivity set-package-networking-enabled false $PACKAGE"
    const val ALLOW = "cmd connectivity set-package-networking-enabled true $PACKAGE"
    const val CHAIN_ON = "cmd connectivity set-chain3-enabled true"
    const val CHAIN_OFF = "cmd connectivity set-chain3-enabled false"
    const val READ_CHAIN = "cmd connectivity get-chain3-enabled"
    const val READ_PACKAGE = "cmd connectivity get-package-networking-enabled $PACKAGE"

    /** How often a lit phone looks whether the block still holds. */
    const val CHECK_EVERY_MS = 60 * 60_000L
    /** While no screen is lit: how often the thread looks whether one is (a binder read; the front coming on pokes it sooner). */
    const val DARK_LOOK_MS = 10 * 60_000L

    // ---- reading what the system says ------------------------------------------------------------------------------

    private val CHAIN_LINE = Regex("""^chain\s*:\s*(enabled|disabled)$""", RegexOption.IGNORE_CASE)

    /** Whether chain 3 is on, from `get-chain3-enabled` ("chain:enabled"); null for anything else (help text, an error). Pure. */
    fun chainOn(out: String?): Boolean? = out?.lineSequence()?.map { it.trim() }
        ?.firstNotNullOfOrNull { CHAIN_LINE.matchEntire(it) }?.groupValues?.get(1)?.lowercase()?.let { it == "enabled" }

    /** Whether the package's networking is denied, from `get-package-networking-enabled` ("pkg:deny" / "pkg:allow"); null otherwise. Pure. */
    fun denied(out: String?, pkg: String = PACKAGE): Boolean? {
        val line = Regex("""^${Regex.escape(pkg)}\s*:\s*(allow|deny)$""", RegexOption.IGNORE_CASE)
        return out?.lineSequence()?.map { it.trim() }?.firstNotNullOfOrNull { line.matchEntire(it) }
            ?.groupValues?.get(1)?.lowercase()?.let { it == "deny" }
    }

    enum class Hold { HOLDS, GONE, UNKNOWN }

    /** The block holds only with the chain on AND the package denied; either one read as not = gone. Pure. */
    fun hold(chain: Boolean?, denied: Boolean?): Hold = when {
        chain == false || denied == false -> Hold.GONE
        chain == true && denied == true -> Hold.HOLDS
        else -> Hold.UNKNOWN
    }

    private val REFUSED = listOf("exception", "error", "not allowed", "permission", "unknown command", "usage:", "failure", "inaccessible or not found")

    /**
     * Why a `set-…` command's output reads as refused, or null: the commands print nothing on success (some builds a
     * short confirmation), so only words of an error count, and the reason is its first line. Pure.
     */
    fun refusal(out: String?): String? {
        val text = out?.trim().orEmpty()
        if (text.isEmpty()) return null
        val low = text.lowercase()
        if (REFUSED.none { it in low }) return null
        return text.lineSequence().map { it.trim() }.first { it.isNotEmpty() }.take(120)
    }

    /**
     * What an apply came to: [HOLDS] read back = done whatever the commands printed; read back [Hold.GONE] = refused
     * (the command's own words, else "the block did not take"); not readable = done unless a command's output was an
     * error. Null for done, else the reason. Pure.
     */
    fun outcome(after: Hold, outputs: List<String?>): String? {
        val said = outputs.firstNotNullOfOrNull { refusal(it) }
        return when (after) {
            Hold.HOLDS -> null
            Hold.GONE -> "the phone refused it (${said ?: "the block did not take"})"
            Hold.UNKNOWN -> said?.let { "the phone refused it ($it)" }
        }
    }

    // ---- this boot's record ----------------------------------------------------------------------------------------

    /** What this app did in [boot]: [applied] the block (not taken off since), [chainByUs] switched chain 3 on itself. */
    data class Marker(val boot: String, val applied: Boolean, val chainByUs: Boolean)

    fun encode(m: Marker): String = "${m.boot}\napplied=${m.applied}\nchain=${m.chainByUs}\n"

    fun decode(text: String?): Marker? {
        val lines = text?.lines()?.map { it.trim() }?.takeIf { it.isNotEmpty() && it[0].isNotEmpty() } ?: return null
        fun flag(k: String) = lines.drop(1).firstOrNull { it.startsWith("$k=") }?.substringAfter('=') == "true"
        return Marker(lines[0], flag("applied"), flag("chain"))
    }

    /** The record as it counts in [boot]: one from another boot is nothing (a restart cleared the block and the chain). Pure. */
    fun thisBoot(m: Marker?, boot: String): Marker? = m?.takeIf { it.boot == boot }

    /**
     * Who switched chain 3 on, after an apply that found it [chainWas] ([kept]: this boot's record before): this app if
     * it was off and we turned it on; if it was on, whoever had it on before (us only if our record says so); not
     * readable, as the record says. Pure.
     */
    fun chainByUs(chainWas: Boolean?, kept: Marker?): Boolean = when (chainWas) {
        false -> true
        else -> kept?.chainByUs == true
    }

    // ---- what to do next -------------------------------------------------------------------------------------------

    /** Why an apply is made, in its log line's words. */
    enum class Why(val words: String) {
        START("applied after start"),
        SWITCHED_ON("switched on"),
        GONE("applied again: the block had gone"),
        UNREADABLE("applied again: its state could not be read"),
    }

    sealed class Step {
        data class Apply(val why: Why) : Step()
        /** Read whether the block holds; then [afterCheck]. */
        object Check : Step()
        /** Networking back for the package; chain 3 off too when [chainToo]. */
        data class Release(val chainToo: Boolean) : Step()
        object Wait : Step()
    }

    /**
     * The next step. [wanted]: the switch as the app's settings say (null: no settings heard yet, nothing done);
     * [lit]: the front or the back is on; [due]: an apply owed (as the process starts, or switched on); [ours]: this
     * boot's record; [sinceCheckMs]: since the last apply or check (null: none in this process). Pure.
     */
    fun next(wanted: Boolean?, lit: Boolean, due: Why?, ours: Marker?, sinceCheckMs: Long?): Step = when {
        wanted == null || !lit -> Step.Wait
        wanted && due != null -> Step.Apply(due)
        wanted && (sinceCheckMs == null || sinceCheckMs >= CHECK_EVERY_MS) -> Step.Check
        !wanted && ours?.applied == true -> Step.Release(ours.chainByUs)
        else -> Step.Wait
    }

    /** After a check: the apply it calls for, or null when the block holds. Pure. */
    fun afterCheck(h: Hold): Why? = when (h) {
        Hold.HOLDS -> null
        Hold.GONE -> Why.GONE
        Hold.UNKNOWN -> Why.UNREADABLE
    }

    /** The apply owed when the switch goes from [was] to [now] (null was = the first settings heard), given [due] before. Pure. */
    fun dueAfter(was: Boolean?, now: Boolean, due: Why?): Why? = when {
        now && was == false -> Why.SWITCHED_ON
        else -> due
    }

    /** How long the thread waits before it looks again. Pure. */
    fun waitMs(wanted: Boolean?, lit: Boolean, sinceCheckMs: Long?): Long = when {
        wanted == true && lit && sinceCheckMs != null -> (CHECK_EVERY_MS - sinceCheckMs).coerceIn(1_000L, CHECK_EVERY_MS)
        wanted == true && lit -> 1_000L
        else -> DARK_LOOK_MS
    }

    // ---- on the phone ----------------------------------------------------------------------------------------------

    /**
     * The shell's side: one daemon thread. [lit]: the front or the back is on; [exec] runs a shell command and gives its
     * output (errors included). [wanted] is told with every settings payload; [frontCameOn] when the front comes on.
     */
    class Runner(
        private val lit: () -> Boolean,
        private val exec: (String) -> String,
    ) {
        private val lock = Object()
        private var poked = false
        @Volatile private var started = false
        /** Guarded by [lock]. */
        private var wanted: Boolean? = null
        private var due: Why? = Why.START
        /** elapsedRealtime of the last apply or check in this process. */
        private var lastCheckAt: Long? = null

        /** The health check's lines (ShellHealth.report): null until the first apply, then what is wrong (empty: nothing). */
        @Volatile var troubles: List<String>? = null
            private set

        fun start() {
            if (started) return
            started = true
            Thread({
                try { run() } catch (_: InterruptedException) { } catch (e: Throwable) { Log.e(TAG, "search: the look failed", e) }
            }, "rear-search-offline").apply { isDaemon = true; start() }
        }

        /** The switch as the settings payload has it. Any thread; cheap when unchanged. */
        fun wanted(on: Boolean) {
            synchronized(lock) {
                val was = wanted
                if (was == on) return
                wanted = on
                due = dueAfter(was, on, due)
                if (!on) troubles = emptyList()
                poked = true
                lock.notifyAll()
            }
        }

        /** The front came on: a step that waited for a lit screen is made now. Any thread. */
        fun frontCameOn() {
            synchronized(lock) { poked = true; lock.notifyAll() }
        }

        private fun run() {
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                val isLit = runCatching { lit() }.getOrDefault(false)
                val (w, d, since) = synchronized(lock) { Triple(wanted, due, lastCheckAt?.let { now - it }) }
                val ours = thisBoot(readMarker(), bootId())
                when (val step = next(w, isLit, d, ours, since)) {
                    is Step.Apply -> apply(step.why, ours)
                    Step.Check -> {
                        val why = afterCheck(read())
                        if (why != null) apply(why, ours) else synchronized(lock) { lastCheckAt = now }
                    }
                    is Step.Release -> release(step.chainToo)
                    Step.Wait -> Unit
                }
                synchronized(lock) {
                    val left = waitMs(wanted, runCatching { lit() }.getOrDefault(false), lastCheckAt?.let { android.os.SystemClock.elapsedRealtime() - it })
                    // Object.wait counts the phone's awake time: it does not wake a sleeping phone.
                    if (!poked) lock.wait(left)
                    poked = false
                }
            }
        }

        private fun read(): Hold = hold(chainOn(runCatching { exec(READ_CHAIN) }.getOrNull()), denied(runCatching { exec(READ_PACKAGE) }.getOrNull()))

        private fun apply(why: Why, kept: Marker?) {
            val chainWas = chainOn(runCatching { exec(READ_CHAIN) }.getOrNull())
            val outs = listOf(runCatching { exec(BLOCK) }.getOrNull(), runCatching { exec(CHAIN_ON) }.getOrNull())
            val problem = outcome(read(), outs)
            val boot = bootId()
            synchronized(lock) {
                lastCheckAt = android.os.SystemClock.elapsedRealtime()
                // An apply that was refused is owed no more until the next hour's look (never a loop of tries).
                due = null
            }
            if (problem == null) {
                writeMarker(Marker(boot, applied = true, chainByUs = chainByUs(chainWas, kept)))
                troubles = emptyList()
                Log.i(TAG, "search: kept offline (${why.words})")
            } else {
                // The chain may be ours even when the package's rule did not take.
                if (chainWas == false) writeMarker(Marker(boot, applied = true, chainByUs = true))
                troubles = listOf("the phone's search is not kept offline: $problem")
                Log.w(TAG, "search: not kept offline (${why.words}): $problem")
            }
        }

        private fun release(chainToo: Boolean) {
            val outs = listOfNotNull(runCatching { exec(ALLOW) }.getOrNull(), if (chainToo) runCatching { exec(CHAIN_OFF) }.getOrNull() else null)
            val said = outs.firstNotNullOfOrNull { refusal(it) }
            // Tried once: a refusal is said, not tried again in a loop (the switch can be turned on and off again).
            writeMarker(Marker(bootId(), applied = false, chainByUs = false))
            troubles = if (said == null) emptyList() else listOf("the phone's search could not be let online again: the phone refused it ($said)")
            if (said == null) Log.i(TAG, "search: online again (switched off${if (chainToo) ", chain 3 off: it was ours" else ", chain 3 left as it is"})")
            else Log.w(TAG, "search: switched off, but the phone refused to let it online again: $said")
        }

        private fun bootId(): String = Autostart.bootIdOf(runCatching { File(Autostart.BOOT_ID_FILE).readText() }.getOrNull())
        private fun readMarker(): Marker? = runCatching { decode(File(MARKER).takeIf { it.isFile }?.readText()) }.getOrNull()
        private fun writeMarker(m: Marker) {
            runCatching { File(MARKER).writeText(encode(m)) }.onFailure { Log.w(TAG, "search: $MARKER not written: ${it.message}") }
        }
    }
}
