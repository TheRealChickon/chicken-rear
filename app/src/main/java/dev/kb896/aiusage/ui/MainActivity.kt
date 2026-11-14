package dev.kb896.aiusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.auth.LoopbackServer
import dev.kb896.aiusage.auth.OAuthClient
import dev.kb896.aiusage.auth.PkceChallenge
import dev.kb896.aiusage.data.Account
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.UsagePollService
import dev.kb896.aiusage.service.WeatherPoll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
    // Apart from Graph's own client: signing in is this screen's business alone, one browser round trip.
    private val oauthClient by lazy { OAuthClient(OkHttpClient()) }
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
        signInFor(account) { failed -> toast(failed ?: "Signed in. Look at the back.") }
    }

    /** Browser sign-in for ONE account. [onDone] reports the outcome for display (null: it worked). */
    fun signInFor(account: Account, onDone: (String?) -> Unit) {
        if (account == Account.CHATGPT) return signInToChatGpt(onDone)
        lifecycleScope.launch {
            try {
                val loopback = LoopbackServer()
                val challenge = PkceChallenge.generate()
                val redirectUri = loopback.redirectUri()
                val authorizeUrl = oauthClient.authorizeUrl(challenge, redirectUri)
                val codeDeferred = async(Dispatchers.IO) { loopback.awaitCode(challenge.state) }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)))
                val code = codeDeferred.await()
                if (code == null) {
                    onDone("Sign-in failed or timed out")
                    return@launch
                }
                val record = withContext(Dispatchers.IO) {
                    oauthClient.exchange(code, challenge.verifier, redirectUri, challenge.state)
                }
                graph.store.save(account, record)
                onDone(null)
                refreshNow()
            } catch (e: OAuthClient.Failure) {
                onDone("Sign-in failed: ${e.message}")
            } catch (e: Exception) {
                onDone("Sign-in failed")
            }
        }
    }

    /**
     * ChatGPT's own sign-in page in the browser (auth/ChatGptOAuth: the Codex client's flow). The browser comes
     * back to a listener on this phone, on one of the two ports OpenAI's side accepts. What he types there is
     * between him and OpenAI's page: this app sees only the code that comes back, and keeps the tokens it buys
     * in its own encrypted store.
     */
    private fun signInToChatGpt(onDone: (String?) -> Unit) {
        lifecycleScope.launch {
            try {
                val oauth = graph.chatOauth
                val port = withContext(Dispatchers.IO) {
                    listOf(dev.kb896.aiusage.auth.ChatGptOAuth.PORT, dev.kb896.aiusage.auth.ChatGptOAuth.FALLBACK_PORT).firstOrNull { LoopbackServer.free(it) }
                } ?: return@launch onDone("Sign-in failed: the port it needs is in use on this phone")
                val redirectUri = dev.kb896.aiusage.auth.ChatGptOAuth.redirectUri(port)
                val loopback = LoopbackServer(port, redirectUri, stateMayGrow = true, doneText = "Signed in. You can go back to Chicken Rear.")
                val challenge = PkceChallenge.generate()
                val codeDeferred = async(Dispatchers.IO) { loopback.awaitCode(challenge.state) }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authorizeUrl(challenge, redirectUri))))
                val code = codeDeferred.await() ?: return@launch onDone("Sign-in failed or timed out")
                val signed = withContext(Dispatchers.IO) { oauth.exchange(code, challenge.verifier, redirectUri) }
                graph.store.save(Account.CHATGPT, signed.record)
                graph.chatAccountId = signed.accountId
                onDone(null)
                refreshNow()
            } catch (e: dev.kb896.aiusage.auth.ChatGptOAuth.Failure) {
                onDone("Sign-in failed: ${e.message}")
            } catch (e: Exception) {
                onDone("Sign-in failed")
            }
        }
    }

    /** Fresh figures at once, past the repository's floor between fetches: someone has just signed in and is looking. */
    private fun refreshNow() = lifecycleScope.launch {
        graph.repository.refresh(force = true)
        graph.bridge.update(StatePayload.encode(graph.repository.state.value, ConfigStore(this@MainActivity).load(), WeatherPoll.latest))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        /** Started with this (by the shell, for the back's "Sign in" button): open the browser sign-in at once. */
        const val EXTRA_SIGN_IN = "sign_in"
    }
}
