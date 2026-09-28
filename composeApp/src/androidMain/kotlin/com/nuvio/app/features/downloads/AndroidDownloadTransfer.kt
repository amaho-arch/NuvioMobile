package com.nuvio.app.features.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal val downloadHttpClient = OkHttpClient.Builder()
    // Fork: fail fast on connect (airplane mode, dead host) so errors
    // surface in seconds; slow-but-alive hosts still get generous reads.
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal class DownloadHttpException(val statusCode: Int) : IOException("Download failed: HTTP $statusCode")

/** Fork: parallel connections (Gopeed/ADM-style). Small files stay single-stream. */
private const val CHUNK_COUNT = 4
private const val MIN_CHUNKED_BYTES = 4L * 1024L * 1024L

internal suspend fun transferAndroidDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    // Fork: fileName may be a relative sub-path (Shows/.../file.mkv).
    // Reject absolute paths and parent escapes, allow nested segments.
    require(item.fileName.isNotBlank() && !item.fileName.startsWith("/") &&
        !item.fileName.split('/').any { it.isBlank() || it == "." || it == ".." })
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }

    // Legacy single-stream resume in progress: keep the old path.
    val legacyPartial = File(directory, "${item.fileName}.part")
    if (legacyPartial.isFile && legacyPartial.length() > 0L && !hasChunkFiles(directory, item.fileName)) {
        return@coroutineScope transferSingleStream(
            item = item,
            directory = directory,
            validator = validator,
            client = client,
            onHeaders = onHeaders,
            onProgress = onProgress,
        )
    }

    val probe = probeRangeSupport(item, validator, client)
    val total = probe?.totalBytes
    if (probe == null || total == null || total < MIN_CHUNKED_BYTES) {
        return@coroutineScope transferSingleStream(
            item = item,
            directory = directory,
            validator = validator,
            client = client,
            onHeaders = onHeaders,
            onProgress = onProgress,
        )
    }

    onHeaders(total, probe.validator)
    try {
        transferChunked(
            item = item,
            directory = directory,
            totalBytes = total,
            validator = probe.validator,
            client = client,
            onProgress = onProgress,
        )
    } catch (error: RangeUnsupportedException) {
        // Server stopped honoring ranges mid-download: one clean single pass.
        ensureActive()
        transferSingleStream(
            item = item,
            directory = directory,
            validator = validator,
            client = client,
            onHeaders = onHeaders,
            onProgress = onProgress,
        )
    }
}

private class RangeUnsupportedException : IOException("Server does not honor range requests")

private data class RangeProbe(
    val totalBytes: Long?,
    val validator: String?,
    val rangeSupported: Boolean,
)

private fun hasChunkFiles(directory: File, fileName: String): Boolean {
    for (index in 0 until CHUNK_COUNT) {
        if (File(directory, "$fileName.part-$index").isFile) return true
    }
    return false
}

private fun baseRequest(item: DownloadItem): Request.Builder {
    return Request.Builder().url(item.sourceUrl).apply {
        item.sourceHeaders.forEach { (key, value) ->
            if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                !key.equals("Accept-Encoding", true)) header(key, value)
        }
        header("Accept-Encoding", "identity")
    }
}

private suspend fun probeRangeSupport(
    item: DownloadItem,
    validator: String?,
    client: OkHttpClient,
): RangeProbe? = coroutineScope {
    val activeCall = AtomicReference<Call?>()
    val probe = async(Dispatchers.IO) {
        val request = baseRequest(item).apply {
            header("Range", "bytes=0-0")
            validator?.let { header("If-Range", it) }
        }.build()
        val call = client.newCall(request)
        activeCall.set(call)
        ensureActive()
        call.execute().use { response ->
            if (response.code == 416) return@async RangeProbe(null, validator, false)
            if (!response.isSuccessful) throw DownloadHttpException(response.code)
            if (response.code != 206) return@async RangeProbe(
                totalBytes = response.body?.contentLength()?.takeIf { it >= 0L },
                validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                    ?: response.header("Last-Modified"),
                rangeSupported = false,
            )
            val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
            // Drain the 1-byte body so the connection can be reused.
            // Never drain a 200: without range support that could be the whole file.
            if (response.code == 206) {
                runCatching { response.body?.bytes() }
            } else {
                runCatching { response.body?.close() }
            }
            RangeProbe(
                totalBytes = range?.second,
                validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                    ?: response.header("Last-Modified"),
                rangeSupported = true,
            )
        }
    }
    try {
        probe.await()
    } finally {
        activeCall.get()?.cancel()
    }
}

private suspend fun transferChunked(
    item: DownloadItem,
    directory: File,
    totalBytes: Long,
    validator: String?,
    client: OkHttpClient,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    val calls = ConcurrentHashMap.newKeySet<Call>()
    val downloaded = AtomicLong(0L)
    try {
        val chunkSize = totalBytes / CHUNK_COUNT
        val jobs = (0 until CHUNK_COUNT).map { index ->
            val start = index * chunkSize
            val end = if (index == CHUNK_COUNT - 1) totalBytes - 1 else (index + 1) * chunkSize - 1
            val chunkFile = File(directory, "${item.fileName}.part-$index")
            async(Dispatchers.IO) {
                downloadChunk(
                    item = item,
                    validator = validator,
                    client = client,
                    calls = calls,
                    chunkFile = chunkFile,
                    start = start,
                    end = end,
                    downloaded = downloaded,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                )
            }
        }
        jobs.awaitAll()
        ensureActive()
        // Merge in order into the legacy .part path so finalize logic is untouched.
        val partial = File(directory, "${item.fileName}.part")
        FileOutputStream(partial, false).use { output ->
            for (index in 0 until CHUNK_COUNT) {
                val chunkFile = File(directory, "${item.fileName}.part-$index")
                check(chunkFile.isFile) { "Missing download chunk $index" }
                chunkFile.inputStream().use { input -> input.copyTo(output) }
            }
            output.fd.sync()
        }
        for (index in 0 until CHUNK_COUNT) {
            File(directory, "${item.fileName}.part-$index").delete()
        }
        check(partial.length() == totalBytes) { "Merged file size does not match expected total" }
        partial
    } finally {
        calls.forEach { it.cancel() }
    }
}

private suspend fun downloadChunk(
    item: DownloadItem,
    validator: String?,
    client: OkHttpClient,
    calls: MutableSet<Call>,
    chunkFile: File,
    start: Long,
    end: Long,
    downloaded: AtomicLong,
    totalBytes: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
) {
    var position = start + chunkFile.takeIf(File::isFile)?.length().orZero()
    if (position - start > end - start + 1) {
        // Oversized chunk from a stale attempt: restart it cleanly.
        chunkFile.delete()
        position = start
    }
    if (position > end) {
        downloaded.addAndGet(end - start + 1)
        onProgress(downloaded.get(), totalBytes)
        return
    }
    // Account for resumed bytes before the first network read.
    if (position > start) {
        downloaded.addAndGet(position - start)
        onProgress(downloaded.get(), totalBytes)
    }
    val request = baseRequest(item).apply {
        header("Range", "bytes=$position-$end")
        validator?.let { header("If-Range", it) }
    }.build()
    val call = client.newCall(request)
    calls.add(call)
    try {
        call.execute().use { response ->
            if (response.code == 416) {
                // Already complete (or file changed): verify and move on.
                if (position > end) return
                throw IOException("Server rejected chunk range")
            }
            if (response.code == 200) throw RangeUnsupportedException()
            if (!response.isSuccessful) throw DownloadHttpException(response.code)
            val body = response.body ?: throw IOException("Download response is empty")
            body.byteStream().use { input ->
                FileOutputStream(chunkFile, position > start).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer)
                        if (size == -1) break
                        output.write(buffer, 0, size)
                        position += size
                        onProgress(downloaded.addAndGet(size.toLong()), totalBytes)
                    }
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            if (position != end + 1) throw IOException("Chunk ended before all bytes were received")
        }
    } finally {
        calls.remove(call)
    }
}

private fun Long?.orZero(): Long = this ?: 0L

internal suspend fun transferSingleStream(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    val activeCall = AtomicReference<Call?>()
    val transfer = async(Dispatchers.IO) {
        try {
            val partial = File(directory, "${item.fileName}.part")
            var offset = partial.takeIf(File::isFile)?.length() ?: 0L
            var restarted = false

            while (true) {
                ensureActive()
                val request = baseRequest(item).apply {
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        validator?.let { header("If-Range", it) }
                    }
                }.build()
                val call = client.newCall(request)
                activeCall.set(call)
                ensureActive()
                call.execute().use { response ->
                    if (response.code == 416 && offset > 0L && !restarted) {
                        offset = 0L
                        restarted = true
                        return@use
                    }
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
                    val resumed = response.code == 206
                    if (resumed && (range == null || range.first != offset)) {
                        throw IOException("Server returned an invalid download range")
                    }
                    val startingBytes = if (resumed) offset else 0L
                    val body = response.body ?: throw IOException("Download response is empty")
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    val totalBytes = range?.second ?: contentLength?.let { startingBytes + it }
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    onHeaders(totalBytes, responseValidator)
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    body.byteStream().use { input ->
                        FileOutputStream(partial, resumed && offset > 0L).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                output.write(buffer, 0, size)
                                downloaded += size
                                onProgress(downloaded, totalBytes)
                            }
                            output.fd.sync()
                        }
                    }
                    ensureActive()
                    if (totalBytes != null && downloaded != totalBytes) {
                        throw IOException("Download ended before all bytes were received")
                    }
                    return@async partial
                }
            }
            @Suppress("UNREACHABLE_CODE")
            partial
        } catch (error: Exception) {
            // Closing a cancelled HTTP call throws IOException from its blocking read.
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCall.get()?.cancel()
    }
}

internal fun parseDownloadContentRange(value: String): Pair<Long, Long?>? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull()
    if (end < start || (total != null && end >= total)) return null
    return start to total
}
