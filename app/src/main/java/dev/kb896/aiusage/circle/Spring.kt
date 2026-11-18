package dev.kb896.aiusage.circle

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** A damped spring that moves one value to a target, one step per display frame. */
class Spring(private val stiffness: Float, private val damping: Float, private val onValue: (Float) -> Unit) {
    var value = 0f
    var running = false
        private set
    private var vel = 0f
    private var target = 0f
    private var lastNs = 0L
    private var onRest: (() -> Unit)? = null
    private val ch = Choreographer.getInstance()
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 1f / 120f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.034f)
            lastNs = now
            val c = 2f * damping * sqrt(stiffness)
            val n = ceil(dt / 0.001f).toInt(); val h = dt / n
            repeat(n) { vel += (-stiffness * (value - target) - c * vel) * h; value += vel * h }
            if (abs(value - target) < 0.5f && abs(vel) < 20f) { value = target; running = false }
            onValue(value)
            if (running) ch.postFrameCallback(this) else { vel = 0f; val r = onRest; onRest = null; r?.invoke() }
        }
    }

    fun to(t: Float, v0: Float = 0f, then: (() -> Unit)? = null) {
        target = t; vel = v0; onRest = then
        if (!running) { running = true; lastNs = 0L; ch.postFrameCallback(tick) }
    }

    fun stop() { running = false; onRest = null; ch.removeFrameCallback(tick); vel = 0f }
}
