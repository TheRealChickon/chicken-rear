package dev.kb896.aiusage.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The privacy texts are promises: every host the app talks to is named in the notice, and the
 * sentences that were true only of upstream's app stay out. The licence texts the APK carries are
 * where the notices say they are.
 */
class PrivacyTextTest {

    @Test fun `the notice names every host the app sends anything to`() {
        for (host in listOf("api.anthropic.com", "claude.ai", "platform.claude.com", "auth.openai.com", "chatgpt.com", "Open-Meteo")) {
            assertTrue("the notice names $host", Privacy.NOTICE.contains(host))
        }
    }

    @Test fun `the notice says what the fork keeps and measures`() {
        for (word in listOf("precise position", "tenth of a degree", "message", "Follows the music", "one picture of the screen")) {
            assertTrue("the notice says $word", Privacy.NOTICE.contains(word))
        }
    }

    @Test fun `no sentence that is not true of this fork`() {
        val all = Privacy.NOTICE + Privacy.SIGN_IN + Privacy.Access.entries.joinToString { it.text }
        for (gone in listOf("precise location is never asked for", "It is not saved", "Sync with front notifications",
                "only if you turn that on", "hides Xiaomi's rear-screen app", "lyrics")) {
            assertFalse("no longer said: $gone", all.contains(gone))
        }
    }

    @Test fun `the notice is shown again to whoever read the old one`() {
        assertTrue(Privacy.NOTICE_VERSION >= 2)
    }

    @Test fun `the apk carries the licence texts`() {
        val dir = listOf(File("src/main/assets/licenses"), File("app/src/main/assets/licenses")).first { it.isDirectory }
        for (name in listOf("LICENSE", "THIRD_PARTY_NOTICES.md", "OFL-DepartureMono.txt", "OFL-Outfit.txt", "Apache-2.0-MaterialSymbols.txt")) {
            assertTrue("assets/licenses/$name", File(dir, name).isFile)
        }
        val ofl = File(dir, "OFL-DepartureMono.txt").readText()
        assertTrue(ofl.startsWith("Copyright 2022–2024 Helena Zhang\n"))
        assertTrue(ofl.contains("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007"))
    }
}
