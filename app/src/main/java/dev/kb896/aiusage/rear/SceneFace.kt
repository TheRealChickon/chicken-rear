package dev.kb896.aiusage.rear

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import dev.kb896.aiusage.data.SceneClockStyle

/**
 * What stands over the scene's clip on the back: the clock, and the date under it. One painter,
 * View-free, drawn by the scene's engine on the rear and in the settings' preview alike.
 *
 * THE CLOCK IS THE STOCK FISH FACE'S (Xiaomi's rear theme with the betta, read from its manifest.xml). Two stacked
 * lines of two thin, narrow digits and a small date under them, at about three quarters of the stock size, in the
 * corner: a tall one was far too big.
 *
 * ITS COLOURS ARE THE CLIP'S, as the stock face takes its from the fish: a pale tone of the
 * subject for the hours, a deeper one for the minutes and the date ([Place.hour], [Place.minute],
 * read from `<clip>.json` beside the clip).
 *
 * IN FRONT AND BEHIND, as the fish swims: the HOURS are drawn behind the clip's subject and the
 * minutes and date over everything. So this draws in two [Part]s, and SceneEngine cuts the BACK
 * part away where the clip's matte says the subject stands.
 *
 * WHERE IT STANDS: the bottom right corner, always. A clip without a side file gets plain light
 * colours.
 */
object SceneFace {

    /**
     * The clock's two colours, the clip's own: a pale tone of its subject for the hours, a deeper
     * one for the minutes and the date.
     *
     * WHERE IT STANDS IS FIXED: the bottom right corner ([X], [Y]), clear of the rounded corner.
     * It was placed per clip for a while, so that the subject would cover part of it; now it is in the corner,
     * smaller, and the depth is left to chance.
     */
    data class Place(val hour: Int, val minute: Int) {
        companion object {
            val STOCK = Place(0xFFF2EEE8.toInt(), 0xFFC9C4BC.toInt())

            /** From a clip's side file: {"clock": {"hour": "#CFE6F7", "minute": "#469DDB", ...}}. */
            fun parse(json: String): Place? = runCatching {
                if (!json.contains("\"clock\"")) return null
                fun colour(key: String) = Regex("\"$key\"\\s*:\\s*\"#([0-9A-Fa-f]{6})\"").find(json)?.groupValues?.get(1)?.toLong(16)?.let { (0xFF000000L or it).toInt() }
                val hour = colour("hour") ?: return null
                Place(hour, colour("minute") ?: hour)
            }.getOrNull()

            /** The side file of the clip at [clipPath], read; null when there is none. */
            fun of(clipPath: String): Place? = runCatching {
                java.io.File(clipPath.removeSuffix(".mp4") + ".json").takeIf { it.isFile && it.length() < 4096 }?.readText()?.let(::parse)
            }.getOrNull()
        }
    }

    /** What to draw. [level] under 0: the battery is not known, and is left out. */
    data class Look(
        val clock: Boolean = true,
        val style: SceneClockStyle = SceneClockStyle.STACKED,
        val date: Boolean = true,
        val battery: Boolean = false,
        val level: Int = -1,
        val charging: Boolean = false,
        val hour24: Boolean = true,
        /** The clip's own place and colours for the clock; null for the stock ones. */
        val place: Place? = null,
    ) {
        val empty: Boolean get() = !clock && !(battery && level >= 0)
    }

    /** Which of the face: what goes behind the clip's subject (the hours), what stays over it, or all of it. */
    enum class Part { BACK, FRONT, ALL }

    /** The words on it at one moment. Pure: a test, the preview and the shell say the same. */
    data class Words(val hours: String, val minutes: String, val date: String, val battery: String)

    private val DAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    private val MONTHS = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    fun words(look: Look, cal: java.util.Calendar): Words {
        val h24 = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val h = if (look.hour24) h24 else ((h24 + 11) % 12) + 1
        val hours = "%02d".format(h)
        val minutes = "%02d".format(cal.get(java.util.Calendar.MINUTE))
        val date = "${DAYS[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]} ${cal.get(java.util.Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(java.util.Calendar.MONTH)]}"
        val battery = if (look.level >= 0) "${look.level.coerceIn(0, 100)}%" else ""
        return Words(hours, minutes, date, battery)
    }

    /** The key a drawn face is good for: drawn again only when this changes. */
    fun key(look: Look, cal: java.util.Calendar): String = "$look|${words(look, cal)}"

    // The stock face's measures (its manifest's 80, 138 and 38) at about three quarters: smaller, as asked.
    const val DIGIT_W = 62f
    const val DIGIT_H = 96f
    const val LINE = 108f
    private const val DATE_SIZE = 26f
    private const val DATE_DROP = 38f
    /** The hours' top-left corner, in rear pixels: the bottom right, in from the rounded corner. */
    const val X = 976f - 64f - 2 * DIGIT_W
    const val Y = 596f - 46f - DATE_DROP - DIGIT_H - LINE

    // Made on the first draw, not with the object: [words], [key] and [Place.parse] are plain Kotlin and run without Android.
    private val digits by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    private val small by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    private var faces: Array<Typeface>? = null

    /** Thin, light, medium: the system's own face (MiSans on this phone), which the shell has too. */
    private fun faces(): Array<Typeface> = faces ?: arrayOf(
        Typeface.create(Typeface.DEFAULT, 200, false),
        Typeface.create(Typeface.DEFAULT, 300, false),
        Typeface.create(Typeface.DEFAULT, 500, false),
    ).also { faces = it }

    /**
     * Draws [part] of [look] at [cal] onto a canvas [w] by [h] (the panel, or the preview scaled to
     * it). Transparent wherever it draws nothing. Not thread-safe: one caller at a time.
     */
    @Synchronized
    fun draw(c: Canvas, w: Float, h: Float, look: Look, cal: java.util.Calendar, part: Part = Part.ALL) {
        if (look.empty) return
        val u = h / 596f                       // one rear pixel, at this size
        val f = faces()
        val words = words(look, cal)
        val place = look.place ?: Place.STOCK

        if (look.clock) {
            // A digit's box is DIGIT_W by DIGIT_H: the face is set so its figures are that tall, and narrowed to that wide.
            digits.typeface = when (look.style) { SceneClockStyle.THIN -> f[0]; SceneClockStyle.BOLD -> f[2]; SceneClockStyle.STACKED -> f[1] }
            digits.textSize = DIGIT_H * u / 0.715f
            digits.textScaleX = 0.84f
            digits.setShadowLayer(10f * u, 0f, 1f * u, SHADOW)
            fun line(text: String, top: Float, colour: Int) {
                digits.color = colour
                for (i in 0..1) {
                    c.drawText(text, i, i + 1, (X + i * DIGIT_W + DIGIT_W / 2f) * u, (top + DIGIT_H) * u, digits)
                }
            }
            if (part != Part.FRONT) line(words.hours, Y, place.hour)
            if (part != Part.BACK) {
                line(words.minutes, Y + LINE, place.minute)
                if (look.date) {
                    small.typeface = f[2]
                    small.textSize = DATE_SIZE * u
                    small.letterSpacing = 0.06f
                    small.color = place.minute
                    small.setShadowLayer(8f * u, 0f, 1f * u, SHADOW)
                    c.drawText(words.date, (X + DIGIT_W) * u, (Y + LINE + DIGIT_H + DATE_DROP) * u, small)
                }
            }
        }

        if (part != Part.BACK && look.battery && look.level >= 0) {
            // Small, bottom left of the picture, only when asked for.
            small.typeface = f[2]
            small.textSize = 24f * u
            small.letterSpacing = 0.04f
            small.color = if (look.charging) CHARGING else place.hour
            small.setShadowLayer(8f * u, 0f, 1f * u, SHADOW)
            small.textAlign = Paint.Align.LEFT
            c.drawText(words.battery, 316f * u, h - 34f * u, small)
            small.textAlign = Paint.Align.CENTER
        }
    }

    private const val SHADOW = 0x8C000000.toInt()
    private const val CHARGING = 0xFF5BE37A.toInt()
}
