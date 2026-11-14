package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Condition

/**
 * What moves on the moon face besides its clip, worked out each frame for the scene's shader
 * (SceneEngine): the real weather's lightning in the Earth's clouds, and now and then a GLITCH, subtle.
 *
 * THE CLOUDS ARE NOT MOVED HERE ANY MORE. They were stirred in the shader by the weather, faster
 * and farther the rougher it was; a displacement of a picture of a globe reads as the globe
 * changing shape. The Earth turns in the clip, rigid. Of the weather only light is left here: LIGHTNING in the clouds
 * in a thunderstorm. Whatever moves by the weather next must not be a displacement of the Earth.
 *
 * THE GLITCH: a twitch of a fifth of a second or so, every nine to twenty-four seconds, at no
 * fixed beat: a few bands of the picture torn sideways and its colours pulled apart, flickering
 * while it lasts. Sometimes two close together. Never while the picture is held still.
 *
 * Pure but for its random source: times in, numbers out, so a test holds it.
 */
class SceneEffects(private val random: () -> Float = { Math.random().toFloat() }) {
    private val out = FloatArray(5)
    private var lastAt = 0L
    private var glitchFrom = 0L
    private var glitchTo = 0L
    private var nextGlitchAt = 0L
    private var flashAt = 0L
    private var nextFlashAt = 0L

    /**
     * At [now] (uptime, ms), for [weather]; [on] false where there is nothing to move (another clip,
     * or the picture held still). The same array each time: two numbers no longer used (always 0: the
     * clouds are not moved), a storm's flash (0..1), the glitch's strength (0..1), its seed. [clouds] and [glitch]: each wanted or not.
     */
    fun at(now: Long, weather: Condition?, on: Boolean, clouds: Boolean = true, glitch: Boolean = true): FloatArray {
        if (!on) { out.fill(0f); lastAt = now; return out }
        lastAt = now
        out[0] = 0f; out[1] = 0f

        // Lightning: a double flicker, every few seconds of a storm.
        out[2] = 0f
        if (clouds && weather == Condition.THUNDER) {
            if (nextFlashAt == 0L) nextFlashAt = now + (2_000 + 6_000 * random()).toLong()
            if (now >= nextFlashAt) { flashAt = now; nextFlashAt = now + (3_000 + 8_000 * random()).toLong() }
            val since = now - flashAt
            if (flashAt != 0L && since < 420L) out[2] = if (since < 90L || since in 160L..260L) 1f - (since % 130L) / 260f else 0f
        } else nextFlashAt = 0L

        // The glitch.
        if (!glitch) { out[3] = 0f; out[4] = 0f; nextGlitchAt = 0L; return out }
        if (nextGlitchAt == 0L) nextGlitchAt = now + gap()
        if (now >= nextGlitchAt && now >= glitchTo) {
            glitchFrom = now
            glitchTo = now + (GLITCH_MIN_MS + (GLITCH_MAX_MS - GLITCH_MIN_MS) * random()).toLong()
            // One in four comes back for a second twitch a moment later.
            nextGlitchAt = if (random() < 0.25f) glitchTo + (120 + 260 * random()).toLong() else glitchTo + gap()
        }
        if (now in glitchFrom until glitchTo) {
            val t = (now - glitchFrom).toFloat() / (glitchTo - glitchFrom)
            // In at once, out over its last third; its pattern jumps some sixteen times a second.
            out[3] = GLITCH_STRENGTH * (if (t < 0.66f) 1f else (1f - t) / 0.34f)
            out[4] = ((now / 60L) % 97L).toFloat()
        } else { out[3] = 0f; out[4] = 0f }
        return out
    }

    private fun gap(): Long = (GAP_MIN_MS + (GAP_MAX_MS - GAP_MIN_MS) * random()).toLong()

    companion object {
        const val GAP_MIN_MS = 9_000L
        const val GAP_MAX_MS = 24_000L
        const val GLITCH_MIN_MS = 140L
        const val GLITCH_MAX_MS = 320L
        /** Subtle: the bands move a third of what the shader could move them, the colours a pixel or two. */
        const val GLITCH_STRENGTH = 0.55f
    }
}
