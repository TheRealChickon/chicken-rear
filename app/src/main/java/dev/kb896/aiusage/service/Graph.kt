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
    val repository = UsageRepository(
        UsageApi(http), OAuthClient(http), store,
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

    /**
     * Everything the back is drawn from, as it stands: the usage this process holds, [config],
     * the last weather. One place builds it, so what is added to the payload reaches every push.
     */
    fun payload(config: dev.kb896.aiusage.data.PanelConfig): String =
        StatePayload.encode(repository.state.value, config, WeatherPoll.latest)

    companion object {
        @Volatile private var instance: Graph? = null
        fun of(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context).also { instance = it } }
    }
}
