package dev.kb896.aiusage.rear

import dev.kb896.aiusage.Panel

/**
 * The rear display's layer stack, from `dumpsys display` ([READ]); 1 on the 17 Pro, as measured.
 *
 * READ ONCE PER PROCESS: it was read afresh for every layer made, and every notification's pop-up and every row thrown
 * aside makes one: the display manager's whole dump (its brightness history, every display's config and nits table)
 * each time. A display's layer stack is given as the display is added and does not change while it is there. Only a
 * stack that was found is kept, and only once TWO READS IN A ROW have found the same (a dump taken while the displays
 * change could give one display's block without its stack, and the next display's would be taken): until then each
 * layer reads it as before. A dump that gives none still says [Panel.DISPLAY_ID] and is read again next time, as
 * before. A layer that could not be made on it ([forget], from ShellLayer) has it read again.
 */
object RearStack {
    val READ = "dumpsys display | grep -iE 'mDisplayId=${Panel.DISPLAY_ID}|layerStack' | head -40"

    @Volatile private var kept: Int? = null
    /** The stack the last read found, not kept yet: kept when the next read finds the same. */
    private var once: Int? = null

    /** The layer stack in [READ]'s output; null for none found. */
    fun parse(dump: String): Int? =
        Regex("""mDisplayId=${Panel.DISPLAY_ID}\b[\s\S]*?[lL]ayerStack[ =](\d+)""").find(dump)?.groupValues?.get(1)?.toIntOrNull()

    /** The rear's layer stack: kept, else read with [exec] (a shell line, its output). */
    fun of(exec: (String) -> String): Int {
        kept?.let { return it }
        val found = parse(exec(READ)) ?: return Panel.DISPLAY_ID
        synchronized(this) { if (once == found) kept = found else once = found }
        return found
    }

    /** Read again next time, and kept only after two reads in a row again. */
    @Synchronized fun forget() { kept = null; once = null }
}
