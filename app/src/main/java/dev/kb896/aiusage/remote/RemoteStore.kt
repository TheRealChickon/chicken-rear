package dev.kb896.aiusage.remote

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import dev.kb896.aiusage.auth.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * Remote control's own settings: whether it is on, who holds a token and for what, which apps
 * may be opened, where events go, and the last commands.
 *
 * NOT IN THE PANEL'S CONFIG, and that is deliberate. The config crosses to the rear process and
 * to the shell with every payload, and is put on a command line when the panel is placed; a
 * token has no business in any of those. This lives in the app's own process and nowhere else.
 *
 * A token is kept twice: its secret sealed by the Keystore, to be shown again behind the
 * phone's lock (it has to be copied into Home Assistant at some point, and "shown once" is a
 * support request waiting to happen on a tool one person runs); and its digest in the clear,
 * which is all the gate ever compares and tells nobody anything about 256 random bits.
 */
class RemoteStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val box = SecretBox(ALIAS)

    @Serializable
    data class TokenInfo(
        val id: String,
        val name: String,
        /** RemoteScope.wire, each. */
        val scopes: Set<String>,
        val pinnedTo: String? = null,
        val createdAt: Long = 0,
        /** The digest, hex: what the gate compares. */
        val hash: String = "",
        /** The secret, sealed (SecretBox). */
        val sealed: String = "",
    ) {
        fun scopeSet(): Set<RemoteScope> = scopes.mapNotNull(RemoteScope::fromWire).toSet()
    }

    @Serializable
    data class LogEntry(val at: Long, val who: String, val command: String, val outcome: String)

    /**
     * The whole feature's switch. Off, the two doors are not merely refused at the gate — they
     * are disabled components, and there is nothing exported for another app to send to.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            val state = if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            for (c in listOf(RemoteReceiver::class.java, RemoteActivity::class.java)) runCatching {
                app.packageManager.setComponentEnabledSetting(ComponentName(app, c), state, PackageManager.DONT_KILL_APP)
            }
        }

    fun tokens(): List<TokenInfo> = decode(prefs.getString(KEY_TOKENS, null))

    /** A new token for [name]: its record, and the secret to hand over. */
    fun create(name: String, scopes: Set<RemoteScope> = RemoteScope.DEFAULT, nowMs: Long = System.currentTimeMillis()): Pair<TokenInfo, String> {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
        val info = TokenInfo(
            id = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) },
            name = name.trim().take(40).ifEmpty { "Token" },
            scopes = scopes.map { it.wire }.toSet(),
            createdAt = nowMs,
            hash = RemoteGate.digest(secret).joinToString("") { "%02x".format(it) },
            sealed = box.seal(secret),
        )
        save(tokens() + info)
        return info to secret
    }

    /** The secret again, for the settings' reveal; null if it will not open (a restored backup's key is gone). */
    fun secret(id: String): String? = tokens().firstOrNull { it.id == id }?.let { box.open(it.sealed) }

    fun update(id: String, edit: (TokenInfo) -> TokenInfo) = save(tokens().map { if (it.id == id) edit(it).copy(id = it.id, hash = it.hash, sealed = it.sealed) else it })

    fun revoke(id: String) = save(tokens().filter { it.id != id })

    /** The tokens as the gate takes them. */
    fun gateTokens(): List<RemoteGate.Token> = tokens().mapNotNull { t ->
        val hash = runCatching { t.hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
        if (hash == null || hash.size != 32) null else RemoteGate.Token(t.id, t.name, hash, t.scopeSet(), t.pinnedTo)
    }

    var rules: RemotePolicy.Rules
        get() = RemotePolicy.Rules(
            apps = RemotePolicy.Apps.fromWire(prefs.getString(KEY_APPS, null)),
            listed = prefs.getStringSet(KEY_LISTED, emptySet()).orEmpty().toSet(),
            whileLocked = prefs.getStringSet(KEY_WHILE_LOCKED, emptySet()).orEmpty().toSet(),
        )
        set(value) {
            prefs.edit().putString(KEY_APPS, value.apps.wire)
                .putStringSet(KEY_LISTED, value.listed).putStringSet(KEY_WHILE_LOCKED, value.whileLocked).apply()
        }

    /** The packages events are sent to; empty, none are sent. */
    var eventTargets: Set<String>
        get() = prefs.getStringSet(KEY_EVENTS, emptySet()).orEmpty().toSet()
        set(value) { prefs.edit().putStringSet(KEY_EVENTS, value).apply() }

    fun log(): List<LogEntry> = try {
        json.decodeFromString(ListSerializer(LogEntry.serializer()), prefs.getString(KEY_LOG, null) ?: "[]")
    } catch (_: Exception) { emptyList() }

    /** Newest first, [LOG_MAX] kept. The command's name and how it went: never what it carried. */
    @Synchronized
    fun record(who: String, command: String, outcome: String, nowMs: Long = System.currentTimeMillis()) {
        val next = (listOf(LogEntry(nowMs, who, command, outcome)) + log()).take(LOG_MAX)
        prefs.edit().putString(KEY_LOG, json.encodeToString(ListSerializer(LogEntry.serializer()), next)).apply()
    }

    fun clearLog() = prefs.edit().remove(KEY_LOG).apply()

    @Synchronized
    private fun save(list: List<TokenInfo>) {
        prefs.edit().putString(KEY_TOKENS, json.encodeToString(ListSerializer(TokenInfo.serializer()), list)).apply()
    }

    private fun decode(raw: String?): List<TokenInfo> = try {
        json.decodeFromString(ListSerializer(TokenInfo.serializer()), raw ?: "[]")
    } catch (_: Exception) { emptyList() }

    companion object {
        const val LOG_MAX = 50
        private const val PREFS = "remote"
        private const val ALIAS = "chicken_rear_remote"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TOKENS = "tokens"
        private const val KEY_APPS = "apps"
        private const val KEY_LISTED = "listed"
        private const val KEY_WHILE_LOCKED = "whileLocked"
        private const val KEY_EVENTS = "events"
        private const val KEY_LOG = "log"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
