package dev.kb896.aiusage.circle

import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class OcrPack(
    private val dir: File,
    http: OcrHttp = OcrHttp.default,
    retryDelays: List<Long> = listOf(1_000, 3_000),
    parallelThreshold: Long = 4_000_000,
    private val fetch: ((String, Long) -> Response)? = null,
) {
    private fun log(message: String) { if (fetch == null) runCatching { android.util.Log.i("AiUsageModels", message) } }
    private val transfer = ModelTransfer(http, retryDelays, parallelThreshold, fetch)
    fun model(a: OcrAsset) = File(dir, a.fileName)

    private fun archivePart(a: OcrAsset) = File(dir, "${a.name}.part")
    private fun hubPart(a: OcrAsset) = File(dir, "${a.name}.hf.part")
    private fun staged(a: OcrAsset) = File(dir, "${a.name}.installing")
    private fun parts(a: OcrAsset) = listOf(archivePart(a), hubPart(a))
        .flatMap { listOf(it) + ModelTransfer.pieces(it) } + staged(a)

    fun partialBytes(a: OcrAsset): Long {
        // The consent/storage budget is based on the BOS fallback. HF bytes belong to a different representation.
        val part = archivePart(a)
        val size = part.length()
        if (size > a.bytes) return 0
        if (size > a.bytes / 3) return size
        return (size + ModelTransfer.pieces(part).sumOf(File::length)).coerceAtMost(a.bytes)
    }

    fun hasPartial(a: OcrAsset) = parts(a).any(File::isFile)

    fun verified(a: OcrAsset) = matches(model(a), a.modelBytes, a.modelSha)

    private fun matches(file: File, size: Long, pin: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) } == pin
    }

    /** Called by the single install worker. Both the distribution tar and the model slice have independent compiled pins. */
    fun install(a: OcrAsset, checkpoint: () -> Unit = {}, progress: (Long) -> Unit) {
        checkpoint()
        val part = archivePart(a)
        if (verified(a)) {
            parts(a).forEach(File::delete)
            progress(a.bytes)
            return
        }
        Files.createDirectories(dir.toPath())
        val hub = a.hubUrl
        val direct = hubPart(a)
        var hubFailure: IOException? = null
        if (hub != null && part.length() != a.bytes) {
            try {
                transfer.download(hub, a.modelBytes, direct, checkpoint, progress)
                if (!matches(direct, a.modelBytes, a.modelSha)) {
                    direct.delete()
                    throw IOException("Hugging Face model checksum failed")
                }
            } catch (e: IOException) {
                checkpoint()
                hubFailure = e
                log("${a.name}: Hugging Face unavailable, trying Paddle BOS")
            }
            if (hubFailure == null) {
                publish(a, direct, checkpoint)
                log("${a.name}: verified from Hugging Face (${a.modelBytes} bytes)")
                progress(a.bytes)
                return
            }
        }
        try {
            transfer.download(a.url, a.bytes, part, checkpoint, progress)
        } catch (e: IOException) {
            hubFailure?.let(e::addSuppressed)
            throw e
        }
        checkpoint()
        if (!matches(part, a.bytes, a.sha256)) {
            Files.delete(part.toPath())
            throw IOException("Download checksum failed")
        }
        // Every pinned vendor tar is uncompressed. Copy its audited regular-file extent only.
        // Never interpret remote paths, links, permissions or archive metadata on the phone.
        val staged = staged(a)
        java.io.RandomAccessFile(part, "r").use { source ->
            source.seek(a.offset)
            staged.outputStream().buffered().use { out ->
                val buf = ByteArray(65536)
                var left = a.modelBytes
                while (left > 0) {
                    checkpoint()
                    val n = minOf(left, buf.size.toLong()).toInt()
                    source.readFully(buf, 0, n)
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
        if (!matches(staged, a.modelBytes, a.modelSha)) {
            staged.delete()
            throw IOException("Model checksum failed")
        }
        publish(a, staged, checkpoint)
        log("${a.name}: verified from Paddle BOS (${a.modelBytes} bytes)")
        progress(a.bytes)
    }

    private fun publish(a: OcrAsset, verified: File, checkpoint: () -> Unit) {
        checkpoint()
        Files.move(verified.toPath(), model(a).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        parts(a).forEach(File::delete)
    }

    fun remove(a: OcrAsset) {
        if (a.name == "kannada") Files.deleteIfExists(File(dir, "kannada-graph.onnx").toPath())
        for (file in listOf(model(a)) + parts(a)) {
            Files.deleteIfExists(file.toPath())
        }
    }
}
