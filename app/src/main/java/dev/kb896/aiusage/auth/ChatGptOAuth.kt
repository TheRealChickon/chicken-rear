package dev.kb896.aiusage.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * ChatGPT's sign-in, for its usage limits on the back.
 *
 * WHAT THERE IS TO SIGN IN TO. ChatGPT gives a signed-in client no figures for its chat models' message
 * caps: none is documented and none was found. What it does give is the plan's CODEX limits,
 * a five-hour window and a weekly one, to its own open-source Codex client (github.com/openai/codex), and
 * that is what this reads (data/ChatGptUsageApi). This sign-in is that client's, step for step, read from
 * its source (codex-rs/login/src/server.rs, oauth/authorization.rs, oauth/client.rs):
 *  - the browser goes to `https://auth.openai.com/oauth/authorize` with the Codex client id, PKCE (S256), a
 *    state, and a redirect to a listener on this phone, `http://127.0.0.1:1455/auth/callback` (1457 when 1455
 *    is taken): those two are the only redirects OpenAI's side accepts for this client;
 *  - the code is exchanged at `/oauth/token`, form-encoded; the answer has an id token, an access token and a
 *    refresh token;
 *  - a refresh is the same address with a JSON body. REFRESH TOKENS ROTATE: each refresh gives a new one and
 *    the old one must not be used again, so every answer is stored at once.
 * The account id the usage request needs is a claim of the id token.
 *
 * NOT VERIFIED AGAINST A LIVE ACCOUNT when this was written: the sign-in needs a real account, and none was used to
 * try it. The scope asked for is the narrow one (identity and a refresh token), not the connector rights the Codex
 * client also asks for: reading usage needs none of them.
 *
 * Nothing here is logged but an HTTP status and OAuth's own short error word. Tokens never are.
 */
class ChatGptOAuth(
    private val client: OkHttpClient,
    private val issuer: HttpUrl = "https://auth.openai.com/".toHttpUrl(),
) {
    /** Message deliberately excludes the token — this is thrown into logs. */
    class Failure(message: String) : RuntimeException(message)

    /** What a sign-in or a refresh gives: the tokens, and the ChatGPT account they are for (null when the id token did not say). */
    class Signed(val record: TokenRecord, val accountId: String?)

    fun authorizeUrl(challenge: PkceChallenge, redirectUri: String): String =
        issuer.newBuilder().addPathSegments("oauth/authorize")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", challenge.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", challenge.state)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("originator", ORIGINATOR)
            .build().toString()

    fun exchange(code: String, verifier: String, redirectUri: String): Signed {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url(tokenUrl()).post(form).build()
        return send(req, "exchange", fallbackRefresh = "")
    }

    fun refresh(refreshToken: String): Signed {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(tokenUrl()).post(body).build()
        return send(req, "refresh", fallbackRefresh = refreshToken)
    }

    private fun tokenUrl() = issuer.newBuilder().addPathSegments("oauth/token").build()

    private fun send(req: Request, what: String, fallbackRefresh: String): Signed {
        try {
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw Failure("$what: HTTP ${res.code}${oauthError(body)}")
                val o = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
                    ?: throw Failure("$what: malformed token response")
                return toSigned(o, fallbackRefresh, System.currentTimeMillis())
            }
        } catch (e: IOException) {
            throw Failure("$what: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** OpenAI's own Codex client (codex-rs/login/src/auth/manager.rs, `CLIENT_ID`). */
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val SCOPES = "openid profile email offline_access"
        const val ORIGINATOR = "codex_cli_rs"
        /** The two ports OpenAI's side accepts a redirect to for this client (server.rs: DEFAULT_PORT, FALLBACK_PORT). */
        const val PORT = 1455
        const val FALLBACK_PORT = 1457
        fun redirectUri(port: Int): String = "http://127.0.0.1:$port/auth/callback"

        private const val AUTH_CLAIMS = "https://api.openai.com/auth"
        /** An access token that does not say when it ends is asked again after a day; a refused one sooner (the repository refreshes on a 401). */
        private const val ACCESS_FALLBACK_MS = 24L * 3600_000L
        /** A refresh token's end is not told: found out when a refresh is refused. */
        private const val REFRESH_ASSUMED_MS = 365L * 24 * 3600_000L

        /** RFC 6749's `error` word alone (never the free-text description, which may quote a code). */
        internal fun oauthError(body: String): String = try {
            val o = Json.parseToJsonElement(body) as? JsonObject
            val err = (o?.get("error") as? JsonPrimitive)?.contentOrNull
                ?: ((o?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
            if (err.isNullOrBlank() || err.length > 60) "" else " ($err)"
        } catch (_: Exception) { "" }

        /** The tokens of a token answer [o]; a refresh that gives no new refresh token keeps [fallbackRefresh]. */
        internal fun toSigned(o: JsonObject, fallbackRefresh: String, now: Long): Signed {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val access = str("access_token") ?: throw Failure("token response had no access_token")
            val refresh = str("refresh_token") ?: fallbackRefresh
            if (refresh.isBlank()) throw Failure("token response had no refresh_token")
            val expiresIn = (o["expires_in"] as? JsonPrimitive)?.longOrNull
            val expiresAt = expiresIn?.let { now + it * 1000L } ?: jwtExpiry(access) ?: (now + ACCESS_FALLBACK_MS)
            val account = str("id_token")?.let(::accountIdOf) ?: accountIdOf(access)
            return Signed(TokenRecord(access, refresh, expiresAt, now + REFRESH_ASSUMED_MS), account)
        }

        /** A JWT's claims, or null for anything that is not one. Never throws; the token is not kept. */
        internal fun claims(jwt: String): JsonObject? = try {
            val part = jwt.split('.').getOrNull(1) ?: return null
            Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(part), Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) { null }

        /** When a JWT ends (its `exp`, seconds), in millis; null when it does not say. */
        internal fun jwtExpiry(jwt: String): Long? = (claims(jwt)?.get("exp") as? JsonPrimitive)?.longOrNull?.let { it * 1000L }

        /** The ChatGPT account a token is for: `chatgpt_account_id` under the claim "https://api.openai.com/auth" (login/src/token_data.rs). */
        internal fun accountIdOf(jwt: String): String? =
            ((claims(jwt)?.get(AUTH_CLAIMS) as? JsonObject)?.get("chatgpt_account_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
