package dev.kb896.aiusage.rear

/**
 * A debug build's made-up route, from the back's demo file (pop-demo, which only adb's shell and the app can write):
 * "nav|distance|instruction|eta|progress", and a sixth part for the line under it. The distance is set as Maps'
 * short critical text, the instruction as the title; progress is 0 to 1, or empty for no bar. Marked "Demo"; no
 * arrow or tracker of its own (there is no app's picture for them).
 */
object NavigationDemo {
    fun parse(parts: List<String>): NavFace.Model? {
        if (parts.size !in 5..6 || parts[0] != "nav") return null
        val value = parts[4].trim()
        val fraction = if (value.isEmpty()) null else value.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
        val sample = NavFace.demo(fraction = fraction)
        return sample.copy(
            short = parts[1].trim().take(200), title = parts[2].trim().take(500), text = parts.getOrNull(5)?.trim().orEmpty().take(500),
            eta = parts[3].trim().take(100), fraction = fraction,
            segments = if (fraction == null) emptyList() else sample.segments,
            progressMax = if (fraction == null) null else sample.progressMax,
        )
    }
}
