package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.PinImage

class PictureTap {
    /** What the back shows where a tap was made, as the shell knows it at that moment. */
    data class Under(
        /** The note showing on that page (the notes page's [RearNav.State.pin], an arrival's [RearNav.State.arrival]); null for none. */
        val note: Pin?,
        /** Where that note's picture is on the page (PinFace.pictureAt: left, top, right, bottom, rear pixels of 976x596); null: no picture. */
        val place: FloatArray?,
        /** The page is up. */
        val up: Boolean = true,
        /** A note turning to the next, or the notes being put in order (the carousel): no picture holds still to be aimed at. */
        val moving: Boolean = false,
        /** The back is lit (dim or bright). */
        val lit: Boolean = true,
        /** A call is on (its page, ringing or talking). */
        val inCall: Boolean = false,
        /** The notes showing are a debug build's made-up ones. */
        val madeUp: Boolean = false,
    )

    /** An open waiting for the double tap's window: the note pinned at [at], on page [on], tapped at [tappedAt] (uptime ms). */
    data class Pending(val at: Long, val on: SceneTouch.Mode, val tappedAt: Long)

    private var pending: Pending? = null

    /**
     * A tap (no bin's, no button's) lifted at [x], [y] (rear pixels of 976x596) on page [on], at [now], over [under]:
     * whether it is on the note's picture and so waits to open it ([due], after [WAIT_MS]). A tap that is not cancels
     * one waiting (a second tap far away is a tap of its own).
     */
    @Synchronized
    fun tapped(x: Float, y: Float, on: SceneTouch.Mode, under: Under, now: Long): Boolean {
        pending = null
        if (!opens(x, y, on, under)) return false
        pending = Pending(under.note!!.at, on, now)
        return true
    }

    /** A finger landed on the back: whatever it becomes, the tap waiting is not left alone, and opens nothing. */
    @Synchronized
    fun landed() { pending = null }

    /** Whether an open is waiting. */
    @Synchronized
    fun waiting(): Boolean = pending != null

    /**
     * The open to make now, once: the tap waiting, [WAIT_MS] after it (no finger has landed since: [landed]), with
     * [underNow] (what that page shows now) still the same note's picture, nothing moving, lit, no call. Null before its
     * time (it stays waiting), and null for good otherwise.
     */
    @Synchronized
    fun due(now: Long, underNow: (SceneTouch.Mode) -> Under): Pending? {
        val p = pending ?: return null
        if (now - p.tappedAt < SceneTouch.DOUBLE_MS) return null
        pending = null
        val u = underNow(p.on)
        return if (u.note?.at == p.at && still(u)) p else null
    }

    companion object {
        /** After its lift a tap on a picture waits this long for a second one: the double tap's window and a little for the reader. */
        const val WAIT_MS = SceneTouch.DOUBLE_MS + 40L
        /** A tap this far outside the picture (rear pixels) is still on it: the back is tapped by a fingertip, not a pointer. */
        const val PAD = 12f

        /** The screen on the front that shows a pinned picture: exported, the shell's alone to start (ui/PinViewActivity). */
        const val VIEWER = "dev.kb896.aiusage.ui.PinViewActivity"
        /** Its one input: the note's time (Pin.at), a number. */
        const val EXTRA_AT = "at"

        /** Whether a tap at [x], [y] on page [on] over [under] is one that opens the note's picture. */
        fun opens(x: Float, y: Float, on: SceneTouch.Mode, under: Under): Boolean {
            if (on != SceneTouch.Mode.NOTES && on != SceneTouch.Mode.ARRIVAL) return false
            val note = under.note ?: return false
            if (!PinImage.valid(note.image) || !still(under)) return false
            return onPicture(under.place, x, y)
        }

        /** Nothing in the way of an open: the page up, holding still, the back lit, no call, his own notes. */
        private fun still(u: Under) = u.up && !u.moving && u.lit && !u.inCall && !u.madeUp

        /** Whether [x], [y] is on [place] (left, top, right, bottom), [PAD] round it counted in. */
        fun onPicture(place: FloatArray?, x: Float, y: Float): Boolean =
            place != null && place.size == 4 && x >= place[0] - PAD && x <= place[2] + PAD && y >= place[1] - PAD && y <= place[3] + PAD

        /**
         * The shell's command that opens the picture of the note pinned at [at] on the front ([appId]'s [VIEWER], display
         * 0, its own window animation off: it draws its own). Only a number goes in: the screen finds the note itself.
         */
        fun command(appId: String, at: Long): String = "am start --display 0 --activity-no-animation -n $appId/$VIEWER --el $EXTRA_AT $at"
    }
}
