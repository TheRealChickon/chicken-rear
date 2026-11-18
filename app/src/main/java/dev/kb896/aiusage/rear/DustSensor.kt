package dev.kb896.aiusage.rear

import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.MemoryFile
import android.os.SystemClock
import android.util.Log

/**
 * THE DUST FACE'S SENSORS (DustMotion says why these two): the accelerometer and the gyroscope, registered ONLY while
 * the dust face is drawn on a lit back, and let go the moment it is not: the back dark, the face covered by a page of
 * ours or held still, another face chosen, the shell ending. The scene's own loop decides ([Gate]); nothing else
 * registers them. Registering and letting go are done on a thread of its own (a binder call each, never on the
 * scene's thread between two pictures), where the listener's samples come too (idle while nothing is registered),
 * straight into the face's DustMotion.
 *
 * THROUGH A DIRECT CHANNEL WHERE THE PHONE HAS ONE (DirectReports): the hardware writes the samples into a memory of
 * ours and the scene's thread reads the new ones before each picture's inputs ([poll]), so no sample wakes
 * system_server or a thread of ours. Both sensors must offer one (a memory file, the normal rate). It is ON TRIAL for
 * its first second or so beside the listener, which feeds the dust meanwhile (DirectReports.Session says what it must
 * show: the pace, the listener's rate and the dust's own, the listener's values, the listener's clock for its times);
 * then the channel feeds the dust and the listener goes (DirectReports.Feed: no sample twice, none going back, none
 * lost). Whatever fails: the listener again at once; a failure of the channel's own make (none to be had, another
 * clock, silent while it fed) for as long as this process lives, a passing one (a slow second, another pace, other
 * values) tried again at the next start, DirectReports.TRIES times in a row at most. The same samples into the same
 * DustMotion.accel and gyro, in time order.
 */
class DustSensor(private val sensors: () -> SensorManager?, private val motion: DustMotion) {
    private val thread by lazy { HandlerThread("rear-dust-motion").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    /** Wanted on (the Gate's word), and registered now (on the sensors' thread). */
    @Volatile var wanted = false
        private set
    @Volatile var listening = false
        private set
    /** What was registered: for the log and the debug word. */
    @Volatile var what = ""
        private set

    /** The listener is registered (the sensors' thread alone). */
    private var listenerOn = false
    /** One more at each registration: what was posted for an earlier one is let be. */
    @Volatile private var run = 0
    /** Whether the direct channel is tried again at the next start (DirectReports.Tries). */
    private val tries = DirectReports.Tries()

    /** The direct channel on trial or feeding: made and closed on the sensors' thread, read on the scene's, both under [chLock]. */
    private val chLock = Any()
    @Volatile private var direct: Direct? = null

    /** Who gives samples to DustMotion, and their rates for the log: under [feedLock] (either thread). */
    private val feedLock = Any()
    private val feed = DirectReports.Feed()
    private val rates = DirectReports.Rates()

    private class Direct(val run: Int, val channel: SensorDirectChannel, val memory: MemoryFile, val session: DirectReports.Session)

    /** The channel's side of DustMotion and of who feeds it: called inside [poll], under [feedLock]. */
    private val host = object : DirectReports.Session.Host {
        override fun give(k: Int, x: Float, y: Float, z: Float, t: Long) = this@DustSensor.give(DirectReports.DIRECT, k, x, y, z, t)
        override fun takeOver() = feed.handTo(DirectReports.DIRECT)
        override fun handBack() = feed.handTo(DirectReports.LISTENER)
        override fun resync() = motion.resync()
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> give(DirectReports.LISTENER, DirectReports.ACCEL, e.values[0], e.values[1], e.values[2], e.timestamp)
                Sensor.TYPE_GYROSCOPE -> give(DirectReports.LISTENER, DirectReports.GYRO, e.values[0], e.values[1], e.values[2], e.timestamp)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** One sample from [src], on to DustMotion if [src] feeds it now: whether it went. */
    private fun give(src: Int, k: Int, x: Float, y: Float, z: Float, t: Long): Boolean {
        synchronized(feedLock) {
            if (!feed.take(src, k, t)) return false
            rates.sample(k, t)
            // The listener's samples, for the channel on trial beside it.
            if (src == DirectReports.LISTENER) direct?.session?.listened(k, x, y, z, t)
            if (k == DirectReports.ACCEL) motion.accel(x, y, z, t) else motion.gyro(x, y, z, t)
            return true
        }
    }

    /** The sensors' thread has been made (the first [start]): until then there is nothing to let go of. */
    @Volatile private var made = false

    /** On, at DustMotion.RATE_US, from the sensors' thread. Any thread; true: asked for. */
    fun start(): Boolean {
        wanted = true
        made = true
        handler.post { if (wanted) register() }
        return true
    }

    /** Off, on the sensors' thread, after anything asked before it (a registration under way is let go of too). Any thread. */
    fun stop() {
        wanted = false
        if (made) handler.post { if (!wanted) unregister() }
    }

    private fun register() {
        if (listening) return
        val m = runCatching { sensors() }.getOrNull()
        val a = m?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (m == null || a == null) { Log.i(TAG, "dust: no accelerometer to be had here — the dust keeps still for the phone's movement"); return }
        val g = m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        run++
        motion.reset()
        synchronized(feedLock) { feed.fresh() }
        val okA = runCatching { m.registerListener(listener, a, DustMotion.RATE_US, handler) }.onFailure { Log.w(TAG, "dust: the accelerometer: ${it.message}") }.getOrDefault(false)
        val okG = g != null && runCatching { m.registerListener(listener, g, DustMotion.RATE_US, handler) }.onFailure { Log.w(TAG, "dust: the gyroscope: ${it.message}") }.getOrDefault(false)
        listenerOn = okA || okG
        listening = listenerOn
        what = listOfNotNull("accelerometer".takeIf { okA }, "gyroscope".takeIf { okG }).joinToString(" and ")
        if (listening) Log.i(TAG, "dust: the $what on (${1_000_000 / DustMotion.RATE_US} a second)")
        if (okA && okG) openDirect(m, a, g!!)
    }

    /** The direct channel, on trial beside the listener; if it cannot be had, the listener alone from now on. */
    private fun openDirect(m: SensorManager, a: Sensor, g: Sensor) {
        if (tries.off) return
        val memoryType = SensorDirectChannel.TYPE_MEMORY_FILE
        if (!a.isDirectChannelTypeSupported(memoryType) || !g.isDirectChannelTypeSupported(memoryType) ||
            a.highestDirectReportRateLevel < SensorDirectChannel.RATE_NORMAL || g.highestDirectReportRateLevel < SensorDirectChannel.RATE_NORMAL) {
            giveUp("the phone offers none for both sensors at the normal rate", structural = true)
            return
        }
        var memory: MemoryFile? = null
        var channel: SensorDirectChannel? = null
        runCatching {
            val mem = MemoryFile("dust-sensors", DirectReports.SLOTS * DirectReports.SIZE).also { memory = it }
            val ch = m.createDirectChannel(mem).also { channel = it }
            val ta = ch.configure(a, SensorDirectChannel.RATE_NORMAL)
            val tg = ch.configure(g, SensorDirectChannel.RATE_NORMAL)
            check(ta > 0 && tg > 0) { "configured as $ta and $tg" }
            val read = DirectReports.Memory { dst, from, count -> mem.readBytes(dst, from, 0, count) }
            val session = DirectReports.Session(read, ta, tg, SystemClock.elapsedRealtimeNanos())
            synchronized(chLock) { direct = Direct(run, ch, mem, session) }
            Log.i(TAG, "dust: the sensors' direct channel on trial beside the listener")
        }.onFailure {
            runCatching { channel?.close() }; runCatching { memory?.close() }
            giveUp("the direct channel: $it", structural = true)
        }
    }

    private fun giveUp(why: String, structural: Boolean) {
        if (tries.off) return
        if (tries.failed(structural)) Log.i(TAG, "dust: the sensors from the listener, not a direct channel, in this process: $why")
        else Log.i(TAG, "dust: the sensors from the listener this time, the direct channel tried again at the next start (${tries.count} of ${DirectReports.TRIES} in a row): $why")
    }

    private fun unregister() {
        if (!listening) return
        val d = synchronized(chLock) { direct.also { direct = null } }
        if (d != null) {
            // It fed the dust to the end: what failed before was passing.
            if (d.session.state == DirectReports.Session.FEEDING) tries.fedToTheEnd()
            close(d)
        }
        if (listenerOn) { runCatching { sensors()?.unregisterListener(listener) }; listenerOn = false }
        listening = false
        Log.i(TAG, "dust: the $what off")
    }

    private fun close(d: Direct) {
        runCatching { d.channel.close() }
        runCatching { d.memory.close() }
    }

    /**
     * THE DIRECT CHANNEL'S NEW SAMPLES, on the scene's thread before each picture's inputs (SceneEngine.playDust):
     * read in time order and, once its trial is passed, given to DustMotion as the listener gave them
     * (DirectReports.Session). Nothing to do without a channel; nothing allocated on a picture but the log's words at a
     * hand-over or a failure; no binder call here (letting the listener go and taking it back are posted to the sensors'
     * thread).
     */
    fun poll() {
        if (direct == null) return
        var verdict = DirectReports.WAIT
        var d: Direct? = null
        synchronized(chLock) {
            val c = direct ?: return
            d = c
            verdict = synchronized(feedLock) { c.session.poll(SystemClock.elapsedRealtimeNanos(), host) }
            if (verdict == DirectReports.FAIL) direct = null
        }
        val c = d ?: return
        when (verdict) {
            DirectReports.PASS -> {
                handler.post {
                    if (run == c.run && direct === c && c.session.letGo(listenerOn)) {
                        runCatching { sensors()?.unregisterListener(listener) }
                        listenerOn = false
                    }
                }
                val hz = c.session.watch.hz
                Log.i(TAG, "dust: the sensors through the direct channel now (the accelerometer ${Math.round(hz[0])} a second, the gyroscope ${Math.round(hz[1])}), the listener let go")
            }
            DirectReports.FAIL -> {
                giveUp(c.session.why ?: "it could not be read", c.session.structural)
                handler.post {
                    close(c)
                    // Fed by the channel until now: the listener again, at once.
                    if (run == c.run && c.session.listenAgain(listenerOn, sensorsOn = wanted && listening)) listenAgain()
                }
            }
        }
    }

    /** The listener back after the direct channel was given up (on the sensors' thread). */
    private fun listenAgain() {
        val m = runCatching { sensors() }.getOrNull() ?: return
        val okA = runCatching { m.registerListener(listener, m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), DustMotion.RATE_US, handler) }.getOrDefault(false)
        val okG = runCatching { m.registerListener(listener, m.getDefaultSensor(Sensor.TYPE_GYROSCOPE), DustMotion.RATE_US, handler) }.getOrDefault(false)
        listenerOn = okA || okG
        Log.i(TAG, "dust: the listener on again (${if (okA) "accelerometer" else "no accelerometer"}, ${if (okG) "gyroscope" else "no gyroscope"})")
    }

    /**
     * For the dust's ten-second line (DustPace.line, on the scene's thread): the rates the samples given to DustMotion
     * came at since the last line, and how they came. Counted afresh.
     */
    fun said(): String {
        val path = when {
            !listening -> DirectReports.OFF
            direct == null -> DirectReports.BY_LISTENER
            else -> synchronized(feedLock) { if (feed.from == DirectReports.DIRECT) DirectReports.BY_DIRECT else DirectReports.ON_TRIAL }
        }
        return synchronized(feedLock) { rates.said(path) }
    }

    /**
     * WHEN THEY ARE ON, from the scene's loop: [showing] each frame or held picture (still = covered by a page of ours,
     * or a dim still's posture), [end] as the face's show ends however it ends (dark, replaced, failed). Pure.
     */
    class Gate(private val on: () -> Boolean, private val off: () -> Unit, private val allowed: () -> Boolean = { true }) {
        var listening = false
            private set
        private var tried = false

        /**
         * Each picture: on while it moves, off while it is held. [allowed] is the setting "Moves with the phone"
         * (PanelConfig.dustMovesWithPhone), read every picture: switched off, the sensors go at once and the dust no
         * longer follows the phone (the finger still moves it); switched on, they come on with the next picture.
         */
        fun showing(still: Boolean) {
            val ok = allowed()
            if (!still && ok && !listening && !tried) { tried = true; listening = on() }
            else if ((still || !ok) && (listening || tried)) { if (listening) off(); listening = false; tried = false }
        }

        fun end() {
            if (listening) off()
            listening = false; tried = false
        }
    }

    companion object { private const val TAG = "AiUsageRear" }
}
