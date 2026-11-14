package dev.kb896.aiusage.remote

import android.app.Activity
import android.os.Bundle

/**
 * The link door: `chickenrear://<command>?<arguments>&token=<token>`, for whatever can open a
 * link but not send a broadcast — Tasker's Browse URL, a home-screen shortcut, an NFC tag.
 *
 *   chickenrear://page?name=usage&token=…
 *   chickenrear://app.open?package=com.spotify.music&token=…
 *
 * NOT BROWSABLE, deliberately. MRSS's `mrss://` is, and so any web page that can open a link
 * can drive it; this one answers only to apps on the phone, and then only with a token.
 *
 * It shows nothing and is gone at once: it has no window (Theme.NoDisplay), keeps no place in
 * recents, and finishes before it is ever resumed, which is what that theme requires of it.
 */
class RemoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString?.let(RemoteParser::link)
        if (link != null) {
            // Named only when the caller shared its identity, as with a broadcast's sender.
            val sender = runCatching { if (android.os.Build.VERSION.SDK_INT >= 35) initialCaller.`package` else null }.getOrNull()
            RemoteDispatcher.submit(this, link.cmd, link.args, link.token, sender, via = "link")
        }
        finish()
    }
}
