package dev.kb896.aiusage.circle

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class OcrPackTest {
    @get:Rule val temp = TemporaryFolder()
    private val plain = "test data for private OCR".toByteArray()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val asset = OcrCatalog.recognizers.first().copy(name = "eng", bytes = plain.size.toLong(), sha256 = sha(plain),
        offset = 0, modelBytes = plain.size.toLong(), modelSha = sha(plain))
    private val part get() = File(temp.root, "eng.part")
    private val model get() = File(temp.root, "eng.onnx")
    private fun reply(code: Int, bytes: ByteArray, range: String? = null): Response =
        Response.Builder().request(Request.Builder().url(asset.url).build()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(bytes.toResponseBody()).apply {
                if (range != null) header("Content-Range", range)
                if (code == 302) header("Location", "https://example.invalid/model")
            }.build()

    @Test fun onlyExactPublishedHttpsPathsCanReachTheNetwork() {
        assertTrue(OcrCatalog.assets.all { OcrCatalog.accepts(it.url) })
        for (bad in listOf(
            asset.url.replace("https:", "http:"), asset.url.replace(".com/", ".com.evil.invalid/"),
            asset.url.replace(".com/", ".com@evil.invalid/"), asset.url.replace(".com/", ".com:444/"),
            asset.url + "?mirror=1", asset.url + "#x", asset.url.replace("paddlex/", "other/"), asset.url.replace("paddle3.0.0", "paddle4.0.0"),
            asset.url.replace("_infer.tar", ".zip"), "https://objects.githubusercontent.com/model",
        )) assertThrows(IllegalArgumentException::class.java) { OcrHttp.default.fetch(bad, 0) { _, _ -> } }
    }

    @Test fun accurateOnlyBecomesReadyWithThePairAndNeverCountsAsFast() {
        val det = OcrCatalog.accurateDetector
        val rec = OcrCatalog.accurateRecognizer
        val pair = setOf(det, rec)
        val state = OcrModels.State(installed = pair)
        assertFalse(state.readyFor(OcrModel.FAST))
        assertTrue(state.readyFor(OcrModel.ACCURATE))
        assertFalse(state.copy(installed = setOf(det)).readyFor(OcrModel.ACCURATE))
        assertFalse(state.copy(installed = setOf(rec)).readyFor(OcrModel.ACCURATE))
        assertEquals(listOf(rec), OcrCatalog.accurateMissing(setOf(det)))
        assertTrue(pair.none { it in OcrCatalog.recognizers })
        assertEquals(16, OcrCatalog.assets.map { it.url }.distinct().size)
        assertEquals(OcrModel.FAST, OcrModel.saved("unknown"))
    }

    @Test fun httpClientSendsTheResumeOffsetAndNeverFollowsTheRedirect() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/forbidden")))
            server.enqueue(MockResponse().setBody("unexpected second request"))
            val localSocket = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/asset")).build())
            }.build()
            OcrHttp(localSocket).fetch(asset.url, 123) { response, _ -> assertEquals(302, response.code) }
            val sent = server.takeRequest()
            assertEquals("bytes=123-", sent.getHeader("Range"))
            assertEquals("identity", sent.getHeader("Accept-Encoding"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rangeRefusalRecoversFromZeroOnce() {
        part.writeBytes(plain.copyOf(5))
        val offsets = mutableListOf<Long>()
        OcrPack(temp.root) { _, offset ->
            offsets.add(offset)
            if (offset > 0) reply(416, byteArrayOf()) else reply(200, plain)
        }.install(asset) {}
        assertEquals(listOf(5L, 0L), offsets)
        assertArrayEquals(plain, model.readBytes())
    }

    @Test fun interruptedTransferResumesOnANewInstaller() {
        var calls = 0
        val cut = 11
        val fetch: (String, Long) -> Response = { url, offset ->
            assertEquals(asset.url, url)
            if (calls++ == 0) {
                assertEquals(0L, offset)
                reply(206, plain.copyOf(cut), "bytes 0-${cut - 1}/${plain.size}")
            } else {
                assertEquals(cut.toLong(), offset)
                if (calls == 2) throw IOException("lost connection")
                reply(206, plain.copyOfRange(cut, plain.size), "bytes $cut-${plain.lastIndex}/${plain.size}")
            }
        }
        assertThrows(IOException::class.java) { OcrPack(temp.root, retryDelays = emptyList(), fetch = fetch).install(asset) {} }
        assertArrayEquals(plain.copyOf(cut), part.readBytes())
        assertFalse(model.exists())
        OcrPack(temp.root, retryDelays = emptyList(), fetch = fetch).install(asset) {}
        assertArrayEquals(plain, model.readBytes())
        assertFalse(part.exists())
        OcrPack(temp.root) { _, _ -> error("offline reuse called network") }.install(asset) {}
    }

    @Test fun cancelledDownloadKeepsThePartialAndResumesWithRange() {
        val cut = 11
        var cancel = false
        val pack = OcrPack(temp.root) { _, offset ->
            assertEquals(0L, offset)
            reply(206, plain.copyOf(cut), "bytes 0-${cut - 1}/${plain.size}")
        }
        assertThrows(IOException::class.java) {
            pack.install(asset, checkpoint = { if (cancel) throw IOException("paused") }) { cancel = true }
        }
        assertEquals(cut.toLong(), pack.partialBytes(asset))
        assertFalse(model.exists())
        OcrPack(temp.root) { _, offset ->
            assertEquals(cut.toLong(), offset)
            reply(206, plain.copyOfRange(cut, plain.size), "bytes $cut-${plain.lastIndex}/${plain.size}")
        }.install(asset) {}
        assertArrayEquals(plain, model.readBytes())
        assertFalse(part.exists())
    }

    @Test fun complete200ReplacesThePartialWhenRangeIsIgnored() {
        part.writeBytes(byteArrayOf(9, 8, 7))
        OcrPack(temp.root) { _, offset ->
            assertEquals(3, offset)
            reply(200, plain)
        }.install(asset) {}
        assertArrayEquals(plain, model.readBytes())
    }

    @Test fun mismatchedRangeOrRedirectCannotAppendOrPublish() {
        val bytes = plain.copyOf(5)
        part.writeBytes(bytes)
        for ((code, header) in listOf(302 to null, 206 to null, 206 to "bytes 0-5/${plain.size}",
            206 to "bytes 5-6/999", 206 to "bytes 5-3/${plain.size}")) {
            assertThrows(IOException::class.java) {
                OcrPack(temp.root) { _, _ -> reply(code, plain, header) }.install(asset) {}
            }
            assertArrayEquals(bytes, part.readBytes())
            assertFalse(model.exists())
        }
    }

    @Test fun corruptBytesOfTheRightLengthNeverPublish() {
        val bad = plain.clone().also { it[8] = (it[8] + 1).toByte() }
        assertThrows(IOException::class.java) { OcrPack(temp.root) { _, _ -> reply(200, bad) }.install(asset) {} }
        assertFalse(part.exists())
        val pack = OcrPack(temp.root) { _, _ -> reply(200, plain) }
        assertFalse(model.exists())
        // A full verified partial left by a process death can finish offline.
        part.writeBytes(plain)
        OcrPack(temp.root) { _, _ -> error("redownloaded") }.install(asset) {}
        model.writeBytes(ByteArray(plain.size))
        assertFalse(pack.verified(asset))
        pack.install(asset) {}
        assertTrue(pack.verified(asset))
    }

    @Test fun extractOnlyPinnedExtentVerifyItsOwnHashAndRemoveOnePack() {
        val bytes = plain.copyOfRange(5,14)
        val slice = asset.copy(offset = 5, modelBytes = bytes.size.toLong(), modelSha = sha(bytes))
        val pack = OcrPack(temp.root) { _,_ -> reply(200,plain) }
        assertThrows(IOException::class.java) { pack.install(slice.copy(modelSha = sha(plain))) {} }
        assertFalse(model.exists())
        pack.install(slice) {}
        assertArrayEquals(bytes,model.readBytes())
        val other = File(temp.root,"latin.onnx").apply { writeText("existing") }
        part.writeBytes(plain.copyOf(2))
        File(temp.root,"eng.installing").writeText("incomplete")
        pack.remove(slice)
        assertFalse(model.exists())
        assertFalse(part.exists())
        assertFalse(File(temp.root,"eng.installing").exists())
        assertEquals("existing",other.readText())
    }

    @Test fun truncatedAndOversizeResponsesCannotPublish() {
        for (body in listOf(plain.copyOf(4), plain.copyOf(plain.size + 1))) {
            assertThrows(IOException::class.java) { OcrPack(temp.root) { _, _ -> reply(200, body) }.install(asset) {} }
            assertFalse(model.exists())
        }
    }
}
