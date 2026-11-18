package dev.kb896.aiusage.ui

import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.data.PinImage

/**
 * WHETHER THE FRONT SHOWS A PINNED PICTURE, and which (ui/PinViewActivity). Pure.
 *
 * WHAT COMES IN: the screen is exported (the back's shell starts it: rear/PictureTap.command) but only a holder of DUMP
 * may start it (the manifest: adb's shell has it, an app can only be given it from adb), and even then the intent says
 * ONE thing, the note's time ([at]: a number above 0). The picture is the note's own file, found from HIS list of notes
 * ([pin]: a note of that time with a picture of ours); a path, an address, a stream, a file name or any other extra in
 * the intent is never read. So whatever starts it can at most show one of his own pictures on his own screen, and learns
 * nothing back (no result, no reply).
 *
 * WHEN ([verdict]): never while a call is on (ringing, talking, or a call or chat app holding the sound for one: the
 * phone's audio mode is not normal: [inCall]); NEVER OVER THE LOCK SCREEN: locked, it asks him to unlock first and shows
 * the picture only once he has ([Verdict.UNLOCK_FIRST]); otherwise at once.
 */
object PinViewGate {
    /** The intent's one input (rear/PictureTap.EXTRA_AT). */
    const val EXTRA_AT = dev.kb896.aiusage.rear.PictureTap.EXTRA_AT

    enum class Verdict(val shows: Boolean, val why: String) {
        SHOW(true, "shown"),
        UNLOCK_FIRST(true, "the phone is locked — shown only once he unlocks"),
        NOT_ASKED(false, "no note's time came"),
        NO_PICTURE(false, "no note of his with a picture has that time"),
        IN_CALL(false, "a call is on — nothing shown"),
    }

    /** The note's time an intent's [EXTRA_AT] holds ([value]: what Intent.getLongExtra gave, 0 for none or not a number). */
    fun at(value: Long): Long = if (value > 0L) value else 0L

    /** His note pinned at [at] with a picture of ours, from [pins] (his own list); null for none. */
    fun pin(pins: List<Pin>, at: Long): Pin? = if (at <= 0L) null else pins.firstOrNull { it.at == at && PinImage.valid(it.image) }

    /** Whether the phone's audio mode ([mode], AudioManager.getMode) says a call is on: anything but normal (0). */
    fun inCall(mode: Int): Boolean = mode > 0

    fun verdict(at: Long, pin: Pin?, inCall: Boolean, locked: Boolean): Verdict = when {
        at <= 0L -> Verdict.NOT_ASKED
        pin == null -> Verdict.NO_PICTURE
        inCall -> Verdict.IN_CALL
        locked -> Verdict.UNLOCK_FIRST
        else -> Verdict.SHOW
    }

    /** Asked again as he unlocks: still his picture, still no call, and the lock really gone. */
    fun afterUnlock(pin: Pin?, inCall: Boolean, locked: Boolean): Boolean = pin != null && !inCall && !locked
}
