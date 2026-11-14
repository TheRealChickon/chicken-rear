package dev.kb896.aiusage.remote

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.data.ConfigWriter
import dev.kb896.aiusage.data.NoticeMatch
import dev.kb896.aiusage.rear.NoticeShade
import dev.kb896.aiusage.rear.RearActivity
import dev.kb896.aiusage.service.BackNotices
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.UsagePollService
import java.util.concurrent.Executors

/**
 * A command from outside, from the door it came through to the thing it does.
 *
 * Both doors (RemoteReceiver, RemoteActivity) hand over the same four things — the command's
 * name, its arguments as strings, the token, and the sender if the system named one — and get
 * back at once whether it was TAKEN. Doing it happens afterwards on one worker thread: the
 * bridge's calls block for seconds (a launch on the back waits for the app to settle), a
 * broadcast receiver has no seconds to give, and one thread keeps a burst in the order it came.
 *
 * NOTHING NEW IS DONE HERE. Every command ends in something the app already did for a finger:
 * the settings screen's write, the panel's own broadcasts, the bridge's calls. This is the
 * part that decides, not a second way of doing things — which is also what keeps it thin
 * enough to be trusted without a phone in hand.
 */
object RemoteDispatcher {

    private const val TAG = "AiUsageRemote"

    /** One gate for the process: its buckets and its lockout are the whole point of it. */
    private val gate = RemoteGate()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "remote").apply { isDaemon = true } }

    const val TAKEN = "ok"

    /**
     * [TAKEN], or why not. [via] is the door, for the log. Returns straight away; the work
     * follows on the worker.
     */
    fun submit(
        context: Context, cmd: String?, args: Map<String, String>, token: String?, sender: String?, via: String,
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): String {
        val app = context.applicationContext
        val store = RemoteStore(app)
        val parsed = RemoteParser.parse(cmd, args)
        // The gate is asked first whatever the command: a caller with no token learns nothing
        // about which commands exist, and its tries count whether or not they parse.
        val scope = (parsed as? RemoteParser.Result.Ok)?.command?.scope ?: RemoteScope.QUERY
        val verdict = gate.check(store.enabled, store.gateTokens(), token, sender, scope, nowMs)
        val who = when (verdict) {
            is RemoteGate.Verdict.Allowed -> verdict.token.name
            is RemoteGate.Verdict.Denied -> verdict.token?.name ?: via
        }
        if (verdict is RemoteGate.Verdict.Denied) {
            // Off, nothing is written at all: a disabled feature keeps no diary.
            if (verdict.reason != RemoteGate.OFF) store.record(who, (parsed as? RemoteParser.Result.Ok)?.command?.name ?: "?", "refused: ${verdict.reason}")
            Log.i(TAG, "refused ($via): ${verdict.reason}")
            return verdict.reason
        }
        if (parsed is RemoteParser.Result.Bad) {
            store.record(who, cmd?.take(24)?.filter { it.isLetterOrDigit() || it == '.' || it == '_' } ?: "?", "not understood: ${parsed.reason}")
            return parsed.reason
        }
        val command = (parsed as RemoteParser.Result.Ok).command
        worker.execute {
            val outcome = runCatching { run(app, command) }.getOrElse { e ->
                Log.w(TAG, "${command.name} failed", e)
                "failed: ${e.javaClass.simpleName}"
            }
            store.record(who, command.name, outcome)
            Log.i(TAG, "${command.name} ($via, $who): $outcome")
        }
        return TAKEN
    }

    /** Do it, and say how it went in a few words (for the log). On the worker thread. */
    private fun run(app: Context, c: RemoteCommand): String {
        val graph = Graph.of(app)
        val bridge = graph.bridge
        // Most of what follows is done by the service (its receiver is where the panel's own
        // buttons already send their asks). Started if it is not running: a command is as good
        // a reason as opening the app.
        runCatching { UsagePollService.start(app) }
        fun toService(action: String, extras: Intent.() -> Unit = {}) =
            app.sendBroadcast(Intent(action).setPackage(app.packageName).apply(extras))
        fun toPanel(op: String, arg: String = "") = bridge.tellPanel(RearActivity.ACTION_REMOTE) {
            putExtra(RearActivity.EXTRA_REMOTE_OP, op); putExtra(RearActivity.EXTRA_REMOTE_ARG, arg)
        }
        // The back's own lock where the shell has said; the phone's keyguard until it has.
        fun locked(): Boolean = bridge.backLocked
            ?: (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

        return when (c) {
            is RemoteCommand.Wake -> wake(app, c.guard)
            RemoteCommand.Sleep -> {
                if (ConfigStore(app).load().alwaysOn) {
                    // Always-on has no sleep of its own (it rests lit): the back is switched off
                    // as pocket mode switches it off when it gives up on a pocket — the pair,
                    // never pocketSleep alone, which would leave every later wake declined.
                    toPanel(RearActivity.REMOTE_LET_GO)
                    Thread.sleep(150)
                    val off = bridge.status().ready && bridge.awaitService() != null && bridge.pocketSleep()
                    bridge.pocketEnd(light = false)
                    if (off) "the back is off" else "could not switch the back off"
                } else {
                    toPanel(RearActivity.REMOTE_SLEEP)
                    "dimming to sleep"
                }
            }
            RemoteCommand.Lock -> { bridge.tellPanel(RearActivity.ACTION_LOCK); "slider up" }
            is RemoteCommand.AlwaysOn -> {
                val after = ConfigWriter.update(app) { it.copy(alwaysOn = c.to.applied(it.alwaysOn)) }.alwaysOn
                if (after && c.now) toPanel(RearActivity.REMOTE_AOD_NOW)
                "always-on ${if (after) "on" else "off"}"
            }
            is RemoteCommand.KeepAwake -> {
                val after = ConfigWriter.update(app) { it.copy(keepPanelAwake = c.to.applied(it.keepPanelAwake)) }.keepPanelAwake
                "keep awake ${if (after) "on" else "off"}"
            }
            is RemoteCommand.TableMode -> {
                val after = ConfigWriter.update(app) { it.copy(tableMode = c.to.applied(it.tableMode)) }.tableMode
                "table mode ${if (after) "on" else "off"}"
            }
            is RemoteCommand.AnimationPlay -> { toPanel(RearActivity.REMOTE_PLAY); "animation asked" }
            is RemoteCommand.Brightness -> {
                c.auto?.let { on -> toService(RearActivity.ACTION_SET_REAR_AUTO_BRIGHTNESS) { putExtra(RearActivity.EXTRA_AUTO_BRIGHTNESS, on) } }
                c.value?.let { v -> toService(RearActivity.ACTION_SET_REAR_BRIGHTNESS) { putExtra(RearActivity.EXTRA_BRIGHTNESS, v) } }
                "brightness asked"
            }
            is RemoteCommand.Page -> {
                if (c.wake) wake(app, guard = false)
                toPanel(RearActivity.REMOTE_PAGE, c.page.wire)
                "page ${c.page.wire}"
            }

            is RemoteCommand.AppOpen -> {
                val pinned = ConfigStore(app).load().portalApps
                when (val v = RemotePolicy.open(c.pkg, pinned, RemoteStore(app).rules, locked())) {
                    is RemotePolicy.Verdict.Denied -> "refused: ${v.reason}"
                    RemotePolicy.Verdict.Allowed -> if (bridge.launchGuest(c.pkg)) "opened on the back" else "could not open it"
                }
            }
            RemoteCommand.AppSendCurrent -> when (val v = RemotePolicy.guest("send", locked())) {
                is RemotePolicy.Verdict.Denied -> "refused: ${v.reason}"
                RemotePolicy.Verdict.Allowed -> if (bridge.sendFocusedToBack().isNotEmpty()) "sent to the back" else "nothing to send"
            }
            is RemoteCommand.AppAction -> when (val v = RemotePolicy.guest(c.which, locked())) {
                is RemotePolicy.Verdict.Denied -> "refused: ${v.reason}"
                RemotePolicy.Verdict.Allowed -> if (bridge.guestAction(c.which)) "asked" else "no shell to ask"
            }
            RemoteCommand.Camera ->
                if (locked()) "refused: ${RemotePolicy.LOCKED}" else { toService(RearActivity.ACTION_OPEN_CAMERA); "camera asked" }

            is RemoteCommand.CardShow -> card(app, c)
            is RemoteCommand.CardClear -> {
                val keys = BackNotices.keys().filter { if (c.id.isEmpty()) NoticeMatch.remote(it) else it == NoticeMatch.REMOTE + c.id }
                    .ifEmpty { if (c.id.isEmpty()) emptyList() else listOf(NoticeMatch.REMOTE + c.id) }
                for (key in keys) {
                    BackNotices.removed(app, key)
                    bridge.tellPanel(RearActivity.ACTION_NOTIFICATION_REMOVED) { putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key) }
                }
                "${keys.size} cleared"
            }
            is RemoteCommand.TileSet, is RemoteCommand.TileRemove,
            is RemoteCommand.RingSet, is RemoteCommand.RingClear,
            is RemoteCommand.SceneApply, is RemoteCommand.ConfigSet,
            is RemoteCommand.UsagePush,
            RemoteCommand.State -> "not in this build yet"

            is RemoteCommand.Media -> { toService(RearActivity.ACTION_MEDIA_COMMAND) { putExtra(RearActivity.EXTRA_MEDIA_COMMAND, c.action) }; c.action }
            RemoteCommand.Screenshot -> { toService(RearActivity.ACTION_CAPTURE) { putExtra(RearActivity.EXTRA_RECORD, false) }; "screenshot asked" }
            RemoteCommand.Record -> { toService(RearActivity.ACTION_CAPTURE) { putExtra(RearActivity.EXTRA_RECORD, true) }; "recording toggled" }
        }
    }

    /**
     * Light the back. A panel that is not there is put there, lit. [guard]: a back that was
     * dark comes up with its slider, as it does from a pocket — a wake nobody is holding the
     * phone for is exactly when a brush of a hand should not turn a page.
     */
    private fun wake(app: Context, guard: Boolean): String {
        val graph = Graph.of(app)
        val bridge = graph.bridge
        // Not ready is known at once, and waited for only when there is something to wait for:
        // without Shizuku running, six seconds of waiting would hold up every command behind this one.
        if (!bridge.status().ready || bridge.awaitService() == null) return "no shell: is Shizuku running?"
        if (bridge.isPocketed()) return "in a pocket: left dark"
        if (bridge.panelAlive() == false) {
            bridge.show(graph.payload(ConfigStore(app).load()), light = true)
            return "panel placed, lit"
        }
        val wasDark = bridge.wake("remote control", byHand = true)
        if (wasDark && guard) bridge.tellPanel(RearActivity.ACTION_POCKET) {
            putExtra(RearActivity.EXTRA_POCKETED, false)
            putExtra(RearActivity.EXTRA_POCKET_RESTORE, true)
            putExtra(RearActivity.EXTRA_POCKET_GUEST, bridge.guestOnTop() == true)
        }
        return if (wasDark) "woken" else "already lit"
    }

    /**
     * A card on the back, by the two doors a real notification takes (NotificationRelay.relay):
     * the shell's list, which is what pops the banner, and the panel's, which is the list that
     * is kept. One or the other alone is a banner with nothing behind it, or a card nobody saw.
     */
    private fun card(app: Context, c: RemoteCommand.CardShow): String {
        val bridge = Graph.of(app).bridge
        // The banner is the shell's, and it is told cards are wanted by the config (see
        // PanelConfig.remoteCards): said here rather than hoped, so the first card shows.
        ConfigWriter.update(app) { if (it.remoteCards) it else it.copy(remoteCards = true) }
        val key = NoticeMatch.REMOTE + c.id
        // The icon is an installed app's, by its package, or this app's own.
        val pkg = c.icon.takeIf { RemoteParser.pkg(it) != null && runCatching { app.packageManager.getApplicationInfo(it, 0) }.isSuccess }
            ?: app.packageName
        val name = c.app.ifBlank { runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("") }
        if (c.wake) {
            // As the relay wakes for a notification: lit, the slider up if it was dark, and a
            // moment for the display to be on before the panel judges the card.
            if (bridge.wake("a card from outside")) bridge.tellPanel(RearActivity.ACTION_LOCK)
            Thread.sleep(200)
        }
        BackNotices.posted(app, NoticeShade.Item(key, pkg, name, c.title, c.text))
        bridge.tellPanel(RearActivity.ACTION_NOTIFICATION) {
            putExtra(RearActivity.EXTRA_NOTIFICATION_APP, name)
            putExtra(RearActivity.EXTRA_NOTIFICATION_PKG, pkg)
            putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key)
            putExtra(RearActivity.EXTRA_NOTIFICATION_TITLE, c.title)
            putExtra(RearActivity.EXTRA_NOTIFICATION_TEXT, c.text)
        }
        return "card up"
    }
}
