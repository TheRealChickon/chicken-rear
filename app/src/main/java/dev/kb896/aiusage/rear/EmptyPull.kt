package dev.kb896.aiusage.rear

/**
 * WHO HAS THE PULL WITH NOTHING WAITING (the rim's light, [EmptyRim]): the notifications' shell's decisions about it,
 * pure, kept under the shell's lock ([ShellNotices]). For the navigation the light is the shade up with nothing on it,
 * from the pull until it has gone.
 *
 * - A notification that comes while the light plays takes the shade back AT ONCE: under a finger still pulling, the
 *   pages come down with it; after the lift, the shade is shut, so that its pop-up may come (a pop-up never comes over
 *   the shade, and the light would have swallowed it).
 * - A pull while the light still runs is nobody's: a new play would cut the running light and the dim in one frame.
 *   Once the light is over (only what is under still coming back) a pull plays it again.
 * - The light gone by itself shuts the shade, unless a newer play is running.
 */
class EmptyPull {
    enum class Start {
        /** Nothing waiting: the rim lights up under the finger (again, once the last light is over). */
        RIM,
        /** Something waiting: the pages, as always. */
        PAGES,
        /** Something came while the light was up: the light stops and the pages take the shade it had. */
        PAGES_FROM_RIM,
        /** The light still runs: the pull is nobody's. */
        NOTHING,
    }

    enum class Arrival {
        NONE,
        /** The finger still pulls: the pages come down under it. */
        PAGES,
        /** After the lift: the light stops and the shade is shut. */
        SHUT,
    }

    /** The rim's light has the shade. */
    var up = false
        private set

    /** Its finger still pulls. */
    var pulling = false
        private set

    /**
     * A pull from the top edge's right half begins, with [count] waiting, [shadeUp] the shade up (pages or this light),
     * [lightOver] the light that runs is over (true when none runs).
     */
    fun start(count: Int, shadeUp: Boolean, lightOver: Boolean): Start = when {
        up && count > 0 -> { up = false; pulling = false; Start.PAGES_FROM_RIM }
        up && !lightOver -> Start.NOTHING
        up || (!shadeUp && count == 0) -> { up = true; pulling = true; Start.RIM }
        else -> Start.PAGES
    }

    /** The finger lifted (or its lift is taken as given). */
    fun lifted() { pulling = false }

    /** The list changed while the light may be up: [count] waiting now. */
    fun arrived(count: Int): Arrival {
        if (!up || count == 0) return Arrival.NONE
        val stillPulling = pulling
        up = false; pulling = false
        return if (stillPulling) Arrival.PAGES else Arrival.SHUT
    }

    /** The light has gone by itself; [playing]: a newer play runs. True: the shade is to be shut now. */
    fun ended(playing: Boolean): Boolean {
        if (!up || playing) return false
        up = false; pulling = false
        return true
    }

    /** Shut from outside (the back dark, a call, another app). */
    fun shut() { up = false; pulling = false }

    /** A debug build's play by the clock: up, no finger. */
    fun demo() { up = true; pulling = false }
}
