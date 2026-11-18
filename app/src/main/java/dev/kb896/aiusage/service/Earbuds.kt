package dev.kb896.aiusage.service

/**
 * EARBUDS CONNECTING: when to play the animation, pure. On the front a rainbow edge light (ui/EarbudsOverlay); on the back the Xiaomi-like moment with his earbuds as Sony's Sound Connect shows them (rear/ShellEarbuds).
 * It plays once per connection, never over a call, never
 * on a dark front, never without a way onto the front that he has allowed (the app's overlay or the pin's
 * accessibility overlay), and never when the switch is off.
 */
object EarbudsGate {
    /** How the front's edge light can be shown: above everything through the accessibility service he switched on, through "Display over other apps", or not at all. */
    enum class Way { GLASS_SERVICE, APP_OVERLAY, NONE }

    fun way(glassRunning: Boolean, overlayAllowed: Boolean): Way = when {
        glassRunning -> Way.GLASS_SERVICE
        overlayAllowed -> Way.APP_OVERLAY
        else -> Way.NONE
    }

    data class Decision(val plays: Boolean, val why: String)

    fun decide(switchOn: Boolean, way: Way, inCall: Boolean, frontOn: Boolean, newConnection: Boolean, xiaomiShowsItsOwn: Boolean): Decision = when {
        !switchOn -> Decision(false, "the switch is off")
        !newConnection -> Decision(false, "already shown for this connection")
        inCall -> Decision(false, "in a call")
        !frontOn -> Decision(false, "the front is off")
        xiaomiShowsItsOwn -> Decision(false, "the system shows its own card for it")
        way == Way.NONE -> Decision(false, "no way onto the front: needs “Display over other apps”")
        else -> Decision(true, "plays (${way.name.lowercase()})")
    }

    /**
     * ONE AT A TIME: a second start (another pair, "Try it" again, a profile told twice) while one plays, on either
     * screen, is not put over it ([lastStart] uptimeMillis of the one playing, 0: none yet). Two would draw over each
     * other.
     */
    fun busy(now: Long, lastStart: Long): Boolean = lastStart != 0L && now >= lastStart && now - lastStart < dev.kb896.aiusage.rear.EarbudsMotion.WHOLE_MS

    /** AudioManager modes that mean a call, or one ringing (RINGTONE 1, IN_CALL 2, IN_COMMUNICATION 3, CALL_SCREENING 4, the redirects 5 and 6). */
    fun inCall(audioMode: Int): Boolean = audioMode in 1..6

    /**
     * Which audio outputs are earbuds or a headset over Bluetooth: A2DP (8), the headset profile (SCO, 7), LE audio
     * headsets (26) and LE broadcast (30) are; a speaker over A2DP looks the same and plays it too (nothing tells
     * them apart without the Bluetooth permission).
     */
    fun isEarbuds(type: Int): Boolean = type == 7 || type == 8 || type == 26 || type == 30

    /** The key one connection is known by: the Bluetooth address, else the name. Null: not to be told apart. */
    fun key(address: String?, name: String?): String? = address?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" } ?: name?.takeIf { it.isNotBlank() }

    /** A level the device reported (0 to 100); -1 and anything else is "not known", and nothing is shown for it. */
    fun level(raw: Int): Int? = raw.takeIf { it in 0..100 }
}

/**
 * ONCE PER CONNECTION. An earbud pair shows up as several outputs at once (A2DP and the headset profile, the same
 * address), and the profiles can come and go within a second while it settles: it is new only when none of its
 * outputs was there, and gone only when the last one has been gone for [goneMs] (a profile dropping for a moment is
 * not a new connection). Outputs are told by their id, so the same output told twice (the system tells what is
 * already there when the watch begins) counts once. Not thread-safe: one thread tells it.
 */
class EarbudsConnections(private val goneMs: Long = GONE_MS) {
    private val outputs = HashMap<String, MutableSet<Int>>()
    private val goneAt = HashMap<String, Long>()

    /** Output [id] of [key] was there before the watch began: no animation for it. */
    fun seed(key: String, id: Int) { outputs.getOrPut(key) { HashSet() }.add(id) }

    /** Output [id] of [key] came at [now]: true when that makes a new connection. */
    fun added(key: String, id: Int, now: Long): Boolean {
        val set = outputs.getOrPut(key) { HashSet() }
        val wasThere = set.isNotEmpty()
        if (!set.add(id)) return false
        val left = goneAt.remove(key)
        if (wasThere) return false
        return left == null || now - left >= goneMs
    }

    /** Output [id] of [key] went at [now]. */
    fun removed(key: String, id: Int, now: Long) {
        val set = outputs[key] ?: return
        if (!set.remove(id) || set.isNotEmpty()) return
        outputs.remove(key); goneAt[key] = now
    }

    companion object { const val GONE_MS = 4_000L }
}

object SonyArt {
    const val BASE = "https://hpc-image.data-gateway.seeds.services/"

    /** The picture's id in Sony's cloud for a model ([name] as the earbuds call themselves) and colour (Sony's ModelColor byte; 0 = default). */
    fun imageId(name: String?, color: Int = 0): String? {
        val n = name?.uppercase().orEmpty()
        return when {
            "WF-1000XM6" in n -> if (color == 0x03) "d66b5c08-ef4e-4929-8025-ab63d7cf3bf7" else "428e2f01-b043-48aa-96bc-01a924dbe531"
            else -> null
        }
    }

    fun url(id: String): String = BASE + id

    /** The picture from this app's cache, else fetched from Sony's address and kept. Off the main thread. Null: not to be had (no network, refused, not a picture). */
    fun picture(context: android.content.Context, id: String): android.graphics.Bitmap? {
        val dir = java.io.File(context.cacheDir, "sony-art").apply { mkdirs() }
        val file = java.io.File(dir, "$id.png")
        if (file.length() > 0) runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()?.let { return it }
        return runCatching {
            val c = java.net.URL(url(id)).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 8_000; c.instanceFollowRedirects = true
            try {
                if (c.responseCode != 200) { android.util.Log.i(TAG, "sony art: $id answered ${c.responseCode}"); return null }
                val bytes = c.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) { android.util.Log.i(TAG, "sony art: $id larger than expected — not kept"); return null }
                val bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val tmp = java.io.File(dir, "$id.tmp")
                tmp.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(file)
                android.util.Log.i(TAG, "sony art: $id fetched (${bm.width} x ${bm.height})")
                bm
            } finally { c.disconnect() }
        }.onFailure { android.util.Log.i(TAG, "sony art: $id not fetched (${it.javaClass.simpleName})") }.getOrNull()
    }

    private const val MAX_BYTES = 6 * 1024 * 1024
    private const val TAG = "AiUsageBuds"
}
