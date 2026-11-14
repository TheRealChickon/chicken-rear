package dev.kb896.aiusage.rear

/**
 * A `logcat` that is followed line by line (the back's cover follows the system's event log, and
 * so does the camera's standby page), started so that it CANNOT OUTLIVE THE PROCESS THAT READS IT.
 *
 * WHY: a bare `logcat` child does not end when its reader dies. Every restart
 * of the shell service (each update of the app kills it outright) left its logcat behind, an
 * orphan under init reading the log for nobody: 25 of them were found on the phone, the oldest
 * five hours old, each costing a little processor and battery for nothing.
 *
 * HOW: the child is a small shell that starts logcat and then only reads its own standard input,
 * which is a pipe from this process. When this process ends, however it ends, that pipe closes,
 * the read returns, and the shell kills its logcat and goes. Nothing is polled. [stop] ends one on
 * purpose the same way. And [sweep], run as a process of ours starts, ends any orphan of an older
 * build that is still about: only a logcat under init whose arguments are exactly a watcher's of
 * ours ([stale]); anything else on the phone that runs logcat is not touched.
 */
object LogWatch {
    /** Follows the log for [filters] (as "tag:I") in [buffer]; its lines are on the returned process's output. */
    fun start(buffer: String, vararg filters: String): Process =
        ProcessBuilder("sh", "-c", command(buffer, filters.toList())).redirectErrorStream(true).start()

    /** The shell line: logcat in the background, its end tied to this shell's, this shell's to its input's. */
    internal fun command(buffer: String, filters: List<String>): String {
        require(buffer.all { it.isLetterOrDigit() } && filters.all { f -> f.all { it.isLetterOrDigit() || it == '_' || it == ':' } }) { "a tag or buffer name only" }
        val logcat = "logcat -b $buffer -T 1 -v brief ${filters.joinToString(" ")} '*:S'"
        return "$logcat & L=\$!; trap 'kill \$L 2>/dev/null' EXIT TERM HUP INT; while read _; do :; done; kill \$L 2>/dev/null"
    }

    /** Ends [p] and its logcat: its input is closed, which is what its shell waits for; then the shell itself. */
    fun stop(p: Process?) {
        p ?: return
        runCatching { p.outputStream.close() }
        runCatching { p.destroy() }
    }

    /** The `ps` line to read for [stale]. */
    const val PS = "ps -A -o PID,PPID,ARGS"

    /**
     * The orphaned watchers in a [PS] listing: under init (parent 1), and with exactly the
     * arguments a build of ours has ever started one with. Their process ids.
     */
    fun stale(ps: String): List<Int> = ps.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 3 || parts[1] != "1") return@mapNotNull null
        if (!OURS.matches(parts[2].trim())) return@mapNotNull null
        parts[0].toIntOrNull()
    }.toList()

    /** Ends the orphans; how many there were. [exec] runs a shell line and gives its output. */
    fun sweep(exec: (String) -> String): Int {
        val pids = runCatching { stale(exec(PS)) }.getOrDefault(emptyList())
        if (pids.isNotEmpty()) runCatching { exec("kill ${pids.joinToString(" ")}") }
        return pids.size
    }

    // Every watcher any build started: the event log's or all logs', new lines only, brief, a first
    // tag of these four, more tags of the same kind, everything else silenced.
    private val OURS = Regex("""logcat -b (all|events) -T 1 -v brief (ActivityTaskManager|input_focus|wm_set_resumed_activity|MiuiSubscreenDoubleTapGesture):I( [A-Za-z_]+:I)* \*:S""")
}
