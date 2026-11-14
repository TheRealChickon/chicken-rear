package dev.kb896.aiusage.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * ChatGPT's usage limits as its own Codex client reads them: `GET https://chatgpt.com/backend-api/wham/usage`
 * with the sign-in's access token and the account's id (codex-rs/backend-client/src/client.rs).
 * These are the plan's CODEX limits (a five-hour window and a weekly one, and any further limit by name); ChatGPT
 * gives no figures for its chat models' message caps to a client. The address is not a documented one: if OpenAI
 * changes it, this fails as any fetch fails (the page says so) and invents nothing.
 *
 * The answer's shape is the Codex client's generated model (codex-backend-openapi-models: RateLimitStatusPayload,
 * RateLimitStatusDetails, RateLimitWindowSnapshot, AdditionalRateLimitDetails): [ChatGptUsageParser].
 */
class ChatGptUsageApi(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://chatgpt.com/".toHttpUrl(),
) {
    /** Throws [UsageApi.Failure], as the Claude fetch does, so one account's failure is handled as the other's is. */
    fun fetch(accessToken: String, accountId: String?): UsageSnapshot {
        val url = baseUrl.newBuilder().addPathSegments("backend-api/wham/usage").build()
        fun request(agent: String) = Request.Builder().url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", agent)
            .apply { if (!accountId.isNullOrBlank()) header("chatgpt-account-id", accountId) }
            .get().build()

        fun once(agent: String): Pair<Int, String> = try {
            client.newCall(request(agent)).execute().use { res ->
                if (res.code == 429) throw UsageApi.Failure(UsageApi.Kind.RATE_LIMITED, "chatgpt usage: HTTP 429", res.header("Retry-After")?.toLongOrNull())
                res.code to res.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw UsageApi.Failure(UsageApi.Kind.NETWORK, "chatgpt usage: ${e.javaClass.simpleName}")
        }

        // Asked as this app. A 403 that is not about the sign-in (the site turning an unknown client away) is asked
        // once more under the Codex client's own fallback name, which is the one its source sends.
        var (code, body) = once(Agent.VALUE)
        if (code == 403) once(CODEX_AGENT).let { code = it.first; body = it.second }
        when {
            code == 401 || code == 403 -> throw UsageApi.Failure(UsageApi.Kind.UNAUTHORIZED, "chatgpt usage: HTTP $code")
            code !in 200..299 -> throw UsageApi.Failure(UsageApi.Kind.SERVER, "chatgpt usage: HTTP $code")
        }
        return ChatGptUsageParser.parse(body, System.currentTimeMillis())
            ?: throw UsageApi.Failure(UsageApi.Kind.SERVER, "chatgpt usage: not the answer expected")
    }

    private companion object {
        const val CODEX_AGENT = "codex-cli"
    }
}

/** The usage answer into the app's own windows. Plain, so a test holds it; never throws. */
object ChatGptUsageParser {
    /**
     * The windows of [body]: the short one (a day or less: the five hours) as the session, the long one as the week,
     * and each further limit's longest window as a limit of its own, by its name. Null when [body] is not the answer
     * at all (not JSON, or no `plan_type` and no `rate_limit`); an answer with no windows is an empty snapshot.
     */
    fun parse(body: String, now: Long): UsageSnapshot? {
        val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null } ?: return null
        if ("rate_limit" !in root && "plan_type" !in root) return null
        val out = ArrayList<UsageWindow>()
        val main = windowsOf(root["rate_limit"] as? JsonObject)
        val session = main.firstOrNull { it.seconds in 1..DAY_S } ?: main.firstOrNull().takeIf { main.size == 2 && main.none { w -> w.seconds in 1..DAY_S } }
        val week = main.firstOrNull { it !== session }
        session?.let { out += UsageWindow(WindowKind.SESSION, "5 h", it.used, it.resetsAt(now)) }
        week?.let { out += UsageWindow(WindowKind.WEEKLY_ALL, "Week", it.used, it.resetsAt(now)) }
        for (extra in (root["additional_rate_limits"] as? JsonArray).orEmpty()) {
            val o = extra as? JsonObject ?: continue
            val w = windowsOf(o["rate_limit"] as? JsonObject).maxByOrNull { it.seconds } ?: continue
            val name = ((o["limit_name"] as? JsonPrimitive)?.contentOrNull ?: (o["metered_feature"] as? JsonPrimitive)?.contentOrNull).orEmpty()
            out += UsageWindow(WindowKind.WEEKLY_SCOPED, pretty(name), w.used, w.resetsAt(now))
        }
        return UsageSnapshot(out, now)
    }

    /** The plan as the answer names it ("plus", "pro"), or null. */
    fun plan(body: String): String? = try {
        ((Json.parseToJsonElement(body) as? JsonObject)?.get("plan_type") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "unknown" }
    } catch (_: Exception) { null }

    private class Window(val used: Float, val seconds: Long, val after: Long, val at: Long) {
        /** When it starts again: by the seconds left (no clock to disagree with), else by the moment given (seconds or millis). */
        fun resetsAt(now: Long): Long? = when {
            after > 0L -> now + after * 1000L
            at > 100_000_000_000L -> at
            at > 0L -> at * 1000L
            else -> null
        }
    }

    private fun windowsOf(limit: JsonObject?): List<Window> = listOf("primary_window", "secondary_window").mapNotNull { key ->
        val w = limit?.get(key) as? JsonObject ?: return@mapNotNull null
        fun num(k: String) = (w[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
        val percent = (w["used_percent"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        Window((percent / 100.0).toFloat().coerceIn(0f, 1f), num("limit_window_seconds"), num("reset_after_seconds"), num("reset_at"))
    }

    /** "codex_other" as "Other": the limit's own name, without the product's. */
    private fun pretty(name: String): String =
        name.removePrefix("codex_").removePrefix("codex").replace('_', ' ').trim().ifBlank { "Model" }.replaceFirstChar { it.uppercase() }

    private const val DAY_S = 24L * 3600L
}
