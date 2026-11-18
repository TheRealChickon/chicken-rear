package dev.kb896.aiusage.rear

import android.util.Log
import dev.kb896.aiusage.service.NightRule
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar

object Autostart {
    const val TAG = RearCover.RESTART_TAG
    const val DIR = "/data/local/tmp/"
    const val LIST_FILE = DIR + "chicken-rear-autostart"
    const val STATE_FILE = DIR + "chicken-rear-autostart.state"
    const val LOCK_FILE = DIR + "chicken-rear-autostart.lock"
    /** The boot id when /proc does not give one: a fixed word, so the state file still reads back (and keeps its starts). */
    const val UNKNOWN_BOOT = "unknown"
    const val BOOT_ID_FILE = "/proc/sys/kernel/random/boot_id"
    const val SHELL_UID = 2000

    /** How often a running shell looks again whether the listed scripts run: never more often. */
    const val LOOK_EVERY_MS = 10 * 60_000L
    /** A script started this many times within [HOUR_MS] is not started again until the oldest start is an hour old. */
    const val MOST_STARTS_AN_HOUR = 3
    const val HOUR_MS = 60 * 60_000L
    /** As a shell starts: how long its first look waits for the back to be covered at most (then it looks anyway). */
    const val COVER_WAIT_MS = 20_000L
    /** After a start: how long before /proc is read for the new process. */
    const val SETTLE_MS = 500L
    /** A script started this recently and not (yet) seen in /proc is not started again: it may be on its way. */
    const val START_GRACE_MS = 30_000L
    /** How long a look waits for another shell process to let go of [LOCK_FILE] at most. */
    const val LOCK_WAIT_MS = 15_000L

    private val NAME = Regex("[A-Za-z0-9._-]+")
    private val OWN_FILES = setOf(LIST_FILE, STATE_FILE, "$STATE_FILE.tmp", LOCK_FILE)

    // ---- the list -----------------------------------------------------------------------------------------------

    /** One listed script: its [path], and [pattern], the command line to look for instead of its own (null: its own). */
    data class Entry(val path: String, val pattern: String? = null) {
        val name: String get() = path.substringAfterLast('/')
    }

    /** A line of the list: an [entry], or why it was refused ([problem]). [line] counts from 1. */
    data class Parsed(val line: Int, val entry: Entry?, val problem: String?)

    /**
     * The list's lines: blank lines and lines starting with '#' are skipped; fields are apart by spaces or tabs; one
     * field (the path) or two (the path and the pattern); a path listed twice counts once (its first line). Pure.
     */
    fun parse(text: String?): List<Parsed> {
        if (text == null) return emptyList()
        val out = ArrayList<Parsed>()
        val seen = HashSet<String>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split(Regex("[ \t]+"))
            val n = i + 1
            if (fields.size > 2) { out += Parsed(n, null, "line $n has ${fields.size} fields, at most two (a script, a pattern)"); return@forEachIndexed }
            val path = fields[0]
            val pattern = fields.getOrNull(1)
            val problem = pathProblem(path) ?: pattern?.let { patternProblem(it) }
            if (problem != null) { out += Parsed(n, null, "line $n: $problem"); return@forEachIndexed }
            if (!seen.add(path)) return@forEachIndexed
            out += Parsed(n, Entry(path, pattern), null)
        }
        return out
    }

    /**
     * Why [path] may not be run, as a path, or null: it must be a file right in /data/local/tmp (no folder below it, no
     * "..", no link to elsewhere by its name), of letters, digits, '.', '_' and '-' only, and not the list or the state
     * file. Pure; the file itself is judged by [fileProblem].
     */
    fun pathProblem(path: String): String? {
        if (!path.startsWith(DIR)) return "$path is not in $DIR"
        val name = path.removePrefix(DIR)
        if (name.isEmpty() || '/' in name) return "$path is not a file right in $DIR"
        if (!NAME.matches(name) || name == "." || name == "..") return "$path has a name of other characters than letters, digits, . _ -"
        if (path in OWN_FILES) return "$path is the autostart's own file"
        return null
    }

    /** Why [pattern] may not be looked for, or null: at least three characters that are not '*'. Pure. */
    fun patternProblem(pattern: String): String? =
        if (pattern.count { it != '*' } < 3) "the pattern \"$pattern\" would match too much (at least three characters other than *)" else null

    data class FileInfo(val exists: Boolean, val regular: Boolean = false, val link: Boolean = false, val uid: Int = -1, val mode: Int = 0)

    /** Why the file [info] describes may not be run (or read as the list), or null. Pure. */
    fun fileProblem(info: FileInfo): String? = when {
        !info.exists -> "missing"
        info.link -> "a link, not a file"
        !info.regular -> "not a regular file"
        info.uid != SHELL_UID -> "owned by uid ${info.uid}, not the shell"
        (info.mode and 0b000_000_010) != 0 -> "writable by anyone"
        else -> null
    }

    // ---- is it running --------------------------------------------------------------------------------------------

    /**
     * A process as /proc has it: [pid], its process group [pgid] (null: not read), its arguments [argv], its folder [cwd]
     * (null: not read, or not needed).
     */
    data class Proc(val pid: Int, val pgid: Int?, val argv: List<String>, val cwd: String? = null)

    /** /proc/<pid>/cmdline's bytes as arguments (apart by NUL, the last NUL not an empty argument). Pure. */
    fun argv(cmdline: ByteArray): List<String> {
        if (cmdline.isEmpty()) return emptyList()
        val parts = String(cmdline, Charsets.UTF_8).split('\u0000')
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    /** The process group from /proc/<pid>/stat's text (the fifth field; the name in brackets may hold anything). Pure. */
    fun pgidOf(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        return stat.substring(close + 1).trim().split(' ').getOrNull(2)?.toIntOrNull()
    }

    /** The shells a script runs in, by the last part of argv[0]. */
    private val SHELLS = setOf("sh", "mksh")
    /** What may stand before the shell and hands over to it (each execs it, so this is seen only for a moment). */
    private val WRAPPERS = setOf("nohup", "setsid")

    /**
     * The script [argv] runs, as it was written (relative or not), or null when it is no shell running a script file:
     * argv[0] a shell (or `nohup`/`setsid` and their options before one), then the shell's options, then the script.
     * `-c` (a command line, not a file) and `-s` (from its input) are no script; `-o`/`+o` take the next argument. Pure.
     */
    fun scriptArg(argv: List<String>): String? {
        var i = 0
        while (i < argv.size && argv[i].substringAfterLast('/') in WRAPPERS) {
            i++
            while (i < argv.size && argv[i].startsWith("-")) i++
        }
        if (i >= argv.size || argv[i].substringAfterLast('/') !in SHELLS) return null
        i++
        while (i < argv.size) {
            val a = argv[i]
            if (a == "--") { i++; break }
            if (a.length < 2 || (a[0] != '-' && a[0] != '+')) break
            if (a == "-o" || a == "+o") { i += 2; continue }
            if (a.startsWith("--")) { i++; continue }
            if ('c' in a || 's' in a) return null
            i++
        }
        return argv.getOrNull(i)?.takeIf { it.isNotEmpty() }
    }

    /** [path] made absolute against [cwd] (null: [DIR]), "." and ".." and doubled slashes resolved. Pure. */
    fun resolve(path: String, cwd: String?): String {
        val whole = if (path.startsWith("/")) path else (cwd ?: DIR).trimEnd('/') + "/" + path
        val parts = ArrayList<String>()
        for (part in whole.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += part
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * Whether [argv] (run in [cwd]) is [e]'s script: a shell running the file [e] names, however the path was written,
     * arguments after it or not; or, with a pattern, the pattern over the whole line. A relative script whose folder
     * could not be read is taken as in [DIR]: a copy believed running is better than a second copy. Pure.
     */
    fun isScript(argv: List<String>, e: Entry, cwd: String? = null): Boolean {
        if (e.pattern != null) return glob(e.pattern, argv.joinToString(" "))
        val a = scriptArg(argv) ?: return false
        return resolve(a, cwd) == e.path
    }

    /** [pattern] over the whole of [text], '*' for any run of characters (none included). Pure. */
    fun glob(pattern: String, text: String): Boolean =
        Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL).matches(text)

    /**
     * The running [e] among [procs], never [selfPid]: the leader of its process group where one matches (the script's
     * own forks share its command line), else the lowest pid; null for none. Pure.
     */
    fun running(procs: List<Proc>, e: Entry, selfPid: Int): Int? {
        val hits = procs.filter { it.pid != selfPid && isScript(it.argv, e, it.cwd) }
        return (hits.firstOrNull { it.pgid == it.pid } ?: hits.minByOrNull { it.pid })?.pid
    }

    // ---- the boot and the starts ----------------------------------------------------------------------------------

    /** What is kept between processes: the [boot] it belongs to, and each script's starts (elapsed realtime) in it. */
    data class State(val boot: String, val starts: Map<String, List<Long>> = emptyMap()) {
        /** [path]'s last start at or before [now], or null. */
        fun last(path: String, now: Long): Long? = starts[path].orEmpty().filter { it <= now }.maxOrNull()

        /** [path]'s starts within the hour before [now]. */
        fun recent(path: String, now: Long): List<Long> = starts[path].orEmpty().filter { now - it in 0 until HOUR_MS }

        /** [path] started at [now]; starts older than an hour forgotten. */
        fun started(path: String, now: Long): State = copy(starts = starts.mapValues { (_, v) -> v.filter { now - it in 0 until HOUR_MS } }
            .filterValues { it.isNotEmpty() } + (path to recent(path, now) + now))
    }

    /**
     * THE BOOT GATE: the kept state if it is of this boot ([bootId]), else a fresh one (a restart of the phone forgets
     * the last boot's starts: elapsed realtime starts from 0 again, and a script that was dying then is not now). Pure.
     */
    fun gate(kept: State?, bootId: String): State = if (kept != null && kept.boot == bootId) kept else State(bootId)

    /** The boot id from [BOOT_ID_FILE]'s text: one word, else [UNKNOWN_BOOT] (never empty: the state must read back). Pure. */
    fun bootIdOf(text: String?): String = text?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: UNKNOWN_BOOT

    /**
     * What this look counts from: the kept file's state of this boot ([gate]) and this process's own ([mine]) together,
     * each start once. So a file that could not be read, or was not written, does not forget the starts this process
     * made; and another process's starts in the file count too. Pure.
     */
    fun merge(kept: State?, mine: State?, bootId: String): State {
        val base = gate(kept, bootId)
        if (mine == null || mine.boot != bootId) return base
        val paths = base.starts.keys + mine.starts.keys
        return base.copy(starts = paths.associateWith { (base.starts[it].orEmpty() + mine.starts[it].orEmpty()).distinct().sorted() })
    }

    /** Whether [kept] is of another boot than [bootId] (or there was none): this look is the first of this boot. Pure. */
    fun newBoot(kept: State?, bootId: String): Boolean = kept?.boot != bootId

    fun encode(s: State): String = buildString {
        append("boot ").append(s.boot).append('\n')
        for ((path, times) in s.starts.entries.sortedBy { it.key }) for (t in times) append("start ").append(t).append(' ').append(path).append('\n')
    }

    fun decode(text: String?): State? {
        val lines = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        val boot = lines.firstOrNull()?.takeIf { it.startsWith("boot ") }?.removePrefix("boot ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val starts = LinkedHashMap<String, MutableList<Long>>()
        for (l in lines.drop(1)) {
            val f = l.split(' ')
            if (f.size != 3 || f[0] != "start") continue
            val t = f[1].toLongOrNull() ?: continue
            starts.getOrPut(f[2]) { ArrayList() } += t
        }
        return State(boot, starts)
    }

    // ---- the decision ---------------------------------------------------------------------------------------------

    sealed class Decision {
        /** Not run: [reason]. */
        data class Refused(val reason: String) : Decision()
        data class Running(val pid: Int) : Decision()
        /** Not seen running, but started [agoMs] ago (within [START_GRACE_MS]): not started again yet. */
        data class JustStarted(val agoMs: Long) : Decision()
        /** Not running, and the night with the front off: started when the front comes on. */
        data object Night : Decision()
        /** Not running, and started [times] within the hour already: it keeps dying. */
        data class TooOften(val times: Int) : Decision()
        data object Start : Decision()
    }

    /**
     * What to do with one listed script. [problem]: why its file may not be run (null: it may); [pid]: it runs as that;
     * [startsInHour]: how often it was started within the hour; [night] and [frontOn] for the night rule;
     * [sinceStartMs]: how long ago it was last started (null: not in this boot). Pure.
     */
    fun decide(problem: String?, pid: Int?, startsInHour: Int, night: Boolean, frontOn: Boolean, sinceStartMs: Long? = null): Decision = when {
        problem != null -> Decision.Refused(problem)
        pid != null -> Decision.Running(pid)
        sinceStartMs != null && sinceStartMs in 0 until START_GRACE_MS -> Decision.JustStarted(sinceStartMs)
        // The project's night rule: nothing of ours starts on a phone lying asleep at night (NightRule.declinesWake).
        NightRule.declinesWake(night, frontOn) -> Decision.Night
        startsInHour >= MOST_STARTS_AN_HOUR -> Decision.TooOften(startsInHour)
        else -> Decision.Start
    }

    /** Night as the night rule has it (its hours or Do Not Disturb); the app's "Sleep at night" switch is not known here, so always on. Pure. */
    fun night(hourOfDay: Int, doNotDisturb: Boolean): Boolean = NightRule.night(true, hourOfDay, doNotDisturb)

    /** The health check's line for [e] and [d], or null for none: a script missing, refused, or dying again and again. Pure. */
    fun trouble(e: Entry?, d: Decision): String? = when (d) {
        is Decision.Refused -> if (e != null) "${e.name}: ${d.reason}" else d.reason
        is Decision.TooOften -> "${e?.name}: keeps stopping (started ${d.times} times in the last hour)"
        else -> null
    }

    /** Whether a look is due: none yet, a look held back by the night and the front now on, or [LOOK_EVERY_MS] past. Pure. */
    fun lookDue(lastLookAt: Long?, now: Long, heldByNight: Boolean, frontOn: Boolean): Boolean =
        lastLookAt == null || (heldByNight && frontOn) || now - lastLookAt >= LOOK_EVERY_MS

    // ---- on the phone ---------------------------------------------------------------------------------------------

    /**
     * The shell's side: one daemon thread that looks as the shell starts (once the back is covered, [COVER_WAIT_MS] at
     * most), then every [LOOK_EVERY_MS], and at once when the front comes on after the night held a start back
     * ([frontCameOn]). [frontOn]: the front display is on; [coverUp]: the back is covered; [doNotDisturb]: read only
     * when the hour is not already the night's.
     */
    class Runner(
        private val frontOn: () -> Boolean,
        private val coverUp: () -> Boolean,
        private val doNotDisturb: () -> Boolean,
    ) {
        private val lock = Object()
        /** The front came on while a start was held back: look at once (guarded by [lock]). */
        private var poked = false
        @Volatile private var heldByNight = false
        @Volatile private var started = false
        /** Said in a line only when it changes: each script's last decision. */
        private val said = HashMap<String, Decision>()
        private var saidNoList = false
        private var saidNoLock = false
        /** This process's own count of the starts of this boot, beside the state file's ([merge]). */
        private var mine: State? = null

        /** The health check's lines (ShellHealth.report): null until the first look, then what is wrong (empty: nothing). */
        @Volatile var troubles: List<String>? = null
            private set

        fun start() {
            if (started) return
            started = true
            Thread({
                try { run() } catch (_: InterruptedException) { } catch (e: Throwable) { Log.e(TAG, "autostart: the look failed", e) }
            }, "rear-autostart").apply { isDaemon = true; start() }
        }

        /** The front came on (the shell's front watch): a start the night held back is made now. Any thread. */
        fun frontCameOn() {
            if (!heldByNight) return
            synchronized(lock) { poked = true; lock.notifyAll() }
        }

        private fun run() {
            val began = android.os.SystemClock.uptimeMillis()
            while (!coverUp() && android.os.SystemClock.uptimeMillis() - began < COVER_WAIT_MS) Thread.sleep(250L)
            var last: Long? = null
            while (true) {
                val now = android.os.SystemClock.uptimeMillis()
                val front = runCatching { frontOn() }.getOrDefault(true)
                if (lookDue(last, now, heldByNight, front)) {
                    runCatching { locked { look(first = last == null) } }.onFailure { Log.w(TAG, "autostart: the look failed: ${it.message}") }
                    last = now
                }
                // Woken early only by frontCameOn; a wait counts the phone's awake time (it does not wake a sleeping phone).
                val left = (LOOK_EVERY_MS - (android.os.SystemClock.uptimeMillis() - (last ?: now))).coerceIn(1_000L, LOOK_EVERY_MS)
                synchronized(lock) {
                    if (!poked) lock.wait(left)
                    poked = false
                }
            }
        }

        /**
         * [f] holding the lock on [LOCK_FILE], so two shell processes never look and start at the same time (the kernel
         * lets it go if a process dies holding it). Waited for [LOCK_WAIT_MS] at most (a look takes about a second), so
         * an old process stuck with it does not hold this one's look back for ever. Without the lock (the file not to be
         * made, or not let go in time) the look is made all the same, said once: the grace after a start and /proc still
         * keep a second copy out in nearly every case.
         */
        private fun <T> locked(f: () -> T): T {
            val file = runCatching { RandomAccessFile(LOCK_FILE, "rw") }.getOrNull()
            val held = file?.let {
                val until = android.os.SystemClock.uptimeMillis() + LOCK_WAIT_MS
                var l = runCatching { it.channel.tryLock() }.getOrNull()
                while (l == null && android.os.SystemClock.uptimeMillis() < until) {
                    Thread.sleep(250L)
                    l = runCatching { it.channel.tryLock() }.getOrNull()
                }
                l
            }
            if (held == null && !saidNoLock) Log.w(TAG, "autostart: no lock on $LOCK_FILE, looking without it")
            saidNoLock = held == null
            try { return f() } finally {
                runCatching { held?.release() }
                runCatching { file?.close() }
            }
        }

        private fun look(first: Boolean) {
            val bootId = bootIdOf(runCatching { File(BOOT_ID_FILE).readText() }.getOrNull())
            val kept = runCatching { decode(File(STATE_FILE).takeIf { it.isFile }?.readText()) }.getOrNull()
            if (first) Log.i(TAG, "autostart: looking as the shell starts (${when {
                bootId == UNKNOWN_BOOT -> "the phone's boot id is not readable"
                newBoot(kept, bootId) -> "the first look since the phone started"
                else -> "the phone has not restarted since the last look"
            }})")
            var state = merge(kept, mine, bootId)
            val listInfo = stat(LIST_FILE)
            if (!listInfo.exists || runCatching { File(LIST_FILE).length() }.getOrDefault(0L) == 0L) {
                if (!saidNoList) Log.i(TAG, "autostart: no list in $LIST_FILE, nothing to start")
                saidNoList = true
                troubles = emptyList(); heldByNight = false
                mine = state; save(kept, state)
                return
            }
            saidNoList = false
            fileProblem(listInfo)?.let {
                sayOnce(LIST_FILE, Decision.Refused("the list $LIST_FILE is $it"), null)
                troubles = listOf("the list: $it"); heldByNight = false
                return
            }
            val parsed = parse(runCatching { File(LIST_FILE).readText() }.getOrNull())
            val problems = ArrayList<String>()
            val table by lazy { procs() }
            val self = android.os.Process.myPid()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val front = runCatching { frontOn() }.getOrDefault(true)
            // Do Not Disturb asked only when the hour does not say night already (it is a `settings` read).
            val isNight = by { night(hour, false) } || (!front && by { night(hour, doNotDisturb()) })
            var held = false
            for (p in parsed) {
                val e = p.entry
                if (e == null) {
                    val d = Decision.Refused(p.problem ?: "?")
                    sayOnce("line ${p.line}", d, null); trouble(null, d)?.let { problems += it }
                    continue
                }
                val now = android.os.SystemClock.elapsedRealtime()
                val d = decide(fileProblem(stat(e.path)), running(table, e, self), state.recent(e.path, now).size, isNight, front,
                    state.last(e.path, now)?.let { now - it })
                when (d) {
                    Decision.Start -> {
                        state = state.started(e.path, now)
                        mine = state; save(kept, state)
                        said[e.path] = launch(e, self)?.let { Decision.Running(it) } ?: Decision.Start
                    }
                    Decision.Night -> { held = true; sayOnce(e.path, d, e) }
                    else -> sayOnce(e.path, d, e)
                }
                trouble(e, d)?.let { problems += it }
            }
            heldByNight = held
            troubles = problems
            mine = state; save(kept, state)
        }

        private inline fun by(f: () -> Boolean) = runCatching(f).getOrDefault(false)

        private fun sayOnce(key: String, d: Decision, e: Entry?) {
            val before = said[key]
            said[key] = d
            if (before == d) return
            val name = e?.name ?: key
            Log.i(TAG, when (d) {
                is Decision.Running -> "autostart: $name already running (pid ${d.pid})"
                is Decision.JustStarted -> "autostart: $name started ${d.agoMs / 1000} s ago and not seen yet, not started again"
                is Decision.Refused -> "autostart: refused: ${if (e != null) "$name, " else ""}${d.reason}"
                Decision.Night -> "autostart: $name not running; not started at night with the front off — when the front comes on"
                is Decision.TooOften -> "autostart: refused: $name keeps stopping (started ${d.times} times in the last hour), not started again within the hour"
                Decision.Start -> "autostart: $name to be started"
            })
        }

        /** `setsid sh <path>`, detached, its input and output /dev/null; then /proc says whether it runs. */
        private fun launch(e: Entry, self: Int): Int? {
            val p = runCatching {
                val setsid = listOf("/system/bin/setsid", "/system/xbin/setsid").firstOrNull { File(it).canExecute() } ?: "setsid"
                ProcessBuilder(setsid, "sh", e.path)
                    .directory(File(DIR))
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .apply { environment().putIfAbsent("PATH", PATH) }
                    .start()
            }.onFailure { Log.w(TAG, "autostart: ${e.name} could not be started: ${it.message}") }.getOrNull() ?: return null
            // setsid either becomes the script (the same pid) or hands it to a child and ends at once: /proc says which.
            // The Process is not waited for here: the runtime's reaper collects it whenever it ends, and nothing of ours
            // ever ends it.
            Thread.sleep(SETTLE_MS)
            val pid = running(procs(), e, self)
            Log.i(TAG, if (pid != null) "autostart: ${e.name} started (pid $pid)" else "autostart: ${e.name} started, but it is not running ${SETTLE_MS} ms later (${if (p.isAlive) "setsid still there" else "ended, exit ${p.exitValue()}"})")
            return pid
        }

        private fun save(kept: State?, state: State) {
            if (kept == state) return
            runCatching {
                val tmp = File("$STATE_FILE.tmp")
                tmp.writeText(encode(state))
                if (!tmp.renameTo(File(STATE_FILE))) tmp.delete()
            }.onFailure { Log.w(TAG, "autostart: the state not kept: ${it.message}") }
        }
    }

    /** lstat of [path] (a link is not followed). */
    fun stat(path: String): FileInfo = runCatching {
        val s = android.system.Os.lstat(path)
        FileInfo(
            exists = true,
            regular = android.system.OsConstants.S_ISREG(s.st_mode),
            link = android.system.OsConstants.S_ISLNK(s.st_mode),
            uid = s.st_uid,
            mode = s.st_mode and 0b111_111_111,
        )
    }.getOrDefault(FileInfo(exists = false))

    /** Every process /proc lets us read: its pid, process group and arguments. */
    fun procs(): List<Proc> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val args = runCatching { argv(File(dir, "cmdline").readBytes()) }.getOrNull() ?: return@mapNotNull null
        if (args.isEmpty()) return@mapNotNull null
        // The folder only where it matters: a shell running a script by a relative path.
        val cwd = scriptArg(args)?.takeIf { !it.startsWith("/") }?.let { runCatching { android.system.Os.readlink("/proc/$pid/cwd") }.getOrNull() }
        Proc(pid, runCatching { pgidOf(File(dir, "stat").readText()) }.getOrNull(), args, cwd)
    }

    /** The shell's own PATH, for a process of ours that has none. */
    private const val PATH = "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
}
