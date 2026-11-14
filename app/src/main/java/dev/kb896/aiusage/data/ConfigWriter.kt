package dev.kb896.aiusage.data

import android.content.Context
import dev.kb896.aiusage.service.Graph

/**
 * A change to the settings, saved AND sent to the back, one at a time.
 *
 * Every writer used to do the three steps itself — load, copy, save, then push the payload — and
 * nothing stopped two of them interleaving: the settings screen, a DPI nudged on the back, the
 * portal's tile. That was survivable while all of them ran on the main thread. A command from
 * outside does not, and can arrive in a burst, so the steps are one synchronised act here:
 * the edit is given the settings as they are at that moment, and nobody else's write lands
 * between its read and its save.
 *
 * Saving alone changes nothing on the back — the panel reads no store, it is told — so the push
 * is not optional and is not left to the caller to remember.
 */
object ConfigWriter {

    /** The settings after [edit]; unchanged settings are neither saved nor sent. */
    fun update(context: Context, edit: (PanelConfig) -> PanelConfig): PanelConfig = synchronized(this) {
        val app = context.applicationContext
        val store = ConfigStore(app)
        val before = store.load()
        val after = edit(before)
        if (after == before) return before
        store.save(after)
        val graph = Graph.of(app)
        // Calibrating, the panel is showing its grid and takes no payload (as every other writer holds off).
        if (!graph.bridge.calibrating) graph.bridge.update(graph.payload(after))
        after
    }
}
