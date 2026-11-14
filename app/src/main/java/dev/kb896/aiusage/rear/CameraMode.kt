package dev.kb896.aiusage.rear

/**
 * CAMERA MODE ("Light up while the camera is open", PanelConfig.cameraLight): while a camera
 * is in use on the front, the back shows the normal animated clock page at FULL brightness —
 * no dim, no always-on, no table dim, no idle sleep — and never a viewfinder. The poll
 * service's CameraWatch says when a use starts and ends (RearActivity.ACTION_CAMERA).
 *
 * - LIT: the panel is up for the camera.
 * - DARK: a double tap during LIT switched the back off; the panel's manual latch (ManualOff)
 *   keeps it off past the camera closing, until the user lights it himself.
 * - ASIDE: the back is not the panel's — the camera's own rear preview is on it, a guest app,
 *   a pocket. The panel stands aside and does not cover it; told the back is free again
 *   while the camera is still in use, it lights.
 *
 * [before] is what the back was doing when it lit, for what comes back when the camera
 * closes: dark, lit, or always-on. The table is kept apart: the posture (RearPosture) puts
 * LIT above it, and it is what comes back once the camera closes.
 *
 * Pure: the activity asks what to do and does it.
 */
class CameraMode {

    enum class State { OFF, LIT, DARK, ASIDE }

    /** What the back was doing before the camera lit it. */
    enum class Before { DARK, LIT, AOD }

    var state = State.OFF
        private set

    var before = Before.LIT
        private set

    val lit: Boolean get() = state == State.LIT

    /**
     * A camera in use and the back free for the panel. [enabled] is the switch, [allowed] every
     * refusal answered (a pocket, a guest). True if it lit now; never out of DARK — the double
     * tap's off holds until the camera closes.
     */
    fun start(enabled: Boolean, allowed: Boolean, before: Before): Boolean {
        if (!enabled || !allowed) return false
        if (state != State.OFF && state != State.ASIDE) return false
        this.before = before
        state = State.LIT
        return true
    }

    /** The back taken by something else while the camera is in use. True if it was LIT. */
    fun aside(): Boolean {
        return when (state) {
            State.OFF -> { state = State.ASIDE; false }
            State.LIT -> { state = State.ASIDE; true }
            State.DARK, State.ASIDE -> false
        }
    }

    /** The double tap (the panel's, or HyperOS's own with the front still on) during LIT. True if it went dark now. */
    fun doubleTap(): Boolean {
        if (state != State.LIT) return false
        state = State.DARK
        return true
    }

    /**
     * The camera closed (or the switch went off): over, whatever it was. Returns [before] when
     * it was LIT — the state to go back to — and null when there is nothing to undo.
     */
    fun end(): Before? {
        val was = state
        state = State.OFF
        return if (was == State.LIT) before else null
    }
}
