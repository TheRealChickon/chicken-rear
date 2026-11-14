package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.PanelConfig

/** What the rear display is to be doing. */
enum class RearLevel {
    /** Dark: let go of, and nothing automatic lights it. */
    OFF,
    /** Lit under [RearPosture.DIM_VEIL_ALPHA]'s veil over the rear's own brightness, the page animated as ever (face down on a table). */
    DIM,
    /** Lit at the rear's own brightness. */
    NORMAL,
    /** Lit at full brightness (a camera in use). */
    FULL,
}

/**
 * THE BACK'S POSTURE: one rule for what the rear display does, from what is known about the
 * phone. In priority order:
 *
 * 1. MANUAL OFF — the double tap on the lit back. Off until the user lights it himself
 *    (ManualOff); nothing automatic overrides it, not the table, the camera, a card, a call or
 *    the charger.
 * 2. POCKET — off (pocket mode).
 * 3. LYING ON ITS BACK — the rear against the table, at rest (LyingOnBack): off, and on again
 *    by itself when picked up.
 * 4. A CAMERA IN USE — full brightness ("Light up while the camera is open").
 * 5. FACE DOWN — the rear facing up on a table: DIM, with no time limit; NORMAL for a touch
 *    (for [TOUCH_MS] after it), a card, or a call. With "Only while charging" on and no charger
 *    in, off.
 * 6. OTHERWISE — in the hand, standing, anything else: NORMAL. With "Stay on in the hand" on
 *    there is no idle sleep ([idleSleeps]); off, the panel's old idle timer runs.
 *
 * Pure: RearActivity gathers the facts and applies the answer.
 */
object RearPosture {

    data class Settings(
        /** "Dim when face down" (PanelConfig.tableMode). */
        val dimFaceDown: Boolean = true,
        /** "Only while charging", under it (PanelConfig.tableChargingOnly). */
        val faceDownChargingOnly: Boolean = false,
        /** "Off when lying on its back" (PanelConfig.offOnBack). */
        val offOnBack: Boolean = true,
        /** "Stay on in the hand" (PanelConfig.stayOnInHand). */
        val stayOnInHand: Boolean = true,
        /** "Light up while the camera is open" (PanelConfig.cameraLight). */
        val cameraLight: Boolean = true,
    ) {
        companion object {
            fun of(c: PanelConfig) = Settings(
                dimFaceDown = c.tableMode,
                faceDownChargingOnly = c.tableChargingOnly,
                offOnBack = c.offOnBack,
                stayOnInHand = c.stayOnInHand,
                cameraLight = c.cameraLight,
            )
        }
    }

    data class Facts(
        val manualOff: Boolean = false,
        val pocketed: Boolean = false,
        val lyingOnBack: Boolean = false,
        val faceDown: Boolean = false,
        /** A camera in use and the back lit for it (CameraMode.lit): not its own preview, not double-tapped off. */
        val cameraInUse: Boolean = false,
        /** A call ringing or going on (AudioManager's mode: [inCall]). */
        val callActive: Boolean = false,
        /** Touched within [TOUCH_MS]. */
        val recentTouch: Boolean = false,
        /** A card arrived and has not had its time yet. */
        val recentNotification: Boolean = false,
        /** A charger in; only read with "Only while charging" on. */
        val plugged: Boolean = true,
    )

    fun wanted(f: Facts, s: Settings): RearLevel = when {
        f.manualOff -> RearLevel.OFF
        f.pocketed -> RearLevel.OFF
        f.lyingOnBack && s.offOnBack -> RearLevel.OFF
        f.cameraInUse && s.cameraLight -> RearLevel.FULL
        f.faceDown && s.dimFaceDown -> when {
            s.faceDownChargingOnly && !f.plugged -> RearLevel.OFF
            f.callActive || f.recentTouch || f.recentNotification -> RearLevel.NORMAL
            else -> RearLevel.DIM
        }
        else -> RearLevel.NORMAL
    }

    /** Whether the panel's idle timer runs: only at NORMAL, and only with "Stay on in the hand" off. */
    fun idleSleeps(level: RearLevel, s: Settings): Boolean = level == RearLevel.NORMAL && !s.stayOnInHand

    /**
     * A phone call ringing or under way, from AudioManager's mode — which needs no permission,
     * where TelephonyManager's call state and TelecomManager.isInCall need READ_PHONE_STATE.
     * IN_COMMUNICATION is a call in another app (WhatsApp, Signal, Meet).
     */
    fun inCall(audioMode: Int): Boolean = audioMode == android.media.AudioManager.MODE_RINGTONE ||
        audioMode == android.media.AudioManager.MODE_IN_CALL ||
        audioMode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
        audioMode == android.media.AudioManager.MODE_CALL_SCREENING

    /**
     * The table's dim: a black veil at this alpha over the rear's own brightness, never a fixed
     * level (which outshines auto-brightness turned right down, so the dim could be brighter
     * than normal). The double tap's and always-on's veil strength.
     */
    const val DIM_VEIL_ALPHA = 0.6f

    /**
     * The share of normal's light left under a black veil of [alpha]: the blend is in the
     * encoded (gamma ~2.2) values, so about 13% at [DIM_VEIL_ALPHA] — a tenth, roughly, and
     * below normal whatever normal is.
     */
    fun dimShare(alpha: Float = DIM_VEIL_ALPHA): Double = Math.pow((1f - alpha).toDouble(), 2.2)

    /** How long a touch on the face-down back keeps it at normal brightness. */
    const val TOUCH_MS = 30_000L
}
