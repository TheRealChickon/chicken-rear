package dev.kb896.aiusage.ui.glass

/**
 * Colours keyed on what is behind a control, not on the system theme.
 *
 * ---------------------------------------------------------------------------------
 * The values below are read out of the catalog app of AndroidLiquidGlass (the
 * `backdrop` library) by Kyant0, https://github.com/Kyant0/AndroidLiquidGlass, cited
 * per constant by file and line, and used under the Apache License 2.0:
 *
 *   Copyright 2025 Kyant
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * ---------------------------------------------------------------------------------
 *
 * The catalog keys light and dark on isSystemInDarkTheme(), and so does this now ([dark]):
 * the settings follow the phone's light or dark mode. The luminance parameters stay for
 * the callers' sake and no longer change an answer.
 */
object Ink {
    const val LIGHT = 0.5f

    /**
     * THE SYSTEM'S THEME, not the cell's: dark when the phone is in dark mode, light when it
     * is not. Set from the configuration by the settings screen as it is created
     * ([follow]); a change of theme recreates that activity, which sets it again before a
     * view is built. The panel's process never sets it and never reads these tokens.
     */
    @Volatile var dark = true

    /** Take the theme from [config]'s night bit. */
    fun follow(config: android.content.res.Configuration) {
        dark = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Light means the light THEME. Nothing reacts to the cell behind a control any more:
     * the whole screen is one ground, near-black or near-white, and every control takes
     * the answer for it wherever it sits.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isLight(luminance: Float) = !dark

    /** Text: white on the dark screen, near-black on the light one. */
    @Suppress("UNUSED_PARAMETER")
    fun primary(luminance: Float): Int = if (dark) 0xFFFFFFFF.toInt() else PRIMARY_LIGHT

    const val PRIMARY_LIGHT = 0xFF1C1C1E.toInt()

    /** The quieter text: the primary at 60%. */
    @Suppress("UNUSED_PARAMETER")
    fun secondary(luminance: Float): Int = if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt()

    /** Quietest text: a short note under a control, below [secondary]. */
    @Suppress("UNUSED_PARAMETER")
    fun tertiary(luminance: Float): Int = if (dark) TERTIARY else TERTIARY_LIGHT

    const val TERTIARY = 0x73FFFFFF
    /** Black at 54%: #757575 on white, still above 4.5:1. */
    const val TERTIARY_LIGHT = 0x8A000000.toInt()

    /**
     * The toggle's on colour: a grey, not the accent. The thumb is white, so a white track
     * would swallow it; light grey on the dark screen, a dark grey on the light one.
     */
    @Suppress("UNUSED_PARAMETER")
    fun on(luminance: Float): Int = if (dark) ON else ON_LIGHT

    const val ON = 0xFF9A9AA0.toInt()
    const val ON_LIGHT = 0xFF58585C.toInt()

    /**
     * The app's one accent: the bottom bar's open tab, the confirm in every dialog, a
     * slider's fill, a done setup step, the font list's tick. Neutral on purpose, so it is
     * the text's own end of the scale: a soft white (#ECECEF) on the dark screen, a near-black
     * on the light one. styles_dialog.xml (through colors.xml) repeats it for what the
     * framework tints. The back does not read it (RearDialView keeps its own colours).
     */
    @Suppress("UNUSED_PARAMETER")
    fun accent(luminance: Float): Int = if (dark) ACCENT else ACCENT_LIGHT

    /**
     * The dark accent, and the frame colour of the crop and trim editors whichever theme:
     * those draw over a picture under a black shade, where only a light line shows.
     */
    const val ACCENT = 0xFFECECEF.toInt()
    const val ACCENT_LIGHT = 0xFF1C1C1E.toInt()

    /** A row's icon (white drawables, tinted with this): the text's own ink in either theme. */
    fun icon(): Int = primary(0f)

    /** Plain text outside the glass (Panel.Palette.FG's place on the front). */
    fun fg(): Int = if (dark) 0xFFC5C8C6.toInt() else 0xFF2C2C2E.toInt()

    /** Hints and a way out (Panel.Palette.DIM's place on the front). */
    fun dim(): Int = if (dark) 0xFF555555.toInt() else 0xFF7C7C80.toInt()

    /** An inactive track. LiquidToggle.kt:59-60: grey at 0.36 dark; a touch over its 0.2 light. */
    @Suppress("UNUSED_PARAMETER")
    fun track(luminance: Float): Int = if (dark) 0x5C787880 else 0x40787880

    /** The dialog scrim. DialogContent.kt:46-47; no hue in either. */
    @Suppress("UNUSED_PARAMETER")
    fun scrimAlpha(luminance: Float): Float = if (dark) 0.56f else 0.23f
    @Suppress("UNUSED_PARAMETER")
    fun scrimColor(luminance: Float): Int = if (dark) 0xFF121212.toInt() else 0xFF1C1C1E.toInt()

    /** [colour] moved [t] of the way toward [toward], per channel, alpha kept. */
    fun mix(colour: Int, toward: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (colour shr shift) and 0xFF
            val b = (toward shr shift) and 0xFF
            return Math.round(a + (b - a) * t).coerceIn(0, 255)
        }
        return (colour and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
