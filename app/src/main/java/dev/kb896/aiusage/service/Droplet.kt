package dev.kb896.aiusage.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.nio.ByteOrder

object Droplet {
    private const val TAG = "AiUsagePin"
    /** Not asked for within this long of being opened: the track is let go (the pin went some other way). */
    private const val WAIT_MS = 8_000L
    /** Silence is written in pieces this long while it waits: the place of the sound is decided to within one. */
    private const val PIECE_MS = 4

    private class Pcm(val of: String, val data: ShortArray, val rate: Int, val channels: Int, val attack: Int)

    @Volatile private var cached: Pcm? = null
    private val lock = Any()

    /**
     * A drop made ready for one pin, or null when this pin is silent (a demo not asked to sound, the phone on silent or
     * vibrate, Do Not Disturb). Cheap: the decoding and the track are on a thread of the shot's own.
     */
    fun arm(context: Context, demo: Boolean, asked: Boolean): Shot? {
        val app = context.applicationContext
        val mode = runCatching { app.getSystemService(android.media.AudioManager::class.java).ringerMode }.getOrDefault(2)
        val filter = runCatching { app.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter }.getOrDefault(0)
        if (!PinChime.plays(mode, filter, demo, asked)) {
            Log.i(TAG, "chime: none for this pin (${if (demo && !asked) "a demo" else if (mode != 2) "the phone is on silent or vibrate" else "Do Not Disturb"})")
            return null
        }
        return Shot(app).also { it.start() }
    }

    /** One pin's drop. [at] once with the moment; [now] when the pin ended before it (a touch): heard at once. */
    class Shot internal constructor(private val app: Context) : Thread("pin-drop") {
        @Volatile private var target = UNSET
        @Volatile private var asked = 0L
        @Volatile private var why = ""
        /** When the island's first frame really was on the glass (by the frame's own clock), for the log. */
        @Volatile var islandShown = 0L

        init { isDaemon = true; priority = MAX_PRIORITY }

        /** Its attack to be heard at [presentNanos] (System.nanoTime). Only the first call counts. */
        fun at(presentNanos: Long, how: String) {
            if (target != UNSET) return
            why = how; asked = System.nanoTime(); target = presentNanos
        }

        /** At once (the pin's screen is going before the island). */
        fun now() { if (target == UNSET) { why = "at once, the pin ended early"; asked = System.nanoTime(); target = ASAP } }

        override fun run() {
            val opened = SystemClock.uptimeMillis()
            val p = runCatching { pcm(app) }.onFailure { Log.w(TAG, "chime: not decoded: ${it.javaClass.simpleName}") }.getOrNull()
            if (p == null) { fallback(opened); return }
            val track = runCatching { track(p) }.onFailure { Log.w(TAG, "chime: no track: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}") }.getOrNull()
            if (track == null) { fallback(opened); return }
            try { play(track, p, opened) } catch (e: Throwable) {
                Log.w(TAG, "chime: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
            } finally { runCatching { track.stop() }; runCatching { track.release() } }
        }

        private fun play(track: AudioTrack, p: Pcm, opened: Long) {
            val piece = maxOf(32, p.rate * PIECE_MS / 1000)
            val silence = ShortArray(piece * p.channels)
            var written = 0L
            track.play()
            val ts = AudioTimestamp()
            var stamped = false
            // Silence until it is asked for, and until the next piece would pass the place of the sound.
            while (true) {
                val t = target
                if (t == UNSET) {
                    if (SystemClock.uptimeMillis() - opened > WAIT_MS) { Log.i(TAG, "chime: not asked for, let go"); return }
                    written += write(track, silence, piece, p.channels); continue
                }
                if (t == ASAP) break
                stamped = track.getTimestamp(ts)
                if (!stamped) {
                    // No timestamp yet (the track has only just begun to be heard): not wait for ever.
                    if (t - System.nanoTime() > 40_000_000L) { written += write(track, silence, piece, p.channels); continue }
                    Log.w(TAG, "chime: the track gave no timestamp in time — played now")
                    break
                }
                val need = DropletTime.silenceFrames(written, ts.framePosition, ts.nanoTime, t, p.rate, p.attack)
                if (need > piece) { written += write(track, silence, piece, p.channels); continue }
                if (need > 0) written += write(track, silence, need.toInt(), p.channels)
                if (need < 0) Log.w(TAG, "chime: LATE by ${"%.1f".format(-need * 1000.0 / p.rate)} ms (asked for ${(t - asked) / 1_000_000} ms ahead)")
                break
            }
            val attackAt = written + p.attack
            var i = 0
            while (i < p.data.size) {
                val n = track.write(p.data, i, minOf(p.data.size - i, piece * p.channels * 8), AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                i += n
            }
            written += p.data.size / p.channels
            // A tail of silence pushes the end of the sound out through the track's buffer.
            repeat(30) { written += write(track, silence, piece, p.channels) }
            // Where its attack was heard, by the track's own clock, against what was asked for and the island's own frame.
            val until = SystemClock.uptimeMillis() + 1_500
            var heard = 0L
            while (SystemClock.uptimeMillis() < until) {
                if (track.getTimestamp(ts) && ts.framePosition >= attackAt) { heard = DropletTime.heardAt(attackAt, ts.framePosition, ts.nanoTime, p.rate); break }
                sleep(10)
            }
            val t = target
            val shown = islandShown
            Log.i(TAG, "chime: the drop's attack (${p.attack * 1000 / p.rate} ms into the sound) " + when {
                heard == 0L -> "not seen heard (no timestamp)"
                t == ASAP -> "heard ${(heard - asked) / 1_000_000} ms after it was asked for ($why)"
                else -> "heard ${"%+.1f".format((heard - t) / 1e6)} ms from the moment asked for" +
                    (if (shown != 0L) ", ${"%+.1f".format((heard - shown) / 1e6)} ms from the island's first frame on the glass" else "") +
                    " (asked ${(t - asked) / 1_000_000} ms ahead; $why)"
            })
            // Let it be heard out before the track goes.
            val end = SystemClock.uptimeMillis() + 1_500
            while (SystemClock.uptimeMillis() < end) {
                if (track.getTimestamp(ts) && ts.framePosition >= written - piece) break
                sleep(20)
            }
        }

        /** The sound could not be had this way: the old one (PinChime), at the moment less its lead. */
        private fun fallback(opened: Long) {
            while (target == UNSET && SystemClock.uptimeMillis() - opened < WAIT_MS) sleep(10)
            val t = target
            if (t == UNSET) return
            if (t != ASAP) { val wait = (t - System.nanoTime()) / 1_000_000 - PinChime.LEAD_MS; if (wait > 0) sleep(wait) }
            PinChime.play(app, demo = false, asked = true)
        }

        private fun write(track: AudioTrack, silence: ShortArray, frames: Int, channels: Int): Int {
            val n = track.write(silence, 0, frames * channels, AudioTrack.WRITE_BLOCKING)
            if (n < 0) error("write $n")
            return n / channels
        }

        private fun track(p: Pcm): AudioTrack {
            val mask = if (p.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(p.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(p.rate).setChannelMask(mask).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(min, p.rate * p.channels * 2 * 40 / 1000))
                .build()
        }
    }

    private const val UNSET = -1L
    private const val ASAP = 0L

    /** The phone's notification sound as 16-bit samples, the same as last time where the setting is the same. */
    private fun pcm(app: Context): Pcm? {
        val set = Settings.System.getString(app.contentResolver, Settings.System.NOTIFICATION_SOUND).orEmpty()
        synchronized(lock) {
            cached?.takeIf { it.of == set }?.let { return it }
            val began = SystemClock.uptimeMillis()
            val ex = MediaExtractor()
            try {
                val path = set.removePrefix("file://").takeIf { set.startsWith("file://") && java.io.File(it).canRead() }
                if (path != null) ex.setDataSource(path)
                else app.contentResolver.openAssetFileDescriptor(Settings.System.DEFAULT_NOTIFICATION_URI, "r")!!.use { ex.setDataSource(it) }
                val p = decode(ex, set) ?: return null
                Log.i(TAG, "chime: the notification sound decoded in ${SystemClock.uptimeMillis() - began} ms (${p.data.size / p.channels * 1000L / p.rate} ms, ${p.rate} Hz, its attack ${p.attack * 1000.0 / p.rate} ms in)")
                cached = p
                return p
            } finally { ex.release() }
        }
    }

    private fun decode(ex: MediaExtractor, of: String): Pcm? {
        val index = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        ex.selectTrack(index)
        val format = ex.getTrackFormat(index)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        val out = java.io.ByteArrayOutputStream()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            val until = SystemClock.uptimeMillis() + 3_000
            while (SystemClock.uptimeMillis() < until) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return null
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size)
                    b.position(info.offset); b.get(bytes)
                    out.write(bytes)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { runCatching { codec.stop() }; codec.release() }
        if (channels !in 1..2) return null
        val raw = out.toByteArray()
        if (raw.size < 4) return null
        val data = ShortArray(raw.size / 2)
        java.nio.ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).asShortBuffer().get(data)
        return Pcm(of, data, rate, channels, DropletTime.attack(data, channels))
    }
}
