package dev.kb896.aiusage.service

import android.content.Context
import dev.kb896.aiusage.auth.OAuthClient
import dev.kb896.aiusage.auth.TokenStore
import dev.kb896.aiusage.data.ProfileApi
import dev.kb896.aiusage.data.ProfileStore
import dev.kb896.aiusage.data.UsageApi
import dev.kb896.aiusage.data.UsageHistory
import dev.kb896.aiusage.data.UsageRepository
import dev.kb896.aiusage.data.WeatherApi
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.StatePayload
import dev.kb896.aiusage.rear.DpiEdits
import dev.kb896.aiusage.rear.ShizukuBridge
import dev.kb896.aiusage.ui.PortalGuide
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Hand-rolled singleton graph — the app is far too small for a DI framework.
 *
 * Constructed from whatever Context first calls [of]; only `context.applicationContext`
 * is ever retained (by [store] and [bridge]), so it makes no difference whether the
 * first caller passes an Activity or the Service itself — nothing here can leak one.
 */
class Graph private constructor(context: Context) {
    private val http = OkHttpClient()
    val weatherApi = WeatherApi(http)
    val store = TokenStore(context.applicationContext)
    /** ChatGPT's sign-in (auth/ChatGptOAuth) and the id of the ChatGPT account it is for, kept beside the tokens. */
    val chatOauth = dev.kb896.aiusage.auth.ChatGptOAuth(http)
    private val chatPrefs = context.applicationContext.getSharedPreferences("chatgpt", Context.MODE_PRIVATE)
    var chatAccountId: String?
        get() = chatPrefs.getString("account_id", null)
        set(value) { chatPrefs.edit().apply { if (value == null) remove("account_id") else putString("account_id", value) }.apply() }
    private val oauth = OAuthClient(http)
    val repository = UsageRepository(
        UsageApi(http), oauth, store,
        ProfileApi(http), ProfileStore(context.applicationContext),
        UsageHistory(context.applicationContext),
        chatApi = dev.kb896.aiusage.data.ChatGptUsageApi(http), chatOauth = chatOauth,
        chatAccountId = { chatAccountId }, onChatAccountId = { chatAccountId = it },
    )
    val bridge = ShizukuBridge(context.applicationContext).also { b ->
        // The onboarding decides whether the panel may be placed at all (Setup.panelAllowed) —
        // in Replace mode only. In companion mode (the default) there is no panel and no gate:
        // Xiaomi's rear screen stays, and each feature checks only what it needs (Readiness).
        b.gate = { status ->
            ConfigStore(context.applicationContext).load().rearMode == dev.kb896.aiusage.data.RearMode.REPLACE &&
                dev.kb896.aiusage.ui.Setup.panelAllowed(context.applicationContext, status)
        }
        b.companion = { ConfigStore(context.applicationContext).load().companion }
        b.nightNow = { NightRule.now(context.applicationContext, ConfigStore(context.applicationContext).load()) }
        // An app's DPI set on the back: saved as the front's own rows save it, and the
        // payload pushed, so the shell sees its value come back (DpiEdits). For the guest
        // whichever way it got there, as "keep right of the cameras" below: it was saved for a
        // pinned app alone, so the DPI of an app sent by the tile was never saved — it held on
        // the back for the shell's ten seconds, then went back to the default, and the app
        // was restarted at it (the owner, 2026-11-10).
        b.onPortalDpi = { pkg, dpi ->
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            val next = config.copy(portalDpi = DpiEdits.applied(config.portalDpi, pkg, dpi))
            store.save(next)
            if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
        }
        // "Keep right of the cameras" switched on the back, for the guest whichever way it got
        // there: not only a pinned app's, or a guest sent by the tile would go back where it was
        // when the shell's hold on the switch ran out with no payload agreeing (ConfinedEdits).
        b.onPortalConfined = { pkg, on ->
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            val next = config.copy(portalConfined = if (on) config.portalConfined + pkg else config.portalConfined - pkg)
            store.save(next)
            if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
        }
        // An app's width set on the back: saved as its DPI is, pinned or not.
        b.onPinRemoved = { at ->
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            val next = config.copy(pins = config.pins.filter { it.at != at })
            store.save(next)
            PlaceNotes.forget(context.applicationContext, at)
            if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
        }
        b.onFaceChosen = { key, seq ->
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            // An older choice than the one kept (the settings' tap after it) is not taken back.
            if (seq > config.faceSeq || key != config.face && seq == 0L) {
                val next = config.copy(face = key, faceSeq = maxOf(seq, config.faceSeq))
                store.save(next)
                if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
            }
        }
        b.onFacesChanged = { for (l in b.facesListeners) runCatching { l() } }
        // The notes put in order on the back: kept on the list as it is now (a note pinned since stays in front).
        b.onPinsOrdered = { order ->
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            val next = config.copy(pins = dev.kb896.aiusage.data.PinOrder.applyPins(config.pins, order))
            if (next != config) store.save(next)
            if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
        }
        // "Not now" on a note standing at its place: it waits for his next visit (place/Places).
        b.onArrivalLater = { at -> dev.kb896.aiusage.place.Places.later(context.applicationContext, at) }
        b.onFacePicked = { file ->
            val store = ConfigStore(context.applicationContext)
            val next = store.load().copy(sceneLoop = file)
            store.save(next)
            if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
        }
        b.onPortalWidth = { pkg, width ->
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            val next = config.copy(portalWidth = if (width <= 0) config.portalWidth - pkg else config.portalWidth + (pkg to width))
            store.save(next)
            if (!b.calibrating) b.update(StatePayload.encode(repository.state.value, next, WeatherPoll.latest))
        }
        // The sheet opened on the back: the Portal tab's last step ticks. The front's alone —
        // nothing on the back reads it, so no payload goes.
        b.onPortalOpened = {
            val store = ConfigStore(context.applicationContext)
            val config = store.load()
            if (!config.portalSheetSeen) store.save(PortalGuide.settled(config.copy(portalSheetSeen = true)))
        }
    }

    private val main = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /**
     * The browser sign-ins, the app's and not a screen's (auth/SignIns says why): the listener the browser comes
     * back to, the exchange, the stored tokens, and what the settings row says of it. What it does is in the log
     * under [SIGN_IN_TAG], a line a step, never with a code or a token.
     */
    val signIns: dev.kb896.aiusage.auth.SignIns = run {
        val app = context.applicationContext
        val chatGpt = dev.kb896.aiusage.auth.SignIns.Flow(
            service = "ChatGPT",
            // The two ports OpenAI's side accepts for this client, and its path: not ours to change.
            ports = listOf(dev.kb896.aiusage.auth.ChatGptOAuth.PORT, dev.kb896.aiusage.auth.ChatGptOAuth.FALLBACK_PORT),
            redirect = { port -> dev.kb896.aiusage.auth.ChatGptOAuth.redirectUri(port) },
            stateMayGrow = true,
            authorizeUrl = { challenge, redirectUri -> chatOauth.authorizeUrl(challenge, redirectUri) },
            exchange = { code, challenge, redirectUri ->
                val signed = chatOauth.exchange(code, challenge.verifier, redirectUri)
                store.save(dev.kb896.aiusage.data.Account.CHATGPT, signed.record)
                chatAccountId = signed.accountId
            },
        )
        fun claude(account: dev.kb896.aiusage.data.Account) = dev.kb896.aiusage.auth.SignIns.Flow(
            service = "Claude",
            ports = listOf(CLAUDE_PORT),
            redirect = { port -> "http://localhost:$port/callback" },
            stateMayGrow = false,
            authorizeUrl = { challenge, redirectUri -> oauth.authorizeUrl(challenge, redirectUri) },
            exchange = { code, challenge, redirectUri -> store.save(account, oauth.exchange(code, challenge.verifier, redirectUri, challenge.state)) },
        )
        val prefs = app.getSharedPreferences("signin", Context.MODE_PRIVATE)
        dev.kb896.aiusage.auth.SignIns(
            flow = { account -> if (account == dev.kb896.aiusage.data.Account.CHATGPT) chatGpt else claude(account) },
            // Asked for while the settings are in front (his tap, or the back's button having just started them),
            // which is when Android lets an app start an activity; a new task, since this is not an activity.
            openBrowser = { url ->
                try {
                    app.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (_: android.content.ActivityNotFoundException) { false }
            },
            // Fresh figures at once, past the repository's floor between fetches: someone has just signed in and is looking.
            signedIn = {
                main.launch {
                    runCatching { repository.refresh(force = true) }
                    bridge.update(payload(ConfigStore(app).load()))
                }
            },
            notes = object : dev.kb896.aiusage.auth.SignIns.Notes {
                override fun get(key: String): String? = prefs.getString(key, null)
                override fun put(key: String, value: String?) = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            },
            appName = runCatching { app.applicationInfo.loadLabel(app.packageManager).toString() }.getOrDefault("Chicken Rear"),
            backLink = dev.kb896.aiusage.ui.SignInReturnActivity.link(app),
            log = { line -> android.util.Log.i(SIGN_IN_TAG, line) },
        )
    }

    /**
     * Everything the back is drawn from, as it stands: the usage this process holds, [config],
     * the last weather. One place builds it, so what is added to the payload reaches every push.
     */
    fun payload(config: dev.kb896.aiusage.data.PanelConfig): String =
        StatePayload.encode(repository.state.value, config, WeatherPoll.latest)

    companion object {
        /** `logcat -d -s AiUsageSignIn:I` is a sign-in from the tap to its end. */
        const val SIGN_IN_TAG = "AiUsageSignIn"
        /** Claude's redirect: `http://localhost:54545/callback`. */
        private const val CLAUDE_PORT = 54545
        @Volatile private var instance: Graph? = null
        fun of(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }
    }
}
