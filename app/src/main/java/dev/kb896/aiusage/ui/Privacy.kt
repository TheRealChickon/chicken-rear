package dev.kb896.aiusage.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * What this app tells the user about their data, in one place: the notice shown once, the
 * link to the policy, and the few sentences said before each settings page or grant the
 * app sends them to.
 *
 * In one file rather than beside each button because these are promises, not labels. A
 * sentence about what is sent where has to stay true as the code changes, and a promise
 * scattered over a 2,000-line activity is one nobody re-reads when the code under it moves.
 * Anything here must match what the code does — the album in the lyrics request was left
 * out of the note under the switch once, and that is exactly the kind of drift this guards.
 */
object Privacy {

    /**
     * What this fork does with data, in its README: the Advanced page's row and the notice. The
     * repository is private, so the page opens only for someone who has access to it; the notice
     * and the texts below say the same in the app itself.
     */
    const val POLICY_URL = "https://github.com/TheRealChickon/chicken-rear/blob/master/README.md#privacy"

    /**
     * The notice's version. Bump it when [NOTICE] changes in a way someone who has read the
     * old one should see: an update that brings a new one shows it once more, and an update
     * that leaves this alone does not. Stored as the last version seen, not a "seen" flag,
     * so the first notice and every later one are the same mechanism.
     */
    const val NOTICE_VERSION = 4

    /**
     * Short on purpose: what leaves the phone, what stays, and what is not there at all.
     * The detail is in the policy, one button away.
     */
    const val NOTICE =
        "What leaves your phone:\n" +
            "• Your Claude usage and plan, read from Anthropic (api.anthropic.com) with your own " +
            "account. You sign in in your browser on claude.ai; the token comes from " +
            "platform.claude.com.\n" +
            "• Only if you add it: ChatGPT's Codex limits, with your own account (sign-in at " +
            "auth.openai.com, the figures from chatgpt.com).\n" +
            "• Only with \"Lightning when it storms\" on: the phone's approximate position, " +
            "rounded to about a kilometre, to Open-Meteo for the weather.\n" +
            "• Only while \"Pin to location\" is open: the map of the area it shows, from " +
            "OpenFreeMap (tiles.openfreemap.org), and what you type in its search, with the map's " +
            "middle rounded to a tenth of a degree, to Photon (photon.komoot.io). Arrivals at a " +
            "note's place are watched by Google Play services on the phone.\n" +
            "• Only with Circle to Search on: text and image searches go to Google. A selected " +
            "image crop is kept in the app cache. Text recognition and translation run on the " +
            "phone with Google ML Kit. Translation can download language models, and Google " +
            "may collect SDK diagnostics.\n\n" +
            "What stays on it: your sign-in tokens, encrypted; your pinned notes, and the place of " +
            "a note you tie to one (for that the app asks for the precise position, all the time); " +
            "the last rough position, to a tenth of a degree, for the weather; the home Wi-Fi's " +
            "gateway and name servers, if you mark it. Notifications are shown on the back with " +
            "their message, which people facing you can read; they are never stored or sent. " +
            "\"Follows the music\" measures how loud the sound is and records nothing. With the " +
            "glass service on, a pin takes one picture of the screen, kept in memory for the " +
            "animation only. Captures of the rear screen are kept in memory only.\n\n" +
            "No ads or Chicken Rear analytics."

    /**
     * Under the two ways to connect an account (AccountSettingsSheet.chooseConnection),
     * which is the screen right before the browser opens claude.ai — so the note is there
     * rather than in one more dialog after the choice.
     */
    const val SIGN_IN =
        "Either way, the account's token is kept on this phone, encrypted, and used only to " +
            "read its usage and profile (plan, name, email) from Anthropic. The browser " +
            "sign-in happens on claude.ai, and the token is fetched from platform.claude.com."

    /**
     * Its own preferences file, not the panel config: "Reset every setting" puts the config
     * back to how it shipped, and having read the notice is not a setting.
     */
    private const val PREFS = "privacy"
    private const val KEY_SEEN = "notice_version_seen"

    /** Whether this install has not yet seen the notice as it now reads. */
    fun noticeDue(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEEN, 0) < NOTICE_VERSION

    /**
     * Shows the notice. Closing it any way at all — OK, Back, a tap beside it — counts as
     * seen and runs [onDone]: it is information, not consent to anything, and a notice that
     * cannot be closed would be one more dialog standing between someone and the setup it is
     * in front of. "Privacy policy" opens the page and leaves the notice where it is, so
     * coming back from the browser finds it still there to be closed.
     */
    fun showNotice(context: Context, onDone: () -> Unit): AlertDialog {
        val dialog = Modal.show(Modal.builder(context)
            .setTitle("Your privacy")
            .setMessage(NOTICE)
            .setNeutralButton("Privacy policy", null)   // rewired below so it does not close
            .setPositiveButton("OK", null))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { openPolicy(context) }
        dialog.setOnDismissListener {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SEEN, NOTICE_VERSION).apply()
            onDone()
        }
        return dialog
    }

    /** The policy in the browser — the only copy of it; the app carries none. */
    fun openPolicy(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(POLICY_URL))) }
            .onFailure { Toast.makeText(context, "Could not open $POLICY_URL", Toast.LENGTH_LONG).show() }
    }

    /**
     * What is said right before the app sends someone to a settings page or a grant: what
     * that access is for, and what does and does not leave the phone because of it.
     *
     * One or two sentences each, and exact. "Nothing is sent" appears only where nothing
     * is; where something is, it says what and to whom. The Shizuku text is the longest
     * because it is the widest grant — shell rights — and a list of what they are used for
     * is the only honest way to describe them.
     */
    enum class Access(val title: String, val text: String) {
        NOTIFICATIONS(
            "Allow notification access?",
            "With notification access the back shows other apps' notifications and the pages " +
                "for what is playing, calls and timers. A new message takes the whole back with " +
                "its app, sender and text (\"Show the message\" turns the text off), and the back " +
                "faces away from you, so people in front of you can read it. Throwing a page " +
                "away on the back clears that notification on the phone too. Notifications are " +
                "never stored or sent off the phone.",
        ),
        SHIZUKU(
            "About Shizuku",
            "Shizuku lets this app run a helper with Android's shell (adb) rights, which the " +
                "rear display needs. The helper draws the back's pages on layers over Xiaomi's " +
                "rear screen (which keeps running underneath), reads the rear touchscreen, the " +
                "motion sensor and the system log (which app is on the back), captures the rear " +
                "screen in memory for its cover, opens apps and Xiaomi's Camera on the back, " +
                "sends the phone's dialer and clock the commands Xiaomi's own rear pages send, " +
                "sets the rear's brightness, dims the front while the phone lies face down, and " +
                "grants this app notification access when you ask. It never captures the front " +
                "screen, saves no captures and makes no network connections.",
        ),
        BATTERY(
            "No battery restrictions",
            "Unrestricted, the service that keeps the panel current is not stopped in the " +
                "background. It checks your Claude usage every 2 minutes, and only while the " +
                "panel on the back is awake. This gives the app no access to anything else.",
        ),
        AUTOSTART(
            "Autostart",
            "Autostart lets HyperOS restart this app's service after a reboot, or after the " +
                "system has stopped it, so the panel comes back on its own. It gives the app " +
                "no access to anything else.",
        ),
        LOCATION(
            "Use the phone's location?",
            "For \"Lightning when it storms\", the phone's approximate position is read before " +
                "each update, every 5 minutes while the back is lit, and sent to Open-Meteo " +
                "rounded to about a kilometre; the last one is kept on the phone to a tenth of " +
                "a degree. A note tied to a place needs the precise position, all the time: its " +
                "place stays on the phone, and Android tells the app when you arrive there. " +
                "While you choose that place, its map and search fetch from OpenFreeMap and Photon.",
        ),
        TILE(
            "Add the \"Send to back\" tile?",
            "Tapped in Quick Settings, the tile moves the app on this screen to the back, " +
                "through Shizuku. It reads only which app is in front, and nothing leaves " +
                "the phone.",
        ),
    }

    /**
     * [access]'s disclosure; Continue runs [open], the page or grant itself. [lead] goes
     * before the text — which feature asked, when there is one — and [next] after it — what
     * the next screen is, when that is not obvious. Cancel, Back or a tap beside it run
     * [onCancel]: a switch that was flipped on to get here has to go back off when the
     * person decides not to go on, or it would read as on with nothing behind it.
     */
    fun disclose(
        context: Context,
        access: Access,
        lead: String? = null,
        next: String? = null,
        onCancel: () -> Unit = {},
        open: () -> Unit,
    ) {
        var went = false
        val text = listOfNotNull(lead, access.text, next).joinToString("\n\n")
        val dialog = Modal.show(Modal.builder(context)
            .setTitle(access.title)
            .setMessage(text)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> went = true; open() })
        // On dismiss rather than on the Cancel button alone, so Back and a tap outside
        // decline the same way Cancel does.
        dialog.setOnDismissListener { if (!went) onCancel() }
    }
}
