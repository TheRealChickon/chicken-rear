package dev.kb896.aiusage.circle

class TrLook(val dark: Boolean) {
    val capsule = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    /** The idle bar's capsule and search field. */
    val idleCapsule = if (dark) 0xFF000000.toInt() else 0xFFDFE7FF.toInt()
    val idleField = if (dark) 0xFF28292C.toInt() else 0xFFFFFFFF.toInt()
    val idleIcon = if (dark) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
    val idleHint = if (dark) 0xFFBDC1C6.toInt() else 0xFF444746.toInt()
    /** The pale pill behind "Scroll and translate" and behind each language chip. */
    val pill = if (dark) 0xFF2C303D.toInt() else 0xFFDFE7FF.toInt()
    val label = if (dark) 0xFFEEF0FF.toInt() else 0xFF18181B.toInt()
    val chipInk = if (dark) 0xFFEEF0FF.toInt() else 0xFF031329.toInt()
    /** The translate button while translation is on. */
    val on = if (dark) 0xFFA8C7FA.toInt() else 0xFF0443C7.toInt()
    val onInk = if (dark) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt()
    /** Floating pill: title, second line, the round close button. */
    val title = if (dark) 0xFFE3E3E3.toInt() else 0xFF121212.toInt()
    val sub = if (dark) 0xFFC4C7C5.toInt() else 0xFF4A4A4A.toInt()
    val closeBg = if (dark) 0xFF2C303D.toInt() else 0xFFF0F1F4.toInt()

    companion object {
        // Fade the search controls, expand the field, then reveal the language label.
        const val FADE_OUT_MS = 70f
        const val MUSIC_OUT_MS = 100f
        const val WIDEN_FROM_MS = 30f
        const val WIDEN_MS = 100f
        const val LABEL_FROM_MS = 100f
        const val LABEL_MS = 200f
        const val CHANGE_MS = 300f
        /** "Scroll and translate" pressed: the bar drops away in 4 frames, the floating pill is up after 5 more. */
        const val LEAVE_MS = 130f
        const val PILL_IN_MS = 190L
        // Sizes in dp, on a 412 dp wide screen.
        const val LABEL_W = 140f          // width of the words "Scroll and translate"
        const val LABEL_ICON = 21f
        const val LABEL_GAP = 10f
        const val CHIP_H = 39.1f
        const val CHIP_INSET = 5.85f
        const val CHIP_PAD = 25f
        const val CHIP_ARROW_GAP = 40.3f
        const val CHIPS_BELOW_CLOSE = 30.6f
        const val PILL_W = 222.8f
        const val PILL_H = 67.2f
        const val PILL_BOTTOM = 49f
        // Edge light in scroll mode, as a tint over a white page: strength 0.68 * exp(-distance / 15 dp).
        const val GLOW_EDGE = 0.68f
        const val GLOW_FALL_DP = 15f
        const val GLOW_REACH_DP = 52f
        /** Material symbol "swipe_up" (Apache 2.0), viewBox 0 -960 960 960: the hand with the arrow in Google's bar. */
        const val SWIPE_UP = "M245-400q-51-64-78-141t-27-159q0-27 3-54t9-54l-70 70-42-42 140-140 140 140-42 42-65-64q-7 25-10 50.5t-3 51.5q0 70 22.5 135.5T288-443l-43 43Zm413 273q-23 8-46.5 7.5T566-131L304-253l18-40q10-20 28-32.5t40-14.5l68-5-112-307q-6-16 1-30.5t23-20.5q16-6 30.5 1t20.5 23l148 407-100 7 131 61q7 3 15 3.5t15-1.5l157-57q31-11 45-41.5t3-61.5l-55-150q-6-16 1-30.5t23-20.5q16-6 30.5 1t20.5 23l55 150q23 63-4.5 122.5T815-184l-157 57Zm-90-265-54-151q-6-16 1-30.5t23-20.5q16-6 30.5 1t20.5 23l55 150-76 28Zm113-41-41-113q-6-16 1-30.5t23-20.5q16-6 30.5 1t20.5 23l41 112-75 28Zm8 78Z"
        fun swipeUp(): android.graphics.Path = androidx.core.graphics.PathParser.createPathFromPathData(SWIPE_UP).apply {
            transform(android.graphics.Matrix().apply { setTranslate(0f, 960f); postScale(1 / 40f, 1 / 40f) })
        }
        const val ARROW = "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z"
    }
}
