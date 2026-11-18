package dev.kb896.aiusage.rear.dots

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's hold on DotsService (":dots"), its binder handed to whoever needs it ([onBinder]: the bridge, for the
 * shell; the settings' [preview]).
 *
 * BOUND ONLY WHEN IT IS WANTED ([wanted]): the switch "ChatGPT's character" is on (PanelConfig.usageDots, OFF until
 * he turns it on, because it runs the ChatGPT app's own native code inside ours) AND a ChatGPT account is signed in
 * (no ChatGPT vessel, no character). Otherwise not bound, and a bound one is let go: no ":dots" process. [sync] is
 * told the wish at start, at every config write and at every usage tick.
 *
 * ONLY A CRASH IS A STRIKE against the ChatGPT build it ran ([Strikes]): the process's own exit record
 * (ApplicationExitInfo) must say a crash, native or not. The system killing it (low memory, HyperOS cleaning the
 * background, the freezer) is no strike. After [MAX_STRIKES] crashes on one build it is not bound again for it, and
 * ChatGPT's vessel shows no character (the service itself refuses, [struckOut], should anything bind it). A new
 * ChatGPT build starts with none; turning the switch on, or picking a character, clears them ([clearStrikes]).
 */
object DotsLink {
    private const val TAG = "AiUsageDots"
    const val MAX_STRIKES = 3
    private const val STRIKES = "dots-strikes"
    /** The exit record can come a moment after the disconnect: looked at again this much later. */
    private const val EXIT_LOOK_MS = 2_000L

    private val thread by lazy { HandlerThread("dots-link").apply { start() } }
    val handler: Handler by lazy { Handler(thread.looper) }
    private val listeners = CopyOnWriteArrayList<(IBinder?) -> Unit>()
    @Volatile var binder: IBinder? = null
        private set
    @Volatile private var bound = false
    @Volatile private var versionCode = -1L
    @Volatile private var wish = false
    private var app: Context? = null

    /** Whether the ":dots" process is to be bound at all: the switch on and ChatGPT signed in. */
    fun wanted(switchOn: Boolean, chatGptSignedIn: Boolean): Boolean = switchOn && chatGptSignedIn

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            binder = b
            Log.i(TAG, "dots: the character process is bound")
            for (l in listeners) runCatching { l(b) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            for (l in listeners) runCatching { l(null) }
            val c = app ?: return
            Log.i(TAG, "dots: the character process is gone; its exit record is read in ${EXIT_LOOK_MS} ms")
            handler.postDelayed({ tallyExits(c) }, EXIT_LOOK_MS)
        }
    }

    /**
     * Bound if [want] (see [wanted]) and the renderer can be had, let go if not. On its own thread (finding it may
     * read ChatGPT's APK once per update). Any thread, any number of times.
     */
    fun sync(context: Context, want: Boolean) {
        val c = context.applicationContext
        app = c
        wish = want
        handler.post { attempt(c) }
    }

    /** On [handler]: what [wish] says, done. */
    private fun attempt(c: Context) {
        if (!wish) { if (bound) Log.i(TAG, "dots: let go — the character is switched off or ChatGPT is not signed in"); unbind(c); return }
        if (bound) return
        val found = DotsSource.onPhone(c)
        if (found !is DotsSource.Found.Ready) { Log.i(TAG, "dots: no character — ${(found as DotsSource.Found.Missing).why}"); return }
        versionCode = found.versionCode
        tallyExits(c)
        if (struckOut(c, found.versionCode)) { Log.i(TAG, "dots: not bound — OpenAI's renderer crashed $MAX_STRIKES times on ChatGPT ${found.versionCode}"); return }
        bound = runCatching { c.bindService(Intent(c, DotsService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) Log.w(TAG, "dots: the character process could not be bound")
    }

    private fun unbind(c: Context) {
        if (!bound) return
        bound = false
        runCatching { c.unbindService(connection) }
        binder = null
        for (l in listeners) runCatching { l(null) }
    }

    /** [l] now with the binder there is (or null) and at every change. Returns the way to stop. */
    fun onBinder(l: (IBinder?) -> Unit): () -> Unit {
        listeners += l
        l(binder)
        return { listeners -= l }
    }

    /**
     * A client for the settings' preview, attached to the binder for as long as [release] is not called. It binds
     * nothing itself: the preview shows a character only where the back would ([sync]).
     */
    fun preview(character: () -> String): Pair<DotsClient, () -> Unit> {
        val client = DotsClient(handler, character, tag = TAG)
        val off = onBinder { b -> client.attach(b) }
        return client to { off(); client.attach(null) }
    }

    // ---- strikes: crashes only, from the process's own exit records ----

    /** One exit of one of our processes, as ApplicationExitInfo gives it. */
    data class Exit(val process: String, val reason: Int, val at: Long)

    /** The strikes on [versionCode], and the newest exit record already looked at ([seen], its time). */
    data class Strikes(val versionCode: Long, val count: Int, val seen: Long) {
        fun text() = "$versionCode $count $seen"

        companion object {
            /** A crash of OpenAI's code (or of ours around it): an uncaught exception or a native signal. */
            fun isCrash(reason: Int) = reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE

            fun parse(text: String): Strikes? = runCatching {
                val p = text.trim().split(' ')
                Strikes(p[0].toLong(), p[1].toInt(), p.getOrNull(2)?.toLong() ?: 0L)
            }.getOrNull()

            /**
             * [before] with the exits of [process] newer than its [seen] added: each crash a strike against
             * [versionCode] (another build's strikes start again from none); any other end (killed for memory, by
             * the system, frozen, stopped) only moves [seen] on.
             */
            fun tally(before: Strikes?, exits: List<Exit>, process: String, versionCode: Long): Strikes {
                val seen = before?.seen ?: 0L
                var count = if (before?.versionCode == versionCode) before.count else 0
                var newest = seen
                for (e in exits) {
                    if (e.process != process || e.at <= seen) continue
                    if (isCrash(e.reason)) count++
                    if (e.at > newest) newest = e.at
                }
                return Strikes(versionCode, count, newest)
            }
        }
    }

    private fun file(c: Context) = File(c.filesDir, STRIKES)

    private fun read(c: Context): Strikes? = runCatching { Strikes.parse(file(c).readText()) }.getOrNull()

    /** The ":dots" process's name. */
    fun process(c: Context) = "${c.packageName}:dots"

    /** The crashes on [versionCode] (another build's do not count). */
    fun strikes(c: Context, versionCode: Long): Int = read(c)?.takeIf { it.versionCode == versionCode }?.count ?: 0

    fun struckOut(c: Context, versionCode: Long): Boolean = strikes(c, versionCode) >= MAX_STRIKES

    /** The ":dots" process's exit records not yet looked at, counted ([Strikes.tally]); unbound at [MAX_STRIKES]. */
    @Synchronized
    fun tallyExits(c: Context) {
        val v = versionCode.takeIf { it >= 0 } ?: return
        val exits = runCatching {
            c.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(c.packageName, 0, 0)
                .map { Exit(it.processName ?: "", it.reason, it.timestamp) }
        }.getOrElse { Log.w(TAG, "dots: exit records not readable (${it.message})"); return }
        val before = read(c)
        val after = Strikes.tally(before, exits, process(c), v)
        if (after == before) return
        runCatching { file(c).writeText(after.text()) }
        val added = after.count - (before?.takeIf { it.versionCode == v }?.count ?: 0)
        if (added > 0) Log.w(TAG, "dots: OpenAI's renderer crashed (strike ${after.count} of $MAX_STRIKES on ChatGPT $v)")
        if (after.count >= MAX_STRIKES) handler.post { unbind(c) }
    }

    /** The strikes taken away (what was already looked at stays looked at), and bound again if it is wanted. */
    fun clearStrikes(context: Context) {
        val c = context.applicationContext
        handler.post {
            val before = read(c)
            runCatching { file(c).writeText(Strikes(versionCode, 0, before?.seen ?: 0L).text()) }
            attempt(c)
        }
    }
}
