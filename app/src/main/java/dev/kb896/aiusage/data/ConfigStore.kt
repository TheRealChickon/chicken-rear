package dev.kb896.aiusage.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists [PanelConfig] as one JSON blob.
 *
 * One blob rather than a preference per field, matching UsageHistory's reasoning: the
 * config is written whole on every edit, and a growing key set would be worse to migrate
 * and clean up than to re-serialise.
 *
 * Holds no logic of its own (but for deleting a pinned picture's file with its note, [save]). Everything that could be wrong lives in PanelConfig's codec
 * and in [DecodeCache], both pure and JVM-testable; what needs a Context here is kept
 * trivial.
 *
 * [load] is asked a few thousand times an hour (the stay tick, the service loops, every
 * notification, and thirty times a second while the music page follows the sound), and a
 * decode is about 70 µs and 50-70 KB of garbage. So the last decoded config is kept
 * ([DecodeCache]) and handed out again while the stored blob is the same; any write
 * changes the blob and the next [load] decodes it. Decoding is pure, so the answer is the
 * one a fresh decode would give.
 */
class ConfigStore(private val context: Context) {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Nothing stored yet: a fresh install, which starts in companion mode (PanelConfig.FRESH). */
    fun load(): PanelConfig = prefs.getString(KEY, null)?.let(decoded::of) ?: PanelConfig.FRESH

    /**
     * [load], with the file looked at again first. SharedPreferences reads its file once per process and then keeps
     * what it read, so a process that does not write the settings (the back's screen, ":rear") goes on seeing them as
     * they were when it first read them, whatever the app has written since. This reads them again where the file has
     * changed since (MODE_MULTI_PROCESS: deprecated, still honoured, and the only way in). For a seldom read, not for
     * the loops.
     */
    fun loadFresh(): PanelConfig {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
        return load()
    }

    /** The stored blob itself, undecoded — what UiTheme compares to know it may skip one. */
    fun raw(): String? = prefs.getString(KEY, null)

    /**
     * [config] stored. A PINNED PICTURE'S FILE GOES WITH ITS NOTE (data/PinImage): whatever took a note away (the bin on
     * the back, the settings' Remove, the twenty-note limit, a debug unpin), the files the stored notes had and [config]'s
     * have not are deleted here, the one place every such change passes.
     */
    fun save(config: PanelConfig) {
        val before = runCatching { load().pins }.getOrDefault(emptyList())
        prefs.edit().putString(KEY, PanelConfig.encode(config)).apply()
        val gone = PinImage.gone(before, config.pins)
        if (gone.isNotEmpty()) runCatching { PinImageFiles.forget(context, gone) }
    }

    /**
     * Calls [onChange] whenever the stored config is written, by anyone in this process —
     * the settings screen's own rows, or the service settling something the panel decided.
     * Returns the way to stop. The listener is held by the returned function, since
     * SharedPreferences keeps its listeners only weakly.
     */
    fun listen(onChange: () -> Unit): () -> Unit {
        val p = prefs
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY) onChange() }
        p.registerOnSharedPreferenceChangeListener(listener)
        return { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS = "panel_config"
        const val KEY = "config"

        /** One per process, as the SharedPreferences it sits on are. */
        val decoded = DecodeCache()
    }
}

/**
 * The last stored blob and what it decoded to: the same blob gives back the same [PanelConfig],
 * any other blob is decoded (and kept in its place).
 *
 * SharedPreferences hands back the very String it holds until the next write, so a hit is an
 * identity comparison; an equal string in another object (read anew) is a hit too, compared
 * without allocating. [PanelConfig] is immutable (vals, read-only lists), so sharing one
 * instance between callers is safe. Threads may race on [of]: the worst outcome is one decode
 * too many; the pair is replaced whole, so a blob is never matched with another's config.
 */
internal class DecodeCache(private val decode: (String) -> PanelConfig = PanelConfig::decode) {
    private class Entry(val raw: String, val config: PanelConfig)

    @Volatile private var last: Entry? = null

    fun of(raw: String): PanelConfig {
        val hit = last
        if (hit != null) {
            if (hit.raw === raw) return hit.config
            if (hit.raw == raw) { last = Entry(raw, hit.config); return hit.config }
        }
        val fresh = decode(raw)
        last = Entry(raw, fresh)
        return fresh
    }
}
