package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool

internal class AndroidDownloadScheduler(val context: Context) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))

    /**
     * Fork: completed files go to the public Movies/Nuvio folder so they are
     * visible to file managers, MTP and external players. Falls back to the
     * legacy private folder when public storage is unavailable, so old
     * entries and permission-less runs keep working. Computed on each access
     * so granting "All files access" takes effect without an app restart.
     */
    val directory: File
        get() = NuvioPublicDownloads.directory()
            ?: File(context.filesDir, "downloads")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(item: DownloadItem): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        val transfer = store.begin(item)
        if (NuvioPublicDownloads.directory() == null && !NuvioPublicDownloads.hasAccess()) {
            // Fork: public folder chosen but not yet granted. Point the user at
            // Settings once; the visible error tells them to tap retry after.
            NuvioPublicDownloads.openAccessSettings(context)
            fail(transfer, IOException("Grant \"All files access\", then retry the download"))
            return store.get(item.fileName) ?: transfer
        }
        val existing = previous?.generation == transfer.generation
        if (existing && Build.VERSION.SDK_INT >= 34) return transfer
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                check(jobScheduler().schedule(buildJob(transfer)) == JobScheduler.RESULT_SUCCESS) {
                    "Android could not start the download. Open the app and try again."
                }
            } else {
                val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
                    .setInputData(workDataOf(FILE_NAME to item.fileName, GENERATION to transfer.generation))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(transfer), if (existing) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, work,
                )
                operation.result.addListener({
                    try {
                        operation.result.get()
                    } catch (error: Exception) {
                        fail(transfer, error)
                        notifyCurrent(item.fileName)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
        return store.get(item.fileName) ?: transfer
    }

    @RequiresApi(34)
    internal fun buildJob(transfer: AndroidDownloadTransfer): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    @RequiresApi(34)
    private fun jobScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    fun restore(item: DownloadItem): DownloadItem {
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        if (transfer.item.status == DownloadStatus.Downloading && Build.VERSION.SDK_INT >= 34 &&
            jobScheduler().getPendingJob(transfer.jobId) == null) {
            pause(item.fileName)
            transfer = store.get(item.fileName) ?: transfer
        }
        return transfer.item
    }

    fun pause(fileName: String) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val paused = store.update(fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused, errorMessage = null))
        }
        cancelScheduled(transfer)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        store.remove(fileName)
        transfer?.let(::cancelScheduled)
        transfer?.let { DownloadsLiveStatusPlatform.removeNotification(it.item.id) }
        cleanupScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    File(directory, "$fileName.part").delete()
                    // Fork: remove our sidecar too.
                    File(File(directory, fileName).path + ".nuvio.json").delete()
                    DownloadSubtitleStorage(File(directory, fileName).toURI().toString()).remove()
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        val destination = File(directory, fileName)
        destination.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create download directory" }
        }
        val client = if (network != null) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else downloadHttpClient
        try {
            // Fork: subtitles are best-effort — a hanging subtitle host must
            // never stall the video itself past this guard.
            withTimeoutOrNull(90_000L) {
                DownloadSubtitles.prepare(transfer.item, destination.toURI().toString())
            }
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            var lastProgressAt = 0L
            val partial = if (destination.isFile) destination else transferAndroidDownload(
                item = transfer.item,
                directory = directory,
                validator = transfer.validator,
                client = client,
                onHeaders = { total, validator ->
                    updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
                },
                onProgress = { bytes, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 1_000L || bytes == total) {
                        lastProgressAt = now
                        updateActive(transfer) {
                            it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                        }?.let(onProgress)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            updateActive(transfer) { current ->
                if (partial != destination && !partial.renameTo(destination)) {
                    throw IOException("Could not finalize the downloaded file")
                }
                val bytes = destination.length()
                // Fork: a 0-byte "success" is never a real file (unknown
                // totals skip the size check in the transfer layer).
                if (bytes <= 0L) {
                    destination.delete()
                    throw IOException("Download completed with an empty file")
                }
                current.copy(item = current.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = destination.toURI().toString(),
                    downloadedBytes = bytes,
                    totalBytes = bytes,
                    errorMessage = null,
                ))
            }
            // Fork: self-describing sidecar so Nuvio (or anything else) always
            // knows what this file is, without a hand-written mapping.
            runCatching { writeNuvioSidecar(destination, transfer.item) }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val retry = shouldRetryAndroidDownload(error, transfer.retryCount)
            if (retry) updateActive(transfer) { it.copy(retryCount = it.retryCount + 1) }
            else fail(transfer, error)
            retry
        } finally {
            if (network != null) withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = friendlyDownloadError(error)))
        }
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int): Boolean =
    retries < 4 && when (error) {
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }

/**
 * Fork: loud, plain-word failure reasons. A code the user can quote beats
 * "Download failed" every time.
 */
internal fun friendlyDownloadError(error: Exception): String {
    if (error is DownloadHttpException) {
        return when (error.statusCode) {
            429 -> "HTTP 429 — the host is throttling this link. Wait a while, then retry."
            403 -> "HTTP 403 — the host refused access. The link may need a refresh."
            404, 410 -> "HTTP 404 — the file is gone from the host."
            408 -> "HTTP 408 — the host timed out. Retry."
            in 500..599 -> "HTTP ${error.statusCode} — host error. Wait a bit, then retry."
            else -> "HTTP ${error.statusCode} — download failed."
        }
    }
    val message = error.message.orEmpty()
    return when {
        message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true) ->
            "Network timeout — the host stopped answering. Retry."
        message.contains("Unable to resolve host", ignoreCase = true) || message.contains("UnknownHost", ignoreCase = true) ->
            "DNS failure — check your connection, then retry."
        message.contains("Connection refused", ignoreCase = true) || message.contains("failed to connect", ignoreCase = true) ->
            "Could not reach the host. Retry."
        message.isNotBlank() -> message
        else -> "Download failed."
    }
}

/** Fork: <video>.nuvio.json next to the file — tidy metadata, machine-readable. */
internal fun writeNuvioSidecar(videoFile: File, item: DownloadItem) {
    val sidecar = File(videoFile.path + ".nuvio.json")
    val json = org.json.JSONObject()
        .put("app", "nuvio-fork")
        .put("id", item.id)
        .put("contentType", item.contentType)
        .put("parentMetaId", item.parentMetaId)
        .put("parentMetaType", item.parentMetaType)
        .put("videoId", item.videoId)
        .put("title", item.title)
        .put("seasonNumber", item.seasonNumber ?: org.json.JSONObject.NULL)
        .put("episodeNumber", item.episodeNumber ?: org.json.JSONObject.NULL)
        .put("episodeTitle", item.episodeTitle)
        .put("providerName", item.providerName)
        .put("streamTitle", item.streamTitle)
        .put("downloadedAtEpochMs", System.currentTimeMillis())
    sidecar.writeText(json.toString(2), Charsets.UTF_8)
}
