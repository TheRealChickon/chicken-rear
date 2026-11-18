package dev.kb896.aiusage.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.kb896.aiusage.R
import dev.kb896.aiusage.data.ConfigStore
import java.util.concurrent.Executors

/** What the health check reads on the phone (the Android half; HealthRun decides with it). */
interface HealthReader {
    fun stamp(): Health.Stamp
    /** Which start of the phone this is (its boot count): a notification does not outlive a restart, its memory must not either. */
    fun boot(): String
    fun facts(shell: Health.Shell?): Health.Facts
}

/** What the health check keeps between its looks. */
interface HealthMemory {
    var stamp: String?
    var shell: Health.Shell?
    /** What the notification posted last stands for (Health.Notice.signature); null for none up. */
    var posted: String?
    /** The boot ([HealthReader.boot]) it was posted in: in another one it is gone, whatever [posted] says. */
    var postedBoot: String?
}

interface HealthNotifier {
    fun post(n: Health.Notice)
    fun cancel()
}

/**
 * One look: the phone's stamp against the last one (a change: the shell looks at Xiaomi's packages again), the
 * shell's half if a shell is bound (else what it said last), the problems, and the notification put up, changed or
 * taken down. Posted again only when what it stands for changes: dismissed by him, the same problems do not come back
 * (in the same boot: a restart takes the notification away, so the same problems after one are told again).
 * Pure but for its three parts, which tests replace.
 */
class HealthRun(
    private val reader: HealthReader,
    private val memory: HealthMemory,
    private val notifier: HealthNotifier,
    private val askShell: (again: Boolean) -> Health.Shell?,
    private val log: (String) -> Unit = {},
) {
    fun run(): List<Health.Problem> {
        val stamp = reader.stamp()
        val changes = Health.changes(Health.decode(memory.stamp), stamp)
        if (changes.isNotEmpty()) log("health: changed since the last look: ${changes.joinToString("; ")} — looking again")
        val answer = askShell(changes.isNotEmpty())
        val asked = answer?.over(memory.shell)
        if (asked != null) memory.shell = asked
        // Stored only once the shell has looked again after a change: a change seen while no shell was there is
        // looked at again when one is.
        if (changes.isEmpty() || answer?.looked == true) memory.stamp = Health.encode(stamp)
        val facts = reader.facts(asked ?: memory.shell)
        val problems = Health.problems(facts)
        log(if (problems.isEmpty()) "health: all well" else "health: ${problems.joinToString { it.key.name.lowercase() }}")
        val notice = Health.notice(problems, facts.notificationsAllowed)
        val boot = reader.boot()
        val up = memory.posted?.takeIf { memory.postedBoot == boot }
        when {
            notice == null -> if (memory.posted != null) { notifier.cancel(); memory.posted = null; memory.postedBoot = null }
            notice.signature != up -> { notifier.post(notice); memory.posted = notice.signature; memory.postedBoot = boot }
        }
        return problems
    }
}

/**
 * THE HEALTH CHECK ON THE PHONE: started with the poll service, it looks at start, 90 s later (past a restart's own
 * waiting), whenever Shizuku comes or goes or a shell service is bound, and when one of the packages the back leans on
 * is installed, updated or removed (Health.WATCHED); a new HyperOS build is seen by its fingerprint at the first look
 * after the restart it ends in. Every look runs on one background thread, coalesced; nothing polls, nothing holds the
 * phone awake. Its lines are under the tag AiUsageHealth.
 */
object HealthCheck {
    private const val TAG = "AiUsageHealth"
    private const val PREFS = "health"
    private const val CHANNEL = "health"
    private const val NOTIFICATION_ID = 7

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "health").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var queued = false
    private var receiver: BroadcastReceiver? = null
    /** The waiting looks past a grace and past a new shell's first look (Health.waitsFor), main thread only. */
    private val dues = Health.Slot.entries.associateWith { Health.Due() }

    fun start(context: Context) {
        val app = context.applicationContext
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (pkg in Health.WATCHED) cue(app, Health.Cue.PACKAGE, "$pkg ${intent.action?.substringAfterLast('_')?.lowercase()}")
                }
            }
            runCatching {
                ContextCompat.registerReceiver(app, r, IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiver = r
            }
        }
        cue(app, Health.Cue.START, "start")
    }

    /** The waiting looks stay: they were asked for by Shizuku and the shell, which outlive this service. */
    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    fun cue(context: Context, cue: Health.Cue, why: String) {
        val app = context.applicationContext
        for (w in Health.waitsFor(cue)) {
            if (w.slot == Health.Slot.SOON) { soon(app, why); continue }
            main.post {
                val due = dues.getValue(w.slot)
                if (!due.arm(android.os.SystemClock.uptimeMillis(), w)) return@post
                main.removeCallbacksAndMessages(w.slot)
                main.postAtTime({ due.fired(); soon(app, "$why, ${w.delayMs / 1000} s on") }, w.slot, android.os.SystemClock.uptimeMillis() + w.delayMs)
            }
        }
    }

    /** A look, soon (a second and a half, so that what set it off has settled); one at a time, several asks one look. */
    fun soon(context: Context, why: String) {
        if (queued) return
        queued = true
        val app = context.applicationContext
        main.postDelayed({
            runCatching { worker.execute { queued = false; runCatching { look(app, why) }.onFailure { Log.w(TAG, "health: the look failed: ${it.message}") } } }
                .onFailure { queued = false }
        }, Health.SOON_MS)
    }

    private fun look(app: Context, why: String) {
        Log.i(TAG, "health: looking ($why)")
        val bridge = Graph.of(app).bridge
        HealthRun(AndroidReader(app), Memory(app), Notifier(app), askShell = { again -> bridge.health(again)?.let(::shellOf) }, log = { Log.i(TAG, it) }).run()
    }

    /** The problems as they are now, for the settings' Health row: read at once (main thread), the shell's half as it last said. */
    fun now(context: Context): List<Health.Problem> = runCatching {
        Health.problems(AndroidReader(context.applicationContext).facts(Memory(context.applicationContext).shell))
    }.getOrDefault(emptyList())

    fun shellOf(b: android.os.Bundle): Health.Shell = Health.Shell(
        coverMade = b.getBoolean("coverMade"),
        coverError = b.getString("coverError"),
        dialerMissing = b.getStringArray("dialerMissing")?.toSet(),
        clockMissing = b.getStringArray("clockMissing")?.toSet(),
        xiaomiMissing = b.getStringArray("xiaomiMissing")?.toList(),
        looked = b.getLong("lookedAt", 0L) > 0L,
        autostart = b.getStringArray("autostart")?.toList(),
        searchOffline = b.getStringArray("searchOffline")?.toList(),
    )

    /** Where the Health row's button leads. */
    fun intentFor(context: Context, action: Health.Action): Intent? = when (action) {
        Health.Action.OPEN_SHIZUKU -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        Health.Action.LISTENER_ACCESS -> Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Health.Action.BATTERY -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.fromParts("package", context.packageName, null))
        Health.Action.NOTIFICATIONS -> Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        Health.Action.NONE -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private class AndroidReader(private val c: Context) : HealthReader {
        override fun stamp(): Health.Stamp = Health.Stamp(
            android.os.Build.FINGERPRINT,
            Health.WATCHED.associateWith { pkg -> runCatching { c.packageManager.getPackageInfo(pkg, 0).longVersionCode }.getOrNull() },
        )

        override fun boot(): String =
            runCatching { android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrNull()?.let { "count $it" }
                // Without the count: when the phone started, to the minute (the same start reads the same).
                ?: "at ${(System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000L}"

        override fun facts(shell: Health.Shell?): Health.Facts {
            val bridge = Graph.of(c).bridge
            val status = bridge.status()
            val config = ConfigStore(c).load()
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pin = Intent(Intent.ACTION_SEND).setClassName(dev.kb896.aiusage.rear.PinVia.PACKAGE, dev.kb896.aiusage.rear.PinVia.ACTIVITY).setType("text/plain")
            return Health.Facts(
                shizukuUp = status.running,
                shizukuAllowed = status.granted,
                shellBound = bridge.shellBound(),
                guardBound = bridge.guardBound(),
                waitedMs = ShizukuArrival.sinceChangeMs(),
                shell = shell,
                listenerAllowed = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName),
                listenerConnected = RelayWatch.connected,
                batteryUnrestricted = runCatching { pm.isIgnoringBatteryOptimizations(c.packageName) && !am.isBackgroundRestricted }.getOrNull(),
                notificationsAllowed = NotificationManagerCompat.from(c).areNotificationsEnabled(),
                pinByXiaomi = config.pinByXiaomi,
                pinActivity = runCatching { c.packageManager.resolveActivity(pin, 0) != null }.getOrDefault(false),
                xiaomiRearVersion = runCatching { c.packageManager.getPackageInfo(dev.kb896.aiusage.rear.PinVia.PACKAGE, 0).longVersionCode }.getOrNull(),
            )
        }
    }

    private class Memory(c: Context) : HealthMemory {
        private val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override var stamp: String?
            get() = p.getString("stamp", null)
            set(v) { p.edit().putString("stamp", v).apply() }
        override var shell: Health.Shell?
            get() = if (!p.getBoolean("shell", false)) null else Health.Shell(
                coverMade = p.getBoolean("coverMade", false),
                coverError = p.getString("coverError", null),
                dialerMissing = p.getStringSet("dialerMissing", null),
                clockMissing = p.getStringSet("clockMissing", null),
                xiaomiMissing = p.getStringSet("xiaomiMissing", null)?.toList(),
                looked = p.getBoolean("looked", true),
                autostart = p.getString("autostart", null)?.let { t -> if (t.isEmpty()) emptyList() else t.split('\n') },
                searchOffline = p.getString("searchOffline", null)?.let { t -> if (t.isEmpty()) emptyList() else t.split('\n') },
            )
            set(v) {
                p.edit().apply {
                    putBoolean("shell", v != null)
                    putBoolean("coverMade", v?.coverMade == true)
                    putBoolean("looked", v?.looked != false)
                    if (v?.coverError != null) putString("coverError", v.coverError) else remove("coverError")
                    if (v?.dialerMissing != null) putStringSet("dialerMissing", v.dialerMissing) else remove("dialerMissing")
                    if (v?.clockMissing != null) putStringSet("clockMissing", v.clockMissing) else remove("clockMissing")
                    if (v?.xiaomiMissing != null) putStringSet("xiaomiMissing", v.xiaomiMissing.toSet()) else remove("xiaomiMissing")
                    if (v?.autostart != null) putString("autostart", v.autostart.joinToString("\n")) else remove("autostart")
                    if (v?.searchOffline != null) putString("searchOffline", v.searchOffline.joinToString("\n")) else remove("searchOffline")
                }.apply()
            }
        override var posted: String?
            get() = p.getString("posted", null)
            set(v) { p.edit().putString("posted", v).apply() }
        override var postedBoot: String?
            get() = p.getString("postedBoot", null)
            set(v) { p.edit().putString("postedBoot", v).apply() }
    }

    private class Notifier(private val c: Context) : HealthNotifier {
        private val mgr get() = c.getSystemService(NotificationManager::class.java)

        override fun post(n: Health.Notice) {
            runCatching {
                mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Health", NotificationManager.IMPORTANCE_LOW))
                val tap = PendingIntent.getActivity(
                    c, NOTIFICATION_ID, Intent(c, dev.kb896.aiusage.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                mgr.notify(NOTIFICATION_ID, Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(n.title)
                    .setContentText(n.text)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build())
                Log.i(TAG, "health: told on the front: ${n.title}")
            }.onFailure { Log.w(TAG, "health: not told: ${it.message}") }
        }

        override fun cancel() {
            runCatching { mgr.cancel(NOTIFICATION_ID) }
            Log.i(TAG, "health: the notification taken down")
        }
    }
}
