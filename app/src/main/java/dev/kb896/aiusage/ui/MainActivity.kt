package dev.kb896.aiusage.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.UsagePollService
import dev.kb896.aiusage.service.WeatherPoll
import kotlinx.coroutines.launch

/**
 * The app's one screen: the settings (CompanionScreen, whose page is ui/home/HomeScreen), with the
 * live preview of the back at its top. Everything else runs without a screen (the service, the
 * shell processes that draw the back).
 *
 * THERE IS NO OTHER UI. The old tabbed settings and the "Replace Xiaomi's rear screen" mode they belonged to
 * are gone: a config that still says Replace is read as companion (PanelConfig.decode), and
 * Xiaomi's rear app is put back by the service (UsagePollService).
 */
class MainActivity : ComponentActivity() {
    private val graph by lazy { Graph.of(this) }
    private var screen: CompanionScreen? = null
    private var privacyNotice: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light or dark, as the phone is, before any view takes a colour. A change of theme recreates this activity.
        dev.kb896.aiusage.ui.glass.Ink.follow(resources.configuration)
        super.onCreate(savedInstanceState)
        val made = CompanionScreen(this, graph) { recreate() }
        screen = made
        setContentView(made.root)
        // The one place a person starts the service from; starting it again when it runs does nothing.
        UsagePollService.start(this)
        if (Privacy.noticeDue(this)) privacyNotice = Privacy.showNotice(this) { privacyNotice = null }
        if (savedInstanceState == null) signInIfAsked()
    }

    override fun onPause() {
        screen?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        screen?.resume()
        dev.kb896.aiusage.place.PlaceWatch.look(this, "app resumed", foreground = true)
        // With this page in front the phone gives a position; in the background it gives none
        // (LastPlace). Taken here, kept roughly, for the weather behind the moon's clouds.
        lifecycleScope.launch {
            if (dev.kb896.aiusage.service.deviceFix(applicationContext)() != null) WeatherPoll.nudge()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        signInIfAsked()
    }

    /**
     * The back's usage page has one button when nobody is signed in; the shell starts this activity
     * with [EXTRA_SIGN_IN] for it. The first account without a sign-in gets the browser's page, once.
     */
    private fun signInIfAsked() {
        if (intent?.getBooleanExtra(EXTRA_SIGN_IN, false) != true) return
        intent.removeExtra(EXTRA_SIGN_IN)
        // The back's button is for Claude: ChatGPT is signed in to from the settings.
        val account = Account.entries.firstOrNull { it != Account.CHATGPT && runCatching { graph.store.load(it) == null }.getOrDefault(true) } ?: return
        // The sign-in is the app's, not this screen's (auth/SignIns): this activity is stopped as soon as the
        // browser is in front and may be gone when it comes back. The settings' row and its toast say how it ended.
        graph.signIns.start(account)
    }

    companion object {
        /** Started with this (by the shell, for the back's "Sign in" button): open the browser sign-in at once. */
        const val EXTRA_SIGN_IN = "sign_in"
    }
}
