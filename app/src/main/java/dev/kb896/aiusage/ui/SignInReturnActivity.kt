package dev.kb896.aiusage.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The way back from the browser after a sign-in: the page that says "Signed in" (auth/SignInPage) has one button,
 * a link to [link], and his tap on it opens this.
 *
 * WHY A LINK AND NOT THE APP BRINGING ITSELF FORWARD. When the code has come the browser is in front, in a task of
 * its own, and this app has no window on screen: Android (10 and later) refuses an activity start from there, and
 * the app holds none of the exceptions it could count on (no "display over other apps", no activity in the task
 * in front). HyperOS has its own switch against it besides ("display pop-up windows while running in the
 * background"; known of the system, not read on this phone). A tap in the browser is the person's own act and is
 * allowed. NOT SEEN ON THE PHONE: whether HyperOS asks before the browser may open this app.
 *
 * NOTHING OF THE LINK IS READ. It shows nothing and only brings the settings forward (the one that is open, else a
 * new one), where the row says "Signed in" or what went wrong. Any page on the web can open this address; all it
 * can make happen is this app's settings coming to the front. In particular it does not pass on "sign_in"
 * (MainActivity.EXTRA_SIGN_IN), which a link could otherwise carry.
 */
class SignInReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }

    companion object {
        /** The address the browser's page links to: this build's package name as the scheme (the manifest's filter). */
        fun link(context: Context): String = "${context.packageName}://signin"
    }
}
