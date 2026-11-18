package dev.kb896.aiusage.service

object Health {

    /** What a problem's button does, where there is one. */
    enum class Action { NONE, OPEN_SHIZUKU, LISTENER_ACCESS, BATTERY, NOTIFICATIONS }

    enum class Key { SHIZUKU_DOWN, SHIZUKU_NOT_ALLOWED, SHELL_DOWN, COVER, LISTENER, BATTERY, XIAOMI_FILES, PIN_OURS, DIALER, CLOCK, AUTOSTART, SEARCH_OFFLINE, CANNOT_WARN }

    /** [what] is wrong, [todo] is what he does about it; [warn]: worth the notification (a fall-back that works by itself is not). */
    data class Problem(val key: Key, val what: String, val todo: String, val action: Action = Action.NONE, val warn: Boolean = true)

    /**
     * The shell's half, as IRearService.health gives it; null fields are not known. [looked]: whether that shell has
     * read the packages yet (rear/ShellHealth: not in its first half minute, unless asked to look again).
     */
    data class Shell(
        val coverMade: Boolean = false,
        val coverError: String? = null,
        val dialerMissing: Set<String>? = null,
        val clockMissing: Set<String>? = null,
        val xiaomiMissing: List<String>? = null,
        val looked: Boolean = true,
        /**
         * What is wrong with the scripts the shell starts after a restart (rear/Autostart: one listed is missing, not
         * allowed to run, or keeps stopping); null while that shell has not looked yet, empty for nothing.
         */
        val autostart: List<String>? = null,
        /**
         * What keeping the phone's search offline met (rear/SearchOffline: the phone refused the firewall commands);
         * null while that shell has not acted yet, empty for nothing.
         */
        val searchOffline: List<String>? = null,
    ) {
        /**
         * What is known now, from this answer over what was known before ([old]): the cover is this process's; what a
         * shell read of the packages is kept until a shell has read them again (a new shell answers before its own
         * look, and that answer must not wipe what the last one found).
         */
        fun over(old: Shell?): Shell = (if (looked || old == null) this
            else copy(dialerMissing = old.dialerMissing, clockMissing = old.clockMissing, xiaomiMissing = old.xiaomiMissing, looked = old.looked))
            // The same for the scripts: a new shell answers before its own first look at them.
            .let { if (it.autostart == null && old != null) it.copy(autostart = old.autostart) else it }
            .let { if (it.searchOffline == null && old != null) it.copy(searchOffline = old.searchOffline) else it }
    }

    data class Facts(
        val shizukuUp: Boolean,
        val shizukuAllowed: Boolean,
        val shellBound: Boolean,
        val guardBound: Boolean,
        /** Since when (ms) Shizuku has been awaited, or since it arrived: grace for the start of a restart. */
        val waitedMs: Long,
        val shell: Shell?,
        val listenerAllowed: Boolean,
        val listenerConnected: Boolean,
        /** Battery saver "No restrictions" as far as the app can read it; null where it cannot. */
        val batteryUnrestricted: Boolean?,
        val notificationsAllowed: Boolean,
        /** The pin setting (Xiaomi's own), whether Xiaomi's pin activity resolves, and the rear app's versionCode. */
        val pinByXiaomi: Boolean,
        val pinActivity: Boolean,
        val xiaomiRearVersion: Long?,
    )

    /** How long a restart's own waiting is not a problem: Shizuku and the shell come some seconds after the unlock. */
    const val GRACE_MS = 90_000L

    // -- WHEN A LOOK IS MADE. Each thing that asks for one gives a look soon (what set it off has settled), and some a
    // look later: past the grace that thing has just begun (a problem inside the grace is no problem, so without the
    // later look one that starts then is never seen), or after a new shell's own first look. Nothing polls. --

    /** What asks for a look. */
    enum class Cue { START, SHIZUKU_CAME, SHIZUKU_WENT, SHELL_GONE, BOUND, BIND_RETRY, PACKAGE }

    /** The waiting looks: [SOON] the coalesced one, [GRACE] past a grace, [SHELL] past a new shell's first look. */
    enum class Slot { SOON, GRACE, SHELL }

    /** A look in [slot] after [delayMs]; [replace]: a later ask moves a waiting one (else the waiting one stands). */
    data class Wait(val slot: Slot, val delayMs: Long, val replace: Boolean)

    const val SOON_MS = 1_500L
    /** A look this long after a change that starts a grace (ShizukuArrival's changedAt): just past it. */
    const val GRACE_LOOK_MS = GRACE_MS + 5_000L
    /** A look this long after a shell is bound: past its own first look of the packages (RearUserService's 30 s). */
    const val SHELL_LOOK_MS = 45_000L

    /** Whether [cue] starts a restart's grace again (ShizukuArrival.sinceChangeMs counts from it). */
    fun startsGrace(cue: Cue) = cue == Cue.START || cue == Cue.SHIZUKU_CAME || cue == Cue.SHIZUKU_WENT

    fun waitsFor(cue: Cue): List<Wait> {
        val soon = Wait(Slot.SOON, SOON_MS, replace = false)
        return when (cue) {
            // The grace starts again with Shizuku coming or going: a look waiting for an older grace would fall inside
            // the new one and see nothing, so it is moved.
            Cue.SHIZUKU_CAME, Cue.SHIZUKU_WENT -> listOf(soon, Wait(Slot.GRACE, GRACE_LOOK_MS, replace = true))
            Cue.START -> listOf(soon, Wait(Slot.GRACE, GRACE_LOOK_MS, replace = false))
            // Said every few seconds while the shell stays away (ShizukuBridge.ensureAlive): the first one's look stands.
            Cue.SHELL_GONE -> listOf(Wait(Slot.GRACE, GRACE_LOOK_MS, replace = false))
            Cue.BOUND -> listOf(soon, Wait(Slot.SHELL, SHELL_LOOK_MS, replace = true))
            Cue.BIND_RETRY, Cue.PACKAGE -> listOf(soon)
        }
    }

    /** One slot's waiting look, by its due time on a clock of the caller's. Not thread-safe: one thread (the main one). */
    class Due {
        var at: Long? = null
            private set

        /** Whether [w], asked at [now], is to be posted (then at now + delay, any waiting one in the slot taken off). */
        fun arm(now: Long, w: Wait): Boolean {
            val waiting = at?.takeIf { it > now }
            if (waiting != null && !w.replace) return false
            at = now + w.delayMs
            return true
        }

        fun fired() { at = null }
    }

    fun problems(f: Facts): List<Problem> {
        val out = ArrayList<Problem>()
        val late = f.waitedMs >= GRACE_MS
        when {
            !f.shizukuUp -> if (late) out += Problem(Key.SHIZUKU_DOWN, "The back shows Xiaomi's screen: Shizuku is not running.", "Start it in Shizuku.", Action.OPEN_SHIZUKU)
            !f.shizukuAllowed -> out += Problem(Key.SHIZUKU_NOT_ALLOWED, "Shizuku runs, but Chicken Rear is not allowed in it.", "Allow it in Shizuku.", Action.OPEN_SHIZUKU)
            !(f.shellBound && f.guardBound) -> if (late) out += Problem(Key.SHELL_DOWN, "Chicken Rear's part on the back is not running.", "Open Chicken Rear once. If this stays, stop and start Shizuku.")
        }
        val s = f.shell
        if (s != null && !s.coverMade && s.coverError != null) out += Problem(Key.COVER, "The back cannot be covered: Xiaomi's screen shows.", "Share the log below.")
        if (!f.listenerAllowed) out += Problem(Key.LISTENER, "Notifications, calls and timers don't reach the back.", "Allow notification access.", Action.LISTENER_ACCESS)
        else if (!f.listenerConnected && late) out += Problem(Key.LISTENER, "Notifications, calls and timers don't reach the back right now.", "Open Chicken Rear once.")
        if (f.batteryUnrestricted == false) out += Problem(Key.BATTERY, "HyperOS may stop Chicken Rear in the background.", "Set its battery use to No restrictions.", Action.BATTERY)
        if (!s?.xiaomiMissing.isNullOrEmpty()) out += Problem(Key.XIAOMI_FILES, "Xiaomi's rear app changed: the call and timer pages look plainer.", "Nothing to do.", warn = false)
        if (f.pinByXiaomi && !(f.pinActivity && dev.kb896.aiusage.rear.PinVia.timedFor(f.xiaomiRearVersion)))
            out += Problem(Key.PIN_OURS, "Xiaomi's rear app was updated: a pin plays our own animation.", "Nothing to do.", warn = false)
        if (!s?.dialerMissing.isNullOrEmpty()) out += Problem(Key.DIALER, "The Phone app changed: the back's call buttons take the plain way.", "Nothing to do.", warn = false)
        if (!s?.clockMissing.isNullOrEmpty()) out += Problem(Key.CLOCK, "The Clock changed: the timer page uses the Clock's notification.", "Nothing to do.", warn = false)
        s?.autostart?.takeIf { it.isNotEmpty() }?.let { lines ->
            out += Problem(Key.AUTOSTART,
                if (lines.size == 1) "A script that starts with the back is not running: ${lines[0]}." else "${lines.size} scripts that start with the back are not running: ${lines.joinToString("; ")}.",
                "Put it right from the PC (the list is /data/local/tmp/chicken-rear-autostart).", warn = false)
        }
        s?.searchOffline?.firstOrNull()?.let { line ->
            out += Problem(Key.SEARCH_OFFLINE, "${line.replaceFirstChar { it.uppercase() }}.",
                "Share the log below, or switch \"Keep the system search offline\" off.", warn = false)
        }
        if (!f.notificationsAllowed) out += Problem(Key.CANNOT_WARN, "Chicken Rear can't warn you: its notifications are off.", "Allow them.", Action.NOTIFICATIONS, warn = false)
        return out
    }

    /** The one notification: its title and text, and what it stands for (posted again only when that changes). */
    data class Notice(val title: String, val text: String, val signature: String)

    /** The notification for [problems], or null for none (all well, nothing worth it, or he has not allowed notifications). */
    fun notice(problems: List<Problem>, allowed: Boolean): Notice? {
        if (!allowed) return null
        val warn = problems.filter { it.warn }
        if (warn.isEmpty()) return null
        val first = warn.first()
        val title = if (warn.size == 1) first.what else "${warn.size} things need a look"
        val text = if (warn.size == 1) first.todo else first.what
        return Notice(title, text, warn.joinToString(",") { it.key.name })
    }

    /** What the phone and the packages the back leans on are: build fingerprint, and versionCode by package (null: not installed). */
    data class Stamp(val fingerprint: String, val versions: Map<String, Long?>)

    /** The packages watched: Xiaomi's rear app, the dialer, the Clock, the Camera, the launcher, Shizuku. */
    val WATCHED = listOf("com.xiaomi.subscreencenter", "com.android.incallui", "com.android.deskclock", "com.android.camera", "com.miui.home", "moe.shizuku.privileged.api")

    /** In words, what has changed from [old] to [new]; empty for nothing (or no [old]: the first look). */
    fun changes(old: Stamp?, new: Stamp): List<String> {
        if (old == null) return emptyList()
        val out = ArrayList<String>()
        if (old.fingerprint != new.fingerprint) out += "the system (${old.fingerprint} to ${new.fingerprint})"
        for (pkg in (old.versions.keys + new.versions.keys).distinct()) {
            val a = old.versions[pkg]; val b = new.versions[pkg]
            if (a != b) out += "$pkg ${a ?: "absent"} to ${b ?: "absent"}"
        }
        return out
    }

    /** The stamp as stored: "fingerprint" line, then "pkg=version" lines ("pkg=" for absent). */
    fun encode(s: Stamp): String = (listOf(s.fingerprint) + s.versions.entries.sortedBy { it.key }.map { "${it.key}=${it.value ?: ""}" }).joinToString("\n")

    fun decode(text: String?): Stamp? {
        val lines = text?.lines()?.takeIf { it.isNotEmpty() && it[0].isNotEmpty() } ?: return null
        val versions = lines.drop(1).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toLongOrNull() }
        return Stamp(lines[0], versions)
    }
}
