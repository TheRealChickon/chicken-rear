package dev.kb896.aiusage.circle

import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Resumable byte transfer for one pinned representation. Each range has a contiguous on-disk prefix. */
internal class ModelTransfer(
    private val http: OcrHttp,
    private val retryDelays: List<Long>,
    private val parallelThreshold: Long,
    private val fixture: ((String, Long) -> Response)? = null,
) {
    private class Rejected(message: String) : IOException(message)
    private class NoRange : IOException()
    private val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    companion object {
        fun pieces(part: File) = (1..2).map { File(part.parentFile, "${part.name}.$it") }
    }

    fun download(url: String, size: Long, part: File, checkpoint: () -> Unit, progress: (Long) -> Unit) {
        val extras = pieces(part)
        val split = size / 3
        if (part.length() > size) part.delete()
        if (part.length() == size) return
        if (fixture == null && size >= parallelThreshold && part.length() <= split) {
            try {
                retry(checkpoint) {
                    request(url, 0, 0) { response, _ ->
                        if (response.code == 200 || response.code == 416) throw NoRange()
                        responseEnd(response, 0, 1, size, bounded = true)
                    }
                }
                val files = listOf(part) + extras
                val progressLock = Any()
                val pool = Executors.newFixedThreadPool(files.size)
                try {
                    val tasks = files.mapIndexed { i, file -> Callable {
                        val start = i * split
                        val end = if (i == files.lastIndex) size else start + split
                        if (file.length() > end - start) file.delete()
                        copyRange(url, file, start, end, size, true, checkpoint) {
                            synchronized(progressLock) { progress(files.sumOf(File::length)) }
                        }
                    } }
                    pool.invokeAll(tasks).forEach { result ->
                        try {
                            result.get()
                        } catch (e: ExecutionException) {
                            throw e.cause ?: e
                        }
                    }
                } finally {
                    pool.shutdown()
                }
                // The first range is already the prefix of the final file. If interrupted while
                // appending, the next run can resume the resulting contiguous prefix normally.
                FileOutputStream(part, true).use { out ->
                    for (file in extras) {
                        checkpoint()
                        file.inputStream().use { it.copyTo(out) }
                        file.delete()
                    }
                }
                return
            } catch (_: NoRange) {
                checkpoint()
            }
        }
        extras.forEach(File::delete)
        copyRange(url, part, 0, size, size, false, checkpoint, progress)
    }

    private fun copyRange(
        url: String, part: File, start: Long, end: Long, size: Long, bounded: Boolean,
        checkpoint: () -> Unit, progress: (Long) -> Unit,
    ) {
        var restarted = false
        while (start + part.length() < end) {
            retry(checkpoint) {
                val offset = start + part.length()
                request(url, offset, if (bounded) end - 1 else null) { response, received ->
                    if (bounded && (response.code == 200 || response.code == 416)) throw NoRange()
                    if (response.code == 416 && offset > 0 && !restarted) {
                        part.delete()
                        restarted = true
                        return@request
                    }
                    val limit = responseEnd(response, offset, end, size, bounded)
                    val append = response.code == 206
                    var position = if (append) offset else 0
                    val body = response.body ?: throw Rejected("Empty model response")
                    if (body.contentLength() >= 0 && body.contentLength() != limit - position) {
                        throw Rejected("Wrong model response length")
                    }
                    FileOutputStream(part, append).use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(65_536)
                        while (position < limit) {
                            checkpoint()
                            val length = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - position).toInt())
                            if (length < 0) throw IOException("Model response ended early")
                            received()
                            out.write(buffer, 0, length)
                            position += length
                            progress(position - start)
                        }
                        if (body.contentLength() < 0) {
                            // A chunked response must also end at the declared range boundary.
                            val trailing = try {
                                input.read()
                            } catch (e: IOException) {
                                out.channel.truncate(limit - start - 1)
                                throw e
                            }
                            if (trailing != -1) {
                                out.channel.truncate(if (append) offset - start else 0)
                                throw Rejected("Response exceeds pinned range")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun responseEnd(response: Response, offset: Long, end: Long, size: Long, bounded: Boolean): Long {
        return when (response.code) {
            200 -> size
            206 -> {
                val fields = range.matchEntire(response.header("Content-Range").orEmpty())?.groupValues
                    ?: throw Rejected("Missing model response range")
                val last = fields[2].toLongOrNull() ?: throw Rejected("Invalid model response range")
                if (fields[1].toLongOrNull() != offset || fields[3].toLongOrNull() != size ||
                    last !in offset until end || bounded && last != end - 1) {
                    throw Rejected("Wrong model response range")
                }
                last + 1
            }
            408, 429, in 500..599 -> throw IOException("Model server returned HTTP ${response.code}")
            else -> throw Rejected("Model server returned HTTP ${response.code}")
        }
    }

    private fun request(url: String, offset: Long, last: Long?, consume: (Response, () -> Unit) -> Unit) {
        if (fixture != null) fixture.invoke(url, offset).use { consume(it) {} }
        else http.fetch(url, offset, last, consume)
    }

    private fun retry(checkpoint: () -> Unit, attempt: () -> Unit) {
        for (index in 0..retryDelays.size) {
            checkpoint()
            try {
                attempt()
                return
            } catch (e: IOException) {
                checkpoint()
                if (e is Rejected || e is NoRange || index == retryDelays.size) throw e
                var left = retryDelays[index]
                while (left > 0) {
                    val wait = minOf(left, 250L)
                    Thread.sleep(wait)
                    checkpoint()
                    left -= wait
                }
            }
        }
    }
}
