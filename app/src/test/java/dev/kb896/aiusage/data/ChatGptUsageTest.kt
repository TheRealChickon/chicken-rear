package dev.kb896.aiusage.data

import dev.kb896.aiusage.auth.ChatGptOAuth
import dev.kb896.aiusage.auth.PkceChallenge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * ChatGPT on the usage page: its Codex limits, read as OpenAI's own Codex client reads them. The answers here are
 * MADE UP in the shape of that client's generated model (codex-backend-openapi-models): no live
 * account was used for any of this. They hold the reading of that shape, not that OpenAI's side still sends it.
 */
class ChatGptUsageTest {
    private lateinit var server: MockWebServer
    private val now = 1_800_000_000_000L

    @Before fun up() { server = MockWebServer().apply { start() } }
    @After fun down() { server.shutdown() }

    private val answer = """
        {"plan_type":"plus",
         "rate_limit":{"allowed":true,"limit_reached":false,
           "primary_window":{"used_percent":37,"limit_window_seconds":18000,"reset_after_seconds":5400,"reset_at":1800005400},
           "secondary_window":{"used_percent":62,"limit_window_seconds":604800,"reset_after_seconds":259200,"reset_at":1800259200}},
         "credits":{"has_credits":false,"unlimited":false,"balance":null},
         "additional_rate_limits":[{"limit_name":"codex_other","metered_feature":"codex_other",
           "rate_limit":{"allowed":true,"limit_reached":false,
             "primary_window":{"used_percent":5,"limit_window_seconds":18000,"reset_after_seconds":600,"reset_at":1800000600},
             "secondary_window":{"used_percent":12,"limit_window_seconds":604800,"reset_after_seconds":86400,"reset_at":1800086400}}}]}
    """.trimIndent()

    @Test fun `the five hours, the week and a further limit become the page's three windows`() {
        val s = ChatGptUsageParser.parse(answer, now)!!
        assertEquals(listOf(WindowKind.SESSION, WindowKind.WEEKLY_ALL, WindowKind.WEEKLY_SCOPED), s.windows.map { it.kind })
        assertEquals(0.37f, s.windows[0].utilization, 1e-4f)
        assertEquals(now + 5_400_000L, s.windows[0].resetsAt)
        assertEquals(0.62f, s.windows[1].utilization, 1e-4f)
        assertEquals(now + 259_200_000L, s.windows[1].resetsAt)
        assertEquals("Other", s.windows[2].label)
        assertEquals(0.12f, s.windows[2].utilization, 1e-4f)
        assertEquals("plus", ChatGptUsageParser.plan(answer))
    }

    @Test fun `which window is the five hours goes by its length, not its place`() {
        val swapped = """{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false,
            "primary_window":{"used_percent":80,"limit_window_seconds":604800,"reset_after_seconds":100,"reset_at":0},
            "secondary_window":{"used_percent":10,"limit_window_seconds":18000,"reset_after_seconds":50,"reset_at":0}}}"""
        val s = ChatGptUsageParser.parse(swapped, now)!!
        assertEquals(0.10f, s.windows.first { it.kind == WindowKind.SESSION }.utilization, 1e-4f)
        assertEquals(0.80f, s.windows.first { it.kind == WindowKind.WEEKLY_ALL }.utilization, 1e-4f)
    }

    @Test fun `a week alone, no limits at all, a moment instead of seconds left - nothing is invented`() {
        val weekOnly = """{"plan_type":"free","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":null,
            "secondary_window":{"used_percent":101,"limit_window_seconds":604800,"reset_after_seconds":0,"reset_at":1800259200}}}"""
        val w = ChatGptUsageParser.parse(weekOnly, now)!!.windows.single()
        assertEquals(WindowKind.WEEKLY_ALL, w.kind)
        assertEquals("never over full", 1f, w.utilization, 0f)
        assertEquals("the moment given, in seconds", 1_800_259_200_000L, w.resetsAt)
        assertTrue(ChatGptUsageParser.parse("""{"plan_type":"plus","rate_limit":null}""", now)!!.windows.isEmpty())
        assertNull("not the answer at all", ChatGptUsageParser.parse("<html>Just a moment...</html>", now))
        assertNull(ChatGptUsageParser.parse("""{"detail":"nope"}""", now))
        assertNull("an unknown plan is no plan", ChatGptUsageParser.plan("""{"plan_type":"unknown"}"""))
    }

    @Test fun `the request carries the token and the account's id, to ChatGPT's usage address`() {
        server.enqueue(MockResponse().setBody(answer))
        val s = ChatGptUsageApi(OkHttpClient(), server.url("/")).fetch("tok-abc", "acct-1")
        assertEquals(3, s.windows.size)
        val req = server.takeRequest()
        assertEquals("/backend-api/wham/usage", req.path)
        assertEquals("GET", req.method)
        assertEquals("Bearer tok-abc", req.getHeader("Authorization"))
        assertEquals("acct-1", req.getHeader("chatgpt-account-id"))
        assertEquals(Agent.VALUE, req.getHeader("User-Agent"))
    }

    @Test fun `turned away as an unknown client it asks once more as the Codex client names itself, then says unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html>"))
        server.enqueue(MockResponse().setBody(answer))
        assertEquals(3, ChatGptUsageApi(OkHttpClient(), server.url("/")).fetch("t", null).windows.size)
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("codex-cli", second.getHeader("User-Agent"))
        assertNull("no id known: no header", second.getHeader("chatgpt-account-id"))

        server.enqueue(MockResponse().setResponseCode(403)); server.enqueue(MockResponse().setResponseCode(403))
        try { ChatGptUsageApi(OkHttpClient(), server.url("/")).fetch("tok-zzz", null); fail("should have failed") }
        catch (e: UsageApi.Failure) { assertEquals(UsageApi.Kind.UNAUTHORIZED, e.kind); assertFalse("the token is not in the message", e.message!!.contains("tok-zzz")) }
    }

    @Test fun `a 401, a 429 with its wait, a 500 and a page that is not the answer each say what they are`() {
        fun kind(r: MockResponse): UsageApi.Failure {
            server.enqueue(r)
            return try { ChatGptUsageApi(OkHttpClient(), server.url("/")).fetch("t", "a"); throw AssertionError("no failure") } catch (e: UsageApi.Failure) { e }
        }
        assertEquals(UsageApi.Kind.UNAUTHORIZED, kind(MockResponse().setResponseCode(401)).kind)
        val limited = kind(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertEquals(UsageApi.Kind.RATE_LIMITED, limited.kind); assertEquals(120L, limited.retryAfterSec)
        assertEquals(UsageApi.Kind.SERVER, kind(MockResponse().setResponseCode(500)).kind)
        assertEquals(UsageApi.Kind.SERVER, kind(MockResponse().setBody("<html>Just a moment...</html>")).kind)
    }

    // -- the sign-in ------------------------------------------------------------------------------------------

    private fun jwt(payload: String) = "x." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".y"

    @Test fun `the browser is sent to OpenAI's page as its Codex client sends it`() {
        val c = PkceChallenge("verifier", "challenge", "state-123")
        val url = ChatGptOAuth(OkHttpClient()).authorizeUrl(c, ChatGptOAuth.redirectUri(ChatGptOAuth.PORT)).toHttpUrl()
        assertEquals("auth.openai.com", url.host)
        assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:1455/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("state-123", url.queryParameter("state"))
        assertEquals("true", url.queryParameter("id_token_add_organizations"))
        assertEquals("true", url.queryParameter("codex_cli_simplified_flow"))
        // Identity and a refresh token: none of the connector rights the Codex client also asks for.
        assertEquals("openid profile email offline_access", url.queryParameter("scope"))
        assertFalse("the verifier never leaves in the address", url.toString().contains("verifier"))
        assertEquals("http://127.0.0.1:1457/auth/callback", ChatGptOAuth.redirectUri(ChatGptOAuth.FALLBACK_PORT))
    }

    @Test fun `the code is exchanged form-encoded and the answer's tokens, end and account are read`() {
        val exp = (now / 1000L) + 864_000L
        val access = jwt("""{"exp":$exp}""")
        val id = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-9","chatgpt_plan_type":"plus"}}""")
        server.enqueue(MockResponse().setBody("""{"id_token":"$id","access_token":"$access","refresh_token":"r1"}"""))
        val signed = ChatGptOAuth(OkHttpClient(), server.url("/")).exchange("the-code", "the-verifier", "http://127.0.0.1:1455/auth/callback")
        assertEquals(access, signed.record.accessToken)
        assertEquals("r1", signed.record.refreshToken)
        assertEquals("when the access token itself says it ends", exp * 1000L, signed.record.expiresAt)
        assertEquals("acct-9", signed.accountId)
        val req = server.takeRequest()
        assertEquals("/oauth/token", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        val body = req.body.readUtf8()
        for (part in listOf("grant_type=authorization_code", "client_id=app_EMoamEEZ73f0CkXaXp7hrann", "code=the-code", "code_verifier=the-verifier", "redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"))
            assertTrue("$part in $body", body.contains(part))
    }

    @Test fun `a refresh is JSON, keeps the old refresh token only when no new one comes, and a refusal says only OAuth's word`() {
        server.enqueue(MockResponse().setBody("""{"access_token":"a2","refresh_token":"r2"}"""))
        val oauth = ChatGptOAuth(OkHttpClient(), server.url("/"))
        val next = oauth.refresh("r1")
        assertEquals("a2", next.record.accessToken); assertEquals("the new one: the old is spent", "r2", next.record.refreshToken)
        val req = server.takeRequest()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val sent = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals(setOf("client_id", "grant_type", "refresh_token"), sent.keys)

        server.enqueue(MockResponse().setBody("""{"access_token":"a3"}"""))
        assertEquals("r2", oauth.refresh("r2").record.refreshToken)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"the token r2-secret was used"}"""))
        try { oauth.refresh("r2-secret"); fail("should have failed") }
        catch (e: ChatGptOAuth.Failure) { assertEquals("refresh: HTTP 400 (invalid_grant)", e.message) }
    }

    @Test fun `what is not a token says nothing and throws nothing`() {
        assertNull(ChatGptOAuth.claims("not-a-jwt"))
        assertNull(ChatGptOAuth.jwtExpiry("a.b.c"))
        assertNull(ChatGptOAuth.accountIdOf(jwt("""{"sub":"x"}""")))
        assertNotNull(ChatGptOAuth.claims(jwt("""{"exp":1}""")))
    }

    @Test fun `ChatGPT is the third account and keeps the two Claude accounts where they were`() {
        assertEquals(listOf(Account.PERSONAL, Account.WORK, Account.CHATGPT), Account.entries.toList())
        assertEquals(Account.CHATGPT, Account.fromSlug("chatgpt"))
        assertEquals("com.openai.chatgpt", Account.CHATGPT.app)
        assertEquals("com.anthropic.claude", Account.PERSONAL.app)
        assertEquals("ChatGPT", Account.CHATGPT.title)
    }
}
