package dev.kb896.aiusage.auth

/**
 * The small page the browser shows when a sign-in has come back to this phone (auth/LoopbackServer): what it came
 * to, in a few words, and one button back to the app. Light or dark as the phone is (the browser's
 * `prefers-color-scheme`); no colour of its own, no script, nothing loaded from anywhere.
 *
 * [service] is whose sign-in page he was on ("ChatGPT", "Claude"). [backLink] opens this app from the browser
 * (ui/SignInReturnActivity, by the app's own scheme): from a browser in front the app may not bring itself
 * forward (Android refuses an activity start from the background), so the page offers the way back and his tap
 * makes it. Null: no button, only the words.
 */
class SignInPage(private val appName: String, private val service: String, private val backLink: String?) {

    fun signedIn(): String = html("Signed in", "$service is connected. Go back to $appName.")

    /** [cause] is one whole plain sentence of what went wrong (auth/SignIns writes them); nothing secret is ever put in one. */
    fun failed(cause: String): String = html("Sign-in failed", "$cause Go back to $appName and sign in again.")

    /** The sign-in page itself sent him back with OAuth's [error] word. */
    fun refused(error: String): String = failed(refusedWords(service, error))

    /** A callback that is not the waiting sign-in's: an old tab, an attempt started over, another app's request. */
    fun stale(): String = html("Sign-in failed", "This answer does not belong to the sign-in $appName is waiting for. Go back and start it again.")

    private fun html(title: String, line: String): String {
        val button = backLink?.let { """<a href="${esc(it)}">Back to ${esc(appName)}</a>""" }.orEmpty()
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${esc(appName)}</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;font:17px/1.45 system-ui,sans-serif;background:#f3f3f1;color:#111}
main{padding:32px;max-width:24em;text-align:center}
h1{font-size:30px;font-weight:650;letter-spacing:-.3px;margin:0 0 12px}
p{margin:0 0 30px;color:#555}
a{display:inline-block;padding:14px 28px;border-radius:999px;background:#111;color:#fff;font-weight:600;text-decoration:none}
@media(prefers-color-scheme:dark){body{background:#0b0b0c;color:#f2f2f2}p{color:#a3a3a3}a{background:#f2f2f2;color:#111}}
</style></head>
<body><main><h1>${esc(title)}</h1><p>${esc(line)}</p>$button</main></body></html>
"""
    }

    companion object {
        /** What the sign-in page's own [error] means, as one sentence. */
        fun refusedWords(service: String, error: String): String =
            if (error == "access_denied") "$service's page was left without allowing it."
            else "$service's page answered with an error: $error."

        internal fun esc(s: String): String = buildString(s.length + 16) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
