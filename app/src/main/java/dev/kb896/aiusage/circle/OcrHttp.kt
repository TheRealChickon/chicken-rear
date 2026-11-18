package dev.kb896.aiusage.circle

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class OcrHttp(base: OkHttpClient = OkHttpClient(), private val idleMillis: Long = 20_000) {
    companion object {
        val default = OcrHttp()
        private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "OCR download watchdog").apply { isDaemon = true }
        }

        fun hubDelivery(url: HttpUrl) = url.isHttps && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            url.host == "us.aws.cdn.hf.co" && url.encodedPath.startsWith("/xet-bridge-us/")
    }

    private val client = base.newBuilder()
        .connectTimeout(minOf(10_000, idleMillis), TimeUnit.MILLISECONDS)
        .readTimeout(idleMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun <T> fetch(
        url: String, start: Long, last: Long? = null, consume: (Response, () -> Unit) -> T,
    ): T {
        require(OcrCatalog.accepts(url)) { "Unknown OCR asset" }
        var target = url
        repeat(2) { hop ->
            val request = Request.Builder().url(target).header("Accept-Encoding", "identity").apply {
                if (start > 0 || last != null) header("Range", "bytes=$start-${last ?: ""}")
            }.build()
            val call = client.newCall(request)
            val progress = AtomicLong(System.nanoTime())
            val interval = (idleMillis / 4).coerceIn(1, 250)
            val timer = watchdog.scheduleWithFixedDelay({
                if (System.nanoTime() - progress.get() >= TimeUnit.MILLISECONDS.toNanos(idleMillis)) call.cancel()
            }, interval, interval, TimeUnit.MILLISECONDS)
            try {
                call.execute().use { response ->
                    val destination = response.header("Location")?.toHttpUrlOrNull()
                    if (hop == 0 && request.url.host == "huggingface.co" && response.code in 300..399 &&
                        destination != null && hubDelivery(destination)) {
                        target = destination.toString()
                    } else {
                        progress.set(System.nanoTime())
                        return consume(response) { progress.set(System.nanoTime()) }
                    }
                }
            } catch (e: IOException) {
                if (call.isCanceled()) throw SocketTimeoutException("Model server made no progress").apply { initCause(e) }
                throw e
            } finally {
                timer.cancel(false)
            }
        }
        throw IOException("Model server redirect failed")
    }
}
