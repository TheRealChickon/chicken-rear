package dev.kb896.aiusage.circle

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = ByteArray(120_000) { (it % 97).toByte() }
    private val asset = OcrCatalog.detector.copy(bytes = bytes.size.toLong(), sha256 = sha(bytes),
        offset = 0, modelBytes = bytes.size.toLong(), modelSha = sha(bytes))
    private val part get() = File(temp.root, "det.part")
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun transport(server: MockWebServer, idle: Long = 2_000) = OcrHttp(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = server.url("/${request.url.host}").newBuilder().host("127.0.0.1").build()
            chain.proceed(request.newBuilder().url(url).build())
        }.build(), idle,
    )

    private fun MockWebServer.request() = requireNotNull(takeRequest(3, TimeUnit.SECONDS)) { "Missing HTTP request" }

    private fun range(first: Int, last: Int) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", "bytes $first-$last/${bytes.size}")
        .setBody(Buffer().write(bytes, first, last - first + 1))

    @Test fun stalledBodyResumesFromBytesAlreadyOnDisk() {
        server().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val first = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(bytes))
                        .throttleBody(30_000, 3, TimeUnit.SECONDS)
                    val offset = first.removePrefix("bytes=").removeSuffix("-").toInt()
                    assertTrue(offset in 1 until bytes.size)
                    return range(offset, bytes.lastIndex)
                }
            }
            ModelTransfer(transport(server, 900), listOf(0), Long.MAX_VALUE)
                .download(asset.url, asset.bytes, part, {}, {})
            assertEquals("bytes=30000-", listOf(server.request(), server.request()).last().getHeader("Range"))
            assertArrayEquals(bytes, part.readBytes())
        }
    }

    @Test fun headerTimeoutExhaustsBoundedAttempts() {
        server().use { server ->
            repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val transfer = ModelTransfer(transport(server, 150), listOf(0, 0), Long.MAX_VALUE)
            assertThrows(IOException::class.java) { transfer.download(asset.url, asset.bytes, part, {}, {}) }
            assertEquals(3, server.requestCount)
            assertEquals(0, part.length())
        }
    }

    @Test fun parallelRangesOverlapAndAssembleInByteOrder() {
        server().use { server ->
            val concurrent = CountDownLatch(3)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val bounds = request.getHeader("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
                    if (bounds != listOf(0, 0)) {
                        concurrent.countDown()
                        check(concurrent.await(2, TimeUnit.SECONDS))
                    }
                    return range(bounds[0], bounds[1])
                }
            }
            part.writeBytes(bytes.copyOf(11))
            ModelTransfer(transport(server), emptyList(), 10)
                .download(asset.url, asset.bytes, part, {}, {})
            assertArrayEquals(bytes, part.readBytes())
            assertEquals(setOf("bytes=0-0", "bytes=11-39999", "bytes=40000-79999", "bytes=80000-119999"),
                List(4) { server.request().getHeader("Range") }.toSet())
        }
    }

    @Test fun noRangeFallsBackToAFullBody() {
        server().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
            part.writeBytes(bytes.copyOf(11))
            ModelTransfer(transport(server), emptyList(), 10)
                .download(asset.url, asset.bytes, part, {}, {})
            assertArrayEquals(bytes, part.readBytes())
            assertEquals("bytes=0-0", server.request().getHeader("Range"))
            assertEquals("bytes=11-", server.request().getHeader("Range"))
        }
    }

    @Test fun corruptHubFileFallsBackToTheExistingBosPrefix() {
        server().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(bytes.size))))
            part.writeBytes(bytes.copyOf(11))
            server.enqueue(range(11, bytes.lastIndex))
            OcrPack(temp.root, transport(server), emptyList(), Long.MAX_VALUE).install(asset) {}
            assertEquals("/huggingface.co", server.request().path)
            val bos = server.request()
            assertEquals("/paddle-model-ecology.bj.bcebos.com", bos.path)
            assertEquals("bytes=11-", bos.getHeader("Range"))
            assertArrayEquals(bytes, File(temp.root, asset.fileName).readBytes())
            assertFalse(File(temp.root, "det.hf.part").exists())
        }
    }

    @Test fun hubAllowsOnlyItsOwnDeliveryRedirectAndPreservesTheRange() {
        server().use { server ->
            val cdn = "https://us.aws.cdn.hf.co/xet-bridge-us/model?signature=fixture"
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn))
            server.enqueue(range(11, 100))
            val http = transport(server)
            http.fetch(asset.hubUrl!!, 11, 100) { response, _ -> assertEquals(206, response.code) }
            assertEquals("/huggingface.co", server.request().path)
            val redirected = server.request()
            assertEquals("/us.aws.cdn.hf.co", redirected.path)
            assertEquals("bytes=11-100", redirected.getHeader("Range"))
            for (url in listOf("http://us.aws.cdn.hf.co/xet-bridge-us/model",
                "$cdn#fragment", "https://us.aws.cdn.hf.co.evil.invalid/xet-bridge-us/model",
                "https://us.aws.cdn.hf.co:444/xet-bridge-us/model", "https://us.aws.cdn.hf.co/unrelated")) {
                assertFalse(OcrHttp.hubDelivery(url.toHttpUrl()))
            }
        }
    }
}
