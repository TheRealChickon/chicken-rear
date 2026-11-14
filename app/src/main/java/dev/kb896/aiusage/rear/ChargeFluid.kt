package dev.kb896.aiusage.rear

import android.graphics.Color

/**
 * The charging page's fluid colour by how charged the battery is: red when nearly empty, through
 * orange and a yellow green, to green when well charged. The two ends are Xiaomi's own rear
 * colours for "bad" and "good" (its call page's red and green); Xiaomi's rear charging animation's
 * own stops were not found in its package, so the two between are ours.
 */
object ChargeFluid {
    /** Level (0..1) to colour. */
    val STOPS: List<Pair<Float, Int>> = listOf(
        0.00f to 0xFFFA382E.toInt(),
        0.10f to 0xFFFA382E.toInt(),
        0.25f to 0xFFFF8A1F.toInt(),
        0.50f to 0xFFB6D634.toInt(),
        0.80f to 0xFF1DCD3A.toInt(),
        1.00f to 0xFF1DCD3A.toInt(),
    )

    fun color(level: Float): Int {
        val t = level.coerceIn(0f, 1f)
        val i = (1 until STOPS.size).firstOrNull { t <= STOPS[it].first } ?: (STOPS.size - 1)
        val (p0, c0) = STOPS[i - 1]; val (p1, c1) = STOPS[i]
        val k = if (p1 > p0) (t - p0) / (p1 - p0) else 0f
        fun mix(a: Int, b: Int) = Math.round(a + (b - a) * k)
        return Color.rgb(mix(Color.red(c0), Color.red(c1)), mix(Color.green(c0), Color.green(c1)), mix(Color.blue(c0), Color.blue(c1)))
    }
}
