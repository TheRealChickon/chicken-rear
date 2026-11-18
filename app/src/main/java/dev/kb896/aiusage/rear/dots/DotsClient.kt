package dev.kb896.aiusage.rear.dots

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import dev.kb896.aiusage.IDots
import dev.kb896.aiusage.IDotsSink
import java.nio.ByteBuffer

/** What the usage page needs of OpenAI's Dots character (rear/UsageFace.draw). */
interface DotsFeed {
    /** Its latest picture (a square, transparent round the figure), or null when there is none to show: then nothing stands in its place. */
    fun picture(): Bitmap?
    /** Its own signature move, now (DotsActor says when). */
    fun signature()
    /** OpenAI's own reduced motion, while its account is spent. */
    fun calm(on: Boolean)
}

/**
 * The page's end of DotsService: in the shell (the back's usage page; the binder comes from the app through
 * IRearService.setDots) and in the settings' preview (bound by DotsLink). It asks for pictures only while they are
 * wanted: the first [picture] starts the watch, and [STOP_AFTER_MS] without one stops it (the page has gone), so the
 * renderer in the ":dots" process draws only while the page is up.
 *
 * The pictures come through one piece of shared memory with [slots] squares; the service says which slot is whole
 * and [picture] copies that one into its bitmap when it is new. A picture older than [STALE_MS] (the ":dots"
 * process frozen or gone) is not shown.
 */
class DotsClient(
    private val handler: Handler,
    /** The character wanted now (PanelConfig.chatGptDot). */
    private val character: () -> String,
    private val size: Int = SIZE,
    private val tag: String = "AiUsageDots",
) : DotsFeed {
    @Volatile private var dots: IDots? = null
    @Volatile private var watching: String? = null
    @Volatile private var askedAt = 0L
    @Volatile private var goneUntil = 0L
    @Volatile private var memory: SharedMemory? = null
    @Volatile private var mapped: ByteBuffer? = null
    @Volatile private var side = 0
    @Volatile private var latestSlot = -1
    @Volatile private var latestSeq = -1L
    @Volatile private var latestAt = 0L
    private var shownSeq = -1L
    private var bitmap: Bitmap? = null
    private var calmNow: Boolean? = null

    private val death = IBinder.DeathRecipient { Log.w(tag, "dots: the renderer's process is gone"); handler.post { forget() } }

    private val sink = object : IDotsSink.Stub() {
        override fun frames(b: Bundle?) {
            val mem = b?.getParcelable("memory", SharedMemory::class.java) ?: return
            val s = b.getInt("size")
            handler.post {
                synchronized(this@DotsClient) {
                    unmap()
                    memory = mem; side = s
                    mapped = runCatching { mem.mapReadOnly() }.onFailure { Log.w(tag, "dots: its pictures cannot be read: ${it.message}") }.getOrNull()
                    latestSlot = -1; latestSeq = -1L; shownSeq = -1L
                }
            }
        }
        override fun frame(slot: Int, seq: Long) { latestSlot = slot; latestSeq = seq; latestAt = SystemClock.uptimeMillis() }
        override fun gone(why: String?) {
            Log.i(tag, "dots: no character — ${why.orEmpty()}")
            goneUntil = SystemClock.uptimeMillis() + RETRY_MS
            handler.post { watching = null; latestSlot = -1 }
        }
    }

    /** The service's binder (null: there is none, or it went). */
    fun attach(binder: IBinder?) {
        handler.post {
            dots?.asBinder()?.let { b -> runCatching { b.unlinkToDeath(death, 0) } }
            forget()
            dots = binder?.let { IDots.Stub.asInterface(it) }
            binder?.let { b -> runCatching { b.linkToDeath(death, 0) }.onFailure { dots = null } }
            goneUntil = 0L
        }
    }

    private fun forget() = synchronized(this) {
        watching = null; latestSlot = -1; latestAt = 0L; calmNow = null
        unmap()
    }

    private fun unmap() {
        mapped?.let { runCatching { SharedMemory.unmap(it) } }
        mapped = null
        memory?.close(); memory = null
    }

    private val stopWhenUnwanted = object : Runnable {
        override fun run() {
            val idle = SystemClock.uptimeMillis() - askedAt
            if (watching == null) return
            if (idle < STOP_AFTER_MS) { handler.postDelayed(this, STOP_AFTER_MS - idle); return }
            runCatching { dots?.stop(sink) }
            watching = null; latestSlot = -1; calmNow = null
        }
    }

    override fun picture(): Bitmap? {
        val now = SystemClock.uptimeMillis()
        askedAt = now
        val d = dots ?: return null
        val want = character()
        if (watching != want && now >= goneUntil) {
            val first = watching == null
            watching = want
            runCatching { d.watch(sink, want, size) }.onFailure { watching = null }
            if (first) handler.postDelayed(stopWhenUnwanted, STOP_AFTER_MS)
        }
        val slot = latestSlot
        if (slot < 0 || now - latestAt > STALE_MS) return null
        synchronized(this) {
            val buf = mapped ?: return null
            val s = side
            if (s <= 0) return null
            val bmp = bitmap?.takeIf { it.width == s } ?: Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { bitmap = it }
            val seq = latestSeq
            if (seq != shownSeq) {
                val len = s * s * 4
                val from = slot * len
                if (from + len > buf.capacity()) return null
                val view = buf.duplicate()
                view.position(from); view.limit(from + len)
                bmp.copyPixelsFromBuffer(view)
                shownSeq = seq
            }
            return bmp
        }
    }

    override fun signature() { runCatching { dots?.signature() } }

    override fun calm(on: Boolean) {
        if (calmNow == on || watching == null) return
        calmNow = on
        runCatching { dots?.calm(on) }
    }

    companion object {
        /** The renderer's square, in pixels (drawn smaller on the page). */
        const val SIZE = 256
        const val STOP_AFTER_MS = 2_000L
        const val STALE_MS = 1_500L
        /** After "no character", asked again this much later at the soonest. */
        const val RETRY_MS = 30_000L
    }
}
