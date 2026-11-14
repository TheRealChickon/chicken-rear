package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.PanelConfig

/**
 * The back's usage page is THE ORIGINAL ONE: the readout this app was first made for (upstream
 * "Magic Rear", RearDialView's readout page: the masthead, each account's rows with their tracks
 * and figures, a mascot per account standing on its first bar, asleep, walking or working by what
 * the account is doing, and the rings round the lenses).
 *
 * It is drawn by that very view, off any window, onto the page's layer; nothing of it is redrawn
 * here. What IS set here is its look, where this fork's rules differ from upstream's defaults:
 * true black behind it (upstream: a purple live backdrop), no purple anywhere (upstream's stock
 * accents were purple; the headings and tracks are a neutral light now, the mascots Claude's own
 * colour and white, the bars' scale begins in grey where upstream's began in purple, the maker's
 * mark at the foot takes the headings' light), and no clock, date or battery on it.
 */
object OriginalUsage {
    /** Claude's own colour, for the first account's mascot; the second is white, as upstream's was. */
    const val CLAUDE = 0xFFD97757.toInt()
    private const val LIGHT = 0xFFF1EFEA.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * The bars' colours by how full they are. Upstream's scale began in purple at empty; here it
     * begins in a quiet light grey and keeps upstream's own yellow, orange and reds from there up.
     */
    val RAMP = listOf(0xFFB9B7B1.toInt(), 0xFFF0C674.toInt(), 0xFFDE935F.toInt(), 0xFFCC6666.toInt(), 0xFFBD2C40.toInt())

    /** Made-up usage for two accounts, for a look at the page with nobody signed in (debug, or the settings' demo mode). */
    fun demo(now: Long): dev.kb896.aiusage.data.DashboardState {
        val hour = 3_600_000L
        fun account(a: dev.kb896.aiusage.data.Account, s: Float, w: Float, o: Float, mascot: String) = dev.kb896.aiusage.data.AccountStatus(
            a, dev.kb896.aiusage.data.Health.OK,
            dev.kb896.aiusage.data.UsageSnapshot(listOf(
                dev.kb896.aiusage.data.UsageWindow(dev.kb896.aiusage.data.WindowKind.SESSION, "All 5h", s, now + 3 * hour),
                dev.kb896.aiusage.data.UsageWindow(dev.kb896.aiusage.data.WindowKind.WEEKLY_ALL, "All 7d", w, now + 60 * hour),
                dev.kb896.aiusage.data.UsageWindow(dev.kb896.aiusage.data.WindowKind.WEEKLY_SCOPED, "Fable 7d", o, now + 60 * hour),
            ), now),
            planTier = "MAX 5X", mascot = mascot,
        )
        return dev.kb896.aiusage.data.DashboardState(now, listOf(
            account(dev.kb896.aiusage.data.Account.PERSONAL, 0.42f, 0.67f, 0.18f, dev.kb896.aiusage.data.MascotMood.WALK),
            account(dev.kb896.aiusage.data.Account.WORK, 0.08f, 0.31f, 0.12f, dev.kb896.aiusage.data.MascotMood.SLEEP),
        ))
    }

    /** [config] as the usage page shows the readout: the accounts and their names kept, the look as above. */
    fun config(config: PanelConfig): PanelConfig = config.copy(
        background = 0xFF000000.toInt(),
        rampColors = RAMP,
        titleColor = LIGHT,
        showClock = false, showBattery = false, showDate = false,
        accounts = config.accounts.mapIndexed { i, a ->
            a.copy(accent = LIGHT, mascotBody = if (i == 0) CLAUDE else WHITE, mascotAccent = if (i == 0) WHITE else CLAUDE, showMascot = true)
        },
    )
}
