package dev.kb896.aiusage.service

import android.os.BatteryManager

/**
 * WHAT THE CHARGER IS DOING, DECIDED IN ONE PLACE, for the back's charging page:
 *  - [Is.FILLING]: the page, its figures running;
 *  - [Is.HOLDING]: on the charger but not charging (a charge limit below 100%, too warm): the page stays, its
 *    figures stand and its fluid rests (ChargePrecise.Detail.holding);
 *  - [Is.FULL]: 100%, or the phone says "full": THE PAGE DOES NOT STAY ON THE BACK, BUT IT HAS ONE SHORT MOMENT: as
 *    the battery reaches full on the charger, and as the charger goes in with the battery already full, the fluid
 *    stands at the top with "100", and then the page goes away as a page does (the shell: RearUserService.fullMoment;
 *    only on a lit back, never kept for later). It comes back to stay when the battery is under 100% again with the
 *    charger in;
 *  - [Is.OUT]: no charger.
 * "Full" is the phone's own word (BatteryManager's status FULL) or a level of 100, whichever comes first.
 */
object ChargeState {
    enum class Is { OUT, FILLING, HOLDING, FULL }

    fun of(plugged: Boolean, status: Int, percent: Int): Is = when {
        !plugged -> Is.OUT
        status == BatteryManager.BATTERY_STATUS_FULL || percent >= 100 -> Is.FULL
        status == BatteryManager.BATTERY_STATUS_CHARGING -> Is.FILLING
        else -> Is.HOLDING
    }

    /**
     * The charging page's place, from one battery reading to the next. AT 99 TO 100 THE LEVEL CAN WAVER (it did at
     * 92 to 93, four times in a minute): the page goes at full and comes back only at [SURE_PERCENT] or under, or
     * after the battery has been under 100% for [BACK_AFTER_MS], so it does not come and go with each flip.
     */
    class Page {
        enum class Does { NOTHING, SHOW, REST }

        /** The page has its place (the back was told so). */
        var on = false
            private set
        private var underSince = 0L

        enum class Plug { NOTHING, PAGE, MOMENT }

        /** The charger has just gone in: the page to stay (under 100%), its one short moment (full), or nothing. */
        fun pluggedIn(state: Is): Plug {
            underSince = 0L
            on = state == Is.FILLING || state == Is.HOLDING
            return when { on -> Plug.PAGE; state == Is.FULL -> Plug.MOMENT; else -> Plug.NOTHING }
        }

        /** The charger is out. */
        fun out() { on = false; underSince = 0L }

        /** A battery reading at [now] (uptime). */
        fun next(state: Is, percent: Int, now: Long): Does = when (state) {
            Is.OUT -> { out(); Does.NOTHING }
            Is.FULL -> { underSince = 0L; if (on) { on = false; Does.REST } else Does.NOTHING }
            Is.FILLING, Is.HOLDING -> when {
                on -> Does.NOTHING
                else -> {
                    if (underSince == 0L) underSince = now
                    if (percent <= SURE_PERCENT || now - underSince >= BACK_AFTER_MS) { on = true; underSince = 0L; Does.SHOW } else Does.NOTHING
                }
            }
        }

        companion object {
            const val SURE_PERCENT = 98
            const val BACK_AFTER_MS = 120_000L
        }
    }
}
