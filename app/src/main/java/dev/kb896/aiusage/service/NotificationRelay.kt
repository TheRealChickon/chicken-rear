package dev.kb896.aiusage.service

import android.content.Intent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.rear.RearActivity

/**
 * Passes other apps' notifications to the rear panel.
 *
 * This is the same mechanism KDE Connect uses to mirror notifications to a desktop, and
 * the same one the phone's other rear-screen tools use — both were already holding
 * notification access on this device when the feature was asked for. It needs the user
 * to grant it in Settings, which cannot be done from code and is the whole cost of the
 * feature.
 *
 * What crosses to the panel is deliberately small: the app's label, a title and one line
 * of text. Not the icon, not the actions, not the extras bundle. The panel draws a strip
 * of pixels on the back of a phone — it can hold a sentence, and anything more would be
 * collected for nothing.
 *
 * PRIVACY, and it is the reason the text is behind its own switch (two, locked): this
 * display faces AWAY from its owner. Everything it shows is shown to whoever is behind
 * them and not to them. A message that would be private on the front screen is on
 * display here, so the content line is a separate opt-in from the feature itself.
 */
class NotificationRelay : NotificationListenerService() {

    // What RelayWatch reads to know whether the relay needs binding again.
    override fun onListenerConnected() {
        RelayWatch.connected = true
        androidx.core.content.ContextCompat.registerReceiver(
            this, matching,
            android.content.IntentFilter().apply {
                addAction(RearActivity.ACTION_DISMISS_NOTIFICATION)
                addAction(RearActivity.ACTION_NOTIFICATIONS_SYNC)
                addAction(RearActivity.ACTION_NOTIFICATION_ACT)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        matchingRegistered = true
        // Debug builds only: `adb shell am broadcast -a dev.kb896.aiusage.DEBUG_UNBIND -p <pkg>`
        // drops the binding with access still granted — the state HyperOS left the release
        // in — so RelayWatch's rebind can be watched doing its job.
        if (dev.kb896.aiusage.BuildConfig.DEBUG && !debugRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(
                this, debugUnbind, android.content.IntentFilter(DEBUG_UNBIND),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            debugRegistered = true
        }
        // Whatever left the front while the relay was not bound, the back finds out now.
        sendActive()
        // And what is on the front already goes into the back's list: pulled down, it is current
        // from the first moment, not only what arrives from now on.
        seedActive()
        // And a timer or a call that is ALREADY GOING is the back's live page from now, not from its next update:
        // Xiaomi's Clock posts its rear note once and lets the page count by itself, so after this app had been
        // restarted (an update, a crash) a running timer was never shown again (found with a six-hour timer running
        // through four installs).
        seedLive()
        // And whatever is on the front already has been seen: an alert-once update of it is
        // not news, however long ago the relay last heard of it. See NotificationAlert.
        runCatching { activeNotifications }.getOrNull()?.let { all -> seen.seed(all.map { it.key }) }
    }

    /** Every key posted, ongoing or not — see NotificationAlert.SeenKeys. */
    private val seen = NotificationAlert.SeenKeys()

    private var debugRegistered = false
    private val debugUnbind = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            Log.i(TAG, "debug: unbinding the relay, access left granted")
            requestUnbind()
        }
    }

    override fun onListenerDisconnected() {
        RelayWatch.connected = false
        if (matchingRegistered) runCatching { unregisterReceiver(matching) }
        matchingRegistered = false
        callHandler.removeCallbacks(callAgain); callHold.drop()
    }

    // ---- what is going on now, as a page of its own on the back: a call (rear/CallFace), a timer or stopwatch (rear/TimerFace) ----

    /** The key of the notification the live card is showing; null for none. */
    private var liveKey: String? = null

    // A call whose notification has gone is held until the phone says the call is over (CallHold): answering takes
    // the "incoming" notification down and posts the "going on" one a moment later, and the page must not leave between.
    private val callHold = CallHold()
    /** What the back was last told about the call that has it. */
    private var lastCall: android.os.Bundle? = null
    private val callHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val callAgain = Runnable { callHeld(callHold.look(android.os.SystemClock.uptimeMillis(), audioMode())) }
    private fun audioMode() = runCatching { getSystemService(android.media.AudioManager::class.java).mode }.getOrDefault(CallHold.MODE_NORMAL)

    private fun callHeld(v: CallHold.Verdict) {
        callHandler.removeCallbacks(callAgain)
        when (v) {
            is CallHold.Verdict.Wait -> {
                val was = lastCall
                if (v.talking && was != null && !was.getBoolean("running")) {
                    // Answered, and its own "going on" notification not there yet: the page is the answered one from now.
                    Log.i(TAG, "live: the call was answered (the phone is in a call), its notification still to come")
                    val b = android.os.Bundle(was)
                    b.putBoolean("running", true); b.putLong("base", System.currentTimeMillis())
                    lastCall = b
                    runCatching { Graph.of(this).bridge.setLive(b) }
                }
                callHandler.postDelayed(callAgain, v.againMs)
            }
            is CallHold.Verdict.Clear -> {
                Log.i(TAG, "live: the call is over (${if (v.sure) "the phone says so" else "no notification of it came back"})")
                lastCall = null
                watchRoute(false)
                val next = runCatching { activeNotifications }.getOrNull()?.firstOrNull { isLive(it) }
                if (next != null) publishLive(next)
                else Graph.of(this).bridge.setLive(android.os.Bundle().apply { if (v.sure) putBoolean("sure", true) })
            }
        }
    }

    private fun liveButtons(n: android.app.Notification): List<android.app.Notification.Action> =
        n.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(3)

    /**
     * Whether [sbn] is something for the live card: a call (its category says so, whichever app
     * carries it), or the Clock's ongoing notification (a timer or a stopwatch; Xiaomi's Clock or
     * Google's).
     */
    private fun isLive(sbn: StatusBarNotification): Boolean =
        // A call IN PROGRESS: ongoing, not to be swiped away, or ringing over the whole screen. A notification that
        // merely has the category (a missed call, as some dialers post it) is not one, and no call page is for it.
        (sbn.notification.category == android.app.Notification.CATEGORY_CALL && (sbn.isOngoing || !sbn.isClearable || sbn.notification.fullScreenIntent != null)) ||
            // (An alarm of the Clock, ringing or snoozed or upcoming, is ongoing too and is no timer: the review's 28.)
            (sbn.packageName in CLOCKS && !LivePick.clockAlarm(sbn.notification.channelId) && (sbn.isOngoing || sbn.notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER) || rearTimer(sbn) != null))

    /**
     * What Xiaomi's Clock tells Xiaomi's own rear screen about a timer: it posts a second, silent
     * notification for it whose extra "miui.rear.param" is a JSON with "maml_config" in it (when it
     * ends, how long it is in all, running or paused or over). The back's timer page takes exactly that.
     */
    private fun rearTimer(sbn: StatusBarNotification): kotlinx.serialization.json.JsonObject? = runCatching {
        val raw = sbn.notification.extras.getString("miui.rear.param") ?: return null
        val root = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        (root?.get("maml_config") as? kotlinx.serialization.json.JsonObject)?.takeIf { it.containsKey("timer_when") || it.containsKey("timer_type") }
    }.getOrNull()

    /** [sbn] to the live card, as it is now. */
    private fun publishLive(sbn: StatusBarNotification) {
        val n = sbn.notification
        val x = n.extras
        val b = android.os.Bundle()
        b.putString("key", sbn.key); b.putString("pkg", sbn.packageName)
        b.putString("title", x.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { appLabel(sbn.packageName) })
        b.putString("text", x.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty())
        val clock = x.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0L
        var running = clock
        var base = n.`when`
        if (n.category == android.app.Notification.CATEGORY_CALL) {
            // ANSWERED OR NOT IS THE PHONE'S TO SAY (LivePick.callTalking): the dialer keeps several notifications up
            // through a call, and its note for the rear screen has no running clock on it.
            val mode = audioMode()
            running = LivePick.callTalking(mode, clock)
            if (running && callAnsweredAt == 0L) { callAnsweredAt = System.currentTimeMillis(); Log.i(TAG, "live: the call is answered (the phone's audio mode $mode${if (clock) ", and its notification's clock runs" else "; this notification has no running clock"})") }
            if (!running) { if (callAnsweredAt != 0L || callSaid != sbn.key) Log.i(TAG, "live: a call rings (the phone's audio mode $mode)"); callAnsweredAt = 0L }
            callSaid = sbn.key
            // Since when: the notification's own clock where it has one, else the moment the phone went into the call.
            if (running && !clock) base = callAnsweredAt
            if (dev.kb896.aiusage.BuildConfig.DEBUG && callKeysSaid.add(sbn.key)) {
                // What this notification carries, BY NAME ONLY (never a value: a caller's name and number are in there),
                // to learn what the dialer's rear-screen note says of the call's state.
                Log.i(TAG, "live: call notification id ${sbn.id} tag ${sbn.tag} channel ${n.channelId} ongoing ${sbn.isOngoing} clock $clock; extras named: ${x.keySet().sorted().joinToString(" ").take(700)}")
            }
        }
        b.putBoolean("running", running); b.putLong("base", base)
        b.putBoolean("down", x.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        b.putStringArray("actions", liveButtons(n).map { it.title.toString().trim() }.toTypedArray())
        BackNotices.iconBytes(this, sbn.packageName)?.let { b.putByteArray("icon", it) }
        // A call is a page of its own on the back (rear/CallFace), which shows where the sound goes.
        val call = n.category == android.app.Notification.CATEGORY_CALL
        b.putBoolean("call", call)
        if (call) {
            val audio = getSystemService(android.media.AudioManager::class.java)
            b.putBoolean("speaker", runCatching { audio.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.getOrDefault(false))
            b.putBoolean("headset", runCatching { audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS } }.getOrDefault(false))
            watchRoute(true)
            callHandler.removeCallbacks(callAgain); callHold.seen(audioMode())
            lastCall = android.os.Bundle(b)
        } else { callHandler.removeCallbacks(callAgain); callHold.drop(); lastCall = null }
        if (!call) {
            // A timer or a stopwatch (rear/TimerFace). Xiaomi's Clock says it all in its rear note; any other clock's
            // ongoing notification gives when it ends (or began, counting up) and whether it runs.
            fun kotlinx.serialization.json.JsonObject.text(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val rear = rearTimer(sbn) ?: runCatching { activeNotifications?.filter { it.packageName == sbn.packageName }?.firstNotNullOfOrNull { rearTimer(it) } }.getOrNull()
            if (rear != null) {
                val end = rear.text("timer_when")?.toLongOrNull() ?: 0L
                val paused = rear.text("maml_type") == "-2"
                b.putLong("end", end)
                b.putLong("left", (end - (rear.text("timer_system_current")?.toLongOrNull() ?: System.currentTimeMillis())).coerceAtLeast(0L))
                b.putLong("total", rear.text("timer_total")?.toLongOrNull() ?: 0L)
                b.putBoolean("paused", paused); b.putBoolean("over", rear.text("timer_type") == "1"); b.putBoolean("up", false)
                rear.text("timer_alert_title")?.takeIf { it.isNotBlank() }?.let { b.putString("title", it) }
            } else {
                val down = x.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
                b.putLong("end", n.`when`); b.putLong("left", 0L); b.putLong("total", 0L)
                b.putBoolean("paused", !running); b.putBoolean("over", false); b.putBoolean("up", !down)
            }
        }
        liveKey = sbn.key
        Graph.of(this).bridge.setLive(b)
    }

    /** The call's own notification among [sbn]'s app's notifications of a call (null where [sbn] is no call's). */
    private fun callOwn(sbn: StatusBarNotification): StatusBarNotification? {
        if (sbn.notification.category != android.app.Notification.CATEGORY_CALL) return null
        val all = (runCatching { activeNotifications }.getOrNull()?.filter { it.packageName == sbn.packageName && it.notification.category == android.app.Notification.CATEGORY_CALL && isLive(it) }.orEmpty() + sbn).distinctBy { it.key }
        val best = LivePick.callNote(all.map { LivePick.CallNote(it.key, it.notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER) && it.notification.`when` > 0L, it.isOngoing) })
        return all.firstOrNull { it.key == best }
    }

    /** When the phone went into the call that has the back (wall clock); 0 while it rings or there is none. */
    private var callAnsweredAt = 0L
    private var callSaid: String? = null
    private val callKeysSaid = HashSet<String>()
    /** The phone's audio mode changed while a call has the back (he answered: ringing to in a call): the page is told at once, whatever the notifications do. */
    private val modeListener = android.media.AudioManager.OnModeChangedListener { _ ->
        val key = liveKey ?: return@OnModeChangedListener
        runCatching { activeNotifications?.firstOrNull { it.key == key }?.let { publishLive(it) } }
    }

    /** While a call is on the back: where its sound goes is told again whenever that changes. */
    private var routeWatched = false
    private val routeListener = android.media.AudioManager.OnCommunicationDeviceChangedListener { _ ->
        val key = liveKey ?: return@OnCommunicationDeviceChangedListener
        runCatching { activeNotifications?.firstOrNull { it.key == key }?.let { publishLive(it) } }
    }

    private fun watchRoute(on: Boolean) {
        if (on == routeWatched) return
        routeWatched = on
        runCatching {
            val audio = getSystemService(android.media.AudioManager::class.java)
            if (on) audio.addOnCommunicationDeviceChangedListener(mainExecutor, routeListener) else audio.removeOnCommunicationDeviceChangedListener(routeListener)
            if (on) audio.addOnModeChangedListener(mainExecutor, modeListener) else { audio.removeOnModeChangedListener(modeListener); callAnsweredAt = 0L; callSaid = null; callKeysSaid.clear() }
        }
    }

    /** What is live on the front already, as the relay connects: a call before a timer, the Clock's rear note before its other one. */
    private fun seedLive() {
        val active = runCatching { activeNotifications }.getOrNull()?.filter { runCatching { isLive(it) }.getOrDefault(false) }.orEmpty()
        val pick = LivePick.first(active.map { LivePick.Candidate(it.key, it.notification.category == android.app.Notification.CATEGORY_CALL, rearTimer(it) != null) })
        val sbn = active.firstOrNull { it.key == pick }
        if (sbn == null) {
            // Nothing is live: SAID, so a page of something that ended while nobody was listening does not stay on the back.
            liveKey = null
            watchRoute(false)
            runCatching { Graph.of(this).bridge.setLive(android.os.Bundle()) }
            return
        }
        Log.i(TAG, "live: already going as the relay connects (${sbn.packageName})")
        runCatching { publishLive(sbn) }
    }

    private fun clearLive(key: String) {
        if (liveKey != key) return
        liveKey = null
        // A CALL'S notification gone: not its end until the phone says so (he answers: one notification down, the next
        // a moment later). Held, and what else is going (a timer) has the back when the call is over: callHeld.
        val wasCall = lastCall?.takeIf { it.getString("key") == key }
        // Another notification of the same call is still up (the dialer's own for the call going on): that one has the page.
        if (wasCall != null) runCatching { activeNotifications }.getOrNull()?.filter { it.key != key && it.packageName == wasCall.getString("pkg") && it.notification.category == android.app.Notification.CATEGORY_CALL && isLive(it) }
            ?.takeIf { it.isNotEmpty() }?.let { rest ->
                val best = LivePick.callNote(rest.map { LivePick.CallNote(it.key, it.notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER) && it.notification.`when` > 0L, it.isOngoing) })
                publishLive(rest.firstOrNull { it.key == best } ?: rest.first())
                return
            }
        if (wasCall != null) {
            callHeld(callHold.gone(android.os.SystemClock.uptimeMillis(), key, ringing = !wasCall.getBoolean("running"), mode = audioMode()))
            return
        }
        watchRoute(false)
        // Another one still going takes its place.
        val next = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key != key && isLive(it) }
        if (next != null) publishLive(next) else Graph.of(this).bridge.setLive(android.os.Bundle())
    }

    // ---- who a message is from, what it says, and its app's colour (the pop-up's: NoticePop) ----

    /**
     * For a messaging notification: the sender of its latest message and that message (its own
     * record of them, Notification.MessagingStyle's), with the group's name after the sender in a
     * group. For any other: its title and text as they are.
     */
    private fun whoAndWhat(extras: android.os.Bundle, title: String, text: String): Pair<String, String> {
        val last = runCatching { extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle }.getOrNull()
            ?: return title to text
        val what = last.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() } ?: text
        @Suppress("DEPRECATION")
        val person = runCatching { (last.getParcelable<android.os.Parcelable>("sender_person") as? android.app.Person)?.name?.toString() }.getOrNull()
        val sender = person?.takeIf { it.isNotBlank() } ?: last.getCharSequence("sender")?.toString()?.takeIf { it.isNotBlank() }
        val group = extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val who = when {
            sender == null -> title
            group != null && group != sender && extras.getBoolean(android.app.Notification.EXTRA_IS_GROUP_CONVERSATION, false) -> "$sender · $group"
            else -> sender
        }
        return who to what
    }

    /** The app's colour for this notification: the one it gave the notification, else its icon's. */
    private fun colorOf(sbn: StatusBarNotification): Int {
        // This app's own (the settings' test): no colour, so the quiet grey. Its icon is purple, and nothing on the
        // back is to be.
        if (sbn.packageName == packageName) return 0
        val own = sbn.notification.color
        return if (own != 0 && android.graphics.Color.alpha(own) != 0) own or (0xFF shl 24) else BackNotices.iconColor(this, sbn.packageName)
    }

    // ---- a notification's buttons, on the back ----

    /**
     * The buttons of [n] the back can offer: the ones that need nothing typed (a reply wants a
     * keyboard, and the back has none), the first [NoticeShade.ACTIONS] of them. The same list,
     * in the same order, names them to the back ([actionWords]) and presses them ([act]).
     */
    private fun pressable(n: android.app.Notification): List<android.app.Notification.Action> =
        n.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(dev.kb896.aiusage.rear.NoticeShade.ACTIONS)

    private fun actionWords(n: android.app.Notification): List<String> = pressable(n).map { it.title.toString().trim() }

    /** Button [index] of the notification of [key], pressed as its own shade would press it. */
    private fun act(key: String, index: Int, live: Boolean = false) {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key }
        val action = sbn?.let { (if (live) liveButtons(it.notification) else pressable(it.notification)).getOrNull(index) }
        if (action == null) { Log.i(TAG, "a button on the back: no such notification or button any more"); return }
        runCatching { action.actionIntent.send() }
            .onSuccess { Log.i(TAG, "a button on the back pressed: ${sbn.packageName}") }
            .onFailure { Log.w(TAG, "a button on the back: ${it.message}") }
    }

    // ---- matching the front (PanelConfig.matchFrontNotifications) ----

    private var matchingRegistered = false

    private val matching = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            // A button pressed on the back is pressed, whatever is kept the same between the two lists.
            if (intent.action == RearActivity.ACTION_NOTIFICATION_ACT) {
                act(
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_KEY).orEmpty(), intent.getIntExtra(RearActivity.EXTRA_NOTIFICATION_ACTION, -1),
                    live = intent.getBooleanExtra(RearActivity.EXTRA_NOTIFICATION_LIVE, false),
                )
                return
            }
            val c = ConfigStore(this@NotificationRelay).load()
            if (!c.matchFrontNotifications && !(c.companion && intent.action == RearActivity.ACTION_DISMISS_NOTIFICATION)) return
            when (intent.action) {
                // Swiped away on the back: cleared on the front.
                RearActivity.ACTION_DISMISS_NOTIFICATION ->
                    intent.getStringExtra(RearActivity.EXTRA_NOTIFICATION_KEY)?.takeIf { it.isNotEmpty() }?.let { key ->
                        runCatching { cancelNotification(key) }.onFailure { Log.w(TAG, "dismiss failed: ${it.message}") }
                        BackNotices.removed(this@NotificationRelay, key)
                    }
                RearActivity.ACTION_NOTIFICATIONS_SYNC -> sendActive()
            }
        }
    }

    /**
     * Companion mode: the notifications on the front right now, into the back's list (no banner
     * for them, they are not news). The same ones a post would let through: not this app's own,
     * not ongoing or uncleared ones, not group summaries, not services and players.
     */
    /**
     * Debug builds: a test notification posted from adb (`cmd notification post … cr_<name> …`, the
     * shell's own, which adb cannot cancel) is taken out of the shade again after a while, so tests
     * leave nothing in it.
     */
    private fun sweepTests() {
        if (!dev.kb896.aiusage.BuildConfig.DEBUG) return
        runCatching {
            activeNotifications?.filter { it.packageName == "com.android.shell" && it.tag?.startsWith("cr_") == true && System.currentTimeMillis() - it.postTime > 20_000L }
                ?.forEach { cancelNotification(it.key) }
        }
    }

    private fun seedActive() {
        sweepTests()
        val config = ConfigStore(this).load()
        if (!config.companion || !config.showNotifications) return
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val locked = phoneLocked()
        val showText = NoticeFilter.textShown(locked, config)
        val items = active.sortedByDescending { it.postTime }.mapNotNull { sbn ->
            val isTest = TestNotice.isTest(sbn.packageName, packageName, sbn.tag)
            if (sbn.packageName == packageName && !isTest) return@mapNotNull null
            if (!NoticeFilter.appAllowed(sbn.packageName, config, alwaysPass = isTest)) return@mapNotNull null
            if (sbn.isOngoing || !sbn.isClearable) return@mapNotNull null
            if (NoticeFilter.secret(locked, sbn.notification.visibility)) return@mapNotNull null
            if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return@mapNotNull null
            when (sbn.notification.category) {
                android.app.Notification.CATEGORY_SERVICE, android.app.Notification.CATEGORY_PROGRESS,
                android.app.Notification.CATEGORY_TRANSPORT -> return@mapNotNull null
            }
            val extras = sbn.notification.extras
            if (extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) return@mapNotNull null
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            if (title.isNullOrBlank() && text.isNullOrBlank()) return@mapNotNull null
            val (who, what) = whoAndWhat(extras, title.orEmpty(), text.orEmpty())
            dev.kb896.aiusage.rear.NoticeShade.Item(
                sbn.key, sbn.packageName, appLabel(sbn.packageName), who, if (showText) what else "",
                time = sbn.postTime, actions = actionWords(sbn.notification), color = colorOf(sbn),
            )
        }
        Log.i(TAG, "the back's list starts with the ${items.size} on the front now")
        BackNotices.seed(this, items)
    }

    /** What is on the front now, every key, so the back drops what has left. */
    private fun sendActive() {
        if (!tracksFront(ConfigStore(this).load())) return
        val keys = runCatching { activeNotifications }.getOrNull()?.map { it.key } ?: return
        BackNotices.keep(this, keys.toSet())
        sendBroadcast(
            Intent(RearActivity.ACTION_NOTIFICATIONS_ACTIVE).setPackage(packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_KEYS, keys.toTypedArray()),
        )
    }

    /** Gone from the front — swiped, tapped, cleared, withdrawn: gone from the back too. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Gone, so the same key posted again later is new again — whatever the switches say.
        seen.forget(sbn.key)
        runCatching { clearLive(sbn.key) }
        if (!tracksFront(ConfigStore(this).load())) return
        BackNotices.removed(this, sbn.key)
        sendBroadcast(
            Intent(RearActivity.ACTION_NOTIFICATION_REMOVED).setPackage(packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, sbn.key),
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // FIRST, before anything can return: whether this key was posted before. The filters
        // below drop ongoing posts, and a player's first post after it pauses — the first one
        // not ongoing — must still be known as an update of something already seen.
        val seenBefore = seen.see(sbn.key)
        sweepTests()
        // A timer, a stopwatch, a call: the live card's, and nothing of the list's or the pop-up's
        // (a call does not pop up as a message would; its card has its buttons).
        if (ConfigStore(this).load().companion && isLive(sbn)) {
            // A CALL KEEPS THE BACK: a timer's clock posting again while a call is on must not take the call's page
            // (the timer has it when the call's notification goes: clearLive).
            val calling = liveKey?.let { key -> runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key } }
                ?.let { it.notification.category == android.app.Notification.CATEGORY_CALL && it.key != sbn.key } == true
            if ((calling || callHold.key != null) && sbn.notification.category != android.app.Notification.CATEGORY_CALL) return
            // Of a dialer's several notifications of one call, the call's own has the page (LivePick.callNote), whichever spoke last.
            runCatching { publishLive(callOwn(sbn) ?: sbn) }
            return
        }
        val config = ConfigStore(this).load()
        if (!config.showNotifications) return

        // Ours would be a loop: the poll service's own ongoing notification is posted
        // continuously, and relaying it would put the panel's own readings back onto the
        // panel once a minute.
        // The one exception is the companion screen's test notification (TestNotice).
        val isTest = TestNotice.isTest(sbn.packageName, packageName, sbn.tag)
        if (sbn.packageName == packageName && !isTest) return
        // The apps picked in the settings: every app but those, or only those (NoticeFilter).
        // The settings' own test notification shows whatever is picked.
        if (!NoticeFilter.appAllowed(sbn.packageName, config, alwaysPass = isTest)) return
        // Ongoing notifications are the state of something rather than an event — a
        // media player, a download, another app's foreground service. They are posted
        // and re-posted constantly and none of them is news.
        if (sbn.isOngoing) return
        if (!sbn.isClearable) return

        // GROUP SUMMARIES. This is the one that matters in practice.
        //
        // An app that can produce several related notifications posts each of them AND a
        // summary standing for the whole group — WhatsApp sends the message, then "2
        // messages from 2 chats" a moment later. Both are real notifications and the
        // summary arrives second, so it replaced the actual message on the wheel a split
        // second after it appeared: you saw who it was from just long enough to know you
        // had missed it.
        //
        // The summary is the one to drop. It carries a count where the child carries the
        // content, and the child is what someone glancing at the back of a phone wants.
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return

        // Categories that are never news either. Same reasoning as isOngoing, but these
        // are how an app DECLARES it rather than how the system infers it.
        when (sbn.notification.category) {
            android.app.Notification.CATEGORY_SERVICE,
            android.app.Notification.CATEGORY_PROGRESS,
            android.app.Notification.CATEGORY_TRANSPORT,
            -> return
        }

        val extras = sbn.notification.extras
        // Would the FRONT alert for this? The back wakes only if so — see NotificationAlert.
        // A paused player is the case that was seen: no longer ongoing, clearable, no
        // category, a song title for a headline — so it is told by its media session.
        val ranking = android.service.notification.NotificationListenerService.Ranking()
        val ranked = runCatching { currentRanking?.getRanking(sbn.key, ranking) }.getOrNull() == true
        val verdict = NotificationAlert.verdict(
            media = extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) ||
                sbn.notification.category == android.app.Notification.CATEGORY_TRANSPORT,
            importance = if (ranked) ranking.importance else null,
            matchesFilter = if (ranked) ranking.matchesInterruptionFilter() else null,
            onlyAlertOnce = sbn.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0,
            seenBefore = seenBefore,
        )
        if (verdict == NotificationAlert.Verdict.DROP) return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val app = appLabel(sbn.packageName)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // The body only when it was asked for separately. See the class note: the back of
        // the phone is the one screen its user cannot see and everyone else can. In companion
        // mode, while the phone is locked a separate choice again (NoticeFilter.textShown).
        val locked = phoneLocked()
        // Marked secret by its app: not on the lock screen, and so not on the back while locked.
        if (NoticeFilter.secret(locked, sbn.notification.visibility)) return
        val body = if (if (config.companion) NoticeFilter.textShown(locked, config) else config.showNotificationText) text else null
        // Companion mode, locked, "Skip while locked": into the list only — no banner, no wake.
        val skip = config.companion && NoticeFilter.skipped(locked, config.noticeSkipLocked)

        // The same notification arrives more than once, and not rarely: a single post
        // measured TWO onNotificationPosted calls two milliseconds apart. The system
        // re-delivers on ranking changes and on any update to a notification already
        // showing, and neither is news — but the panel treated each as a fresh arrival
        // and turned the wheel, so every message replaced itself with a copy of itself
        // the instant it appeared.
        //
        // Keyed on the CONTENT rather than on sbn.key alone: an app that genuinely edits
        // a notification in place — a download percentage, a message being corrected —
        // keeps the same key, and that IS worth showing again.
        val fingerprint = "${sbn.key}\u0000$title\u0000$body"
        if (fingerprint == lastFingerprint && SystemClock.elapsedRealtime() - lastAt < DEDUPE_MS) {
            return
        }
        lastFingerprint = fingerprint
        lastAt = SystemClock.elapsedRealtime()
        // The group's summary, under the headline. See cascade.
        //
        // A grouped app posts one notification per conversation plus a summary standing
        // for all of them. The summary cannot be the headline — it arrives second and
        // would replace the message you actually want — but it is the right thing to put
        // UNDER one, because saying how much more there is is what it was written for.
        val next = cascade(sbn)

        Log.i(TAG, "relaying from ${sbn.packageName}${if (next != null) " (+1 in group)" else ""}" +
            if (verdict == NotificationAlert.Verdict.CARD) " — a card only, the front would not alert for it" else "")

        // Not one the front would alert for: a card for a back somebody is already looking
        // at, and the panel drops it if the back is dark. No light, no lock, no settle.
        if (verdict == NotificationAlert.Verdict.CARD || skip) {
            if (skip) Log.i(TAG, "the phone is locked and Xiaomi shows it — no banner")
            relay(app, sbn, title, body, next, config, pop = !skip)
            return
        }
        // Companion mode: woken only with "Wake the back for them" (PanelConfig.noticeWake); off,
        // the banner shows on a back already lit. No lock broadcast: there is no panel to lock.
        if (config.companion) {
            val wake = config.noticeWake
            val woke = wake && runCatching { Graph.of(this).bridge }.getOrNull()?.wake("a notification") == true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                relay(app, sbn, title, body, next, config, wake = wake)
            }, if (woke) WAKE_SETTLE_MS else 0L)
            return
        }

        // LIGHT THE PANEL FIRST, then tell it.
        //
        // The rear display dozes on its own, and a notification delivered to a sleeping
        // one is five seconds of strip animating in the dark followed by nothing to see.
        // Worse, the panel now DROPS what arrives while it is dark — deliberately, so
        // that waking it does not replay the night — so without this a notification while
        // it dozed was simply lost rather than merely unseen.
        //
        // The order matters and so does the pause. The panel decides whether to keep a
        // notice by asking its display whether there is a screen to show it on, and that
        // answer does not change the instant the keyevent is sent. A fifth of a second is
        // below noticing and comfortably past the state settling.
        val bridge = runCatching { Graph.of(this).bridge }.getOrNull()
        // A panel that was DARK a moment ago is a panel nobody was looking at, and a
        // phone nobody is looking at is very often in a pocket. Lock it, so the
        // notification does not arrive on a surface where a thigh can work the transport
        // buttons. If it was already lit, nothing changes: it was already there to be
        // touched, and seizing up in front of somebody using it would be the cure doing
        // the harm.
        if (bridge?.wake("a notification") == true) {
            sendBroadcast(Intent(RearActivity.ACTION_LOCK).setPackage(packageName))
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            relay(app, sbn, title, body, next, config)
        }, WAKE_SETTLE_MS)
    }

    private fun relay(
        app: String,
        sbn: StatusBarNotification,
        title: String?,
        body: String?,
        next: Pair<String, String>?,
        config: dev.kb896.aiusage.data.PanelConfig,
        pop: Boolean = true,
        wake: Boolean = false,
    ) {
        BackNotices.posted(
            this,
            whoAndWhat(sbn.notification.extras, title.orEmpty(), body.orEmpty()).let { (who, what) ->
                dev.kb896.aiusage.rear.NoticeShade.Item(
                    sbn.key, sbn.packageName, app, who, if (body == null) "" else what,
                    time = sbn.postTime, actions = actionWords(sbn.notification), color = colorOf(sbn),
                )
            },
            pop = pop,
            wake = wake,
        )
        sendBroadcast(
            Intent(RearActivity.ACTION_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_APP, app)
                // The package, not the icon itself. :rear can ask PackageManager for a
                // drawable; putting a Bitmap through a broadcast would copy it across
                // the process boundary on every notification for no gain.
                .putExtra(RearActivity.EXTRA_NOTIFICATION_PKG, sbn.packageName)
                // Which front notification this is, so its card can follow it there.
                .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, sbn.key)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_TITLE, title.orEmpty())
                .putExtra(RearActivity.EXTRA_NOTIFICATION_TEXT, body.orEmpty())
                .putExtra(RearActivity.EXTRA_NOTIFICATION_MORE_TITLE, next?.first.orEmpty())
                .putExtra(
                    RearActivity.EXTRA_NOTIFICATION_MORE_TEXT,
                    // The second card's body obeys the same switch as the first's.
                    if (config.showNotificationText) next?.second.orEmpty() else "",
                ),
        )
    }

    /**
     * THIS ONE'S GROUP SUMMARY, or null.
     *
     * The second line under the headline used to be the newest OTHER message in the
     * group, which reads as a second notification and is the wrong thing entirely: two
     * unrelated messages stacked on a panel look like a list you are meant to read, and
     * the older of them is not news by the time you see it.
     *
     * What belongs there is the summary — the "2 messages from 2 chats" line the app
     * posts alongside its children. It says how much more there is without pretending to
     * be a second event, which is exactly the job of a line under a headline.
     *
     * The summary is still never relayed as a headline in its own right; see the
     * FLAG_GROUP_SUMMARY drop above, which exists because the summary arrives second and
     * replaced the actual message. Dropped as a headline, kept as a subtitle.
     *
     * Title and text stay separate so the panel can lay them out; flattening them here
     * would decide the design in the wrong place.
     */
    /**
     * Whether the back's list follows the front's: always in companion mode (the list pulled down
     * on the back is to be current), otherwise the panel's own "Match the front" setting.
     */
    private fun tracksFront(config: dev.kb896.aiusage.data.PanelConfig) = config.companion || config.matchFrontNotifications

    private fun cascade(sbn: StatusBarNotification): Pair<String, String>? {
        val group = sbn.notification.group ?: return null
        val active = runCatching { activeNotifications }.getOrNull() ?: return null
        return active
            .firstOrNull {
                it.notification.group == group &&
                    it.packageName == sbn.packageName &&
                    it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0
            }
            ?.let { summary ->
                val e = summary.notification.extras
                val h = e.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                // EXTRA_TEXT first, then EXTRA_SUMMARY_TEXT: apps disagree about which
                // one carries the count, and a summary with neither is a summary worth
                // nothing to look at.
                val t = e.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                    ?: e.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT)?.toString()
                if (h.isNullOrBlank() && t.isNullOrBlank()) null else (h.orEmpty() to t.orEmpty())
            }
    }

    /**
     * The app's human name, falling back to its package.
     *
     * A package name is not nothing — it identifies the sender — and showing
     * "com.whatsapp" beats showing a blank where an app should be.
     */
    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Whether the phone is locked now (its keyguard's word); unknown counts as locked, the private side. */
    private fun phoneLocked(): Boolean = runCatching {
        getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked
    }.getOrNull() ?: true

    /** The last thing relayed, and when — see the de-duplication above. */
    private var lastFingerprint: String? = null
    private var lastAt = 0L

    private companion object {
        /**
         * How long the wake is given before the notice is sent.
         *
         * The panel asks its display whether it is on, and that does not become true the
         * moment the keyevent is sent. Below noticing, comfortably past the settle.
         */
        const val WAKE_SETTLE_MS = 200L

        const val TAG = "AiUsageNotif"
        /** The clocks whose ongoing notification is a timer or a stopwatch: Xiaomi's (on this phone) and Google's. */
        val CLOCKS = setOf("com.android.deskclock", "com.google.android.deskclock")
        /** A headset of any kind: a call answered from the back then goes to it, not to the speaker. */
        private val HEADSETS = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        const val DEBUG_UNBIND = "dev.kb896.aiusage.DEBUG_UNBIND"

        /**
         * How long an identical notification is treated as the same one.
         *
         * Generous. The duplicates measured were milliseconds apart, but the cost of
         * being wrong in this direction is a repeat that is silently dropped, and the
         * cost in the other is the panel flickering as a message replaces itself.
         */
        const val DEDUPE_MS = 3_000L

    }
}
