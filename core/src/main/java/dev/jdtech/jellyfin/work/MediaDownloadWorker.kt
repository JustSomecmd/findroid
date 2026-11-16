package dev.jdtech.jellyfin.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Downloads the media file of an item that was prepared by
 * [dev.jdtech.jellyfin.utils.DownloaderImpl].
 *
 * The file is written to its `.download` path. When the worker is stopped (paused by the user,
 * network lost, app killed), the bytes already written stay on disk and the next run asks the
 * server for the rest with an HTTP Range request. When everything has arrived, the `.download`
 * suffix is removed and the database is updated.
 */
@HiltWorker
class MediaDownloadWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val database: ServerDatabaseDao,
) : CoroutineWorker(appContext, params) {

    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    private var foregroundActive = false

    private sealed interface Outcome {
        data object Done : Outcome

        /** Try again later. [madeProgress] is true when bytes were received in this attempt. */
        data class Retry(val madeProgress: Boolean) : Outcome

        data class Failed(val message: String) : Outcome
    }

    private data class ContentRange(val start: Long, val total: Long?)

    override suspend fun doWork(): Result {
        val result = download()
        // A retry keeps this download's place. When it ends for good, the next one can start.
        if (result !is Result.Retry) {
            try {
                MediaDownloadQueue.startNext(
                    appContext,
                    WorkManager.getInstance(appContext),
                    excludeWorkId = id,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not start the next queued download")
            }
        }
        return result
    }

    private suspend fun download(): Result =
        withContext(Dispatchers.IO) {
            val itemId =
                params.inputData.getString(KEY_ITEM_ID)?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }
            val sourceId = params.inputData.getString(KEY_SOURCE_ID)
            val url = params.inputData.getString(KEY_URL)
            if (itemId == null || sourceId.isNullOrEmpty() || url.isNullOrEmpty()) {
                return@withContext Result.failure()
            }
            val title = params.inputData.getString(KEY_TITLE).orEmpty()
            val notificationId = itemId.hashCode()

            val source = database.getSources(itemId).firstOrNull { it.id == sourceId }
            if (source == null) {
                // The download was cancelled and its database entries were removed
                return@withContext Result.failure()
            }
            if (!source.path.endsWith(DOWNLOAD_SUFFIX)) {
                // Already finished in an earlier run
                return@withContext Result.success()
            }

            val file = File(source.path)
            val startBytes = if (file.exists()) file.length() else 0L
            foregroundActive =
                tryForeground(
                    createForegroundInfo(
                        notificationId,
                        title,
                        startBytes,
                        getExpectedBytes(appContext, sourceId),
                    )
                )

            val outcome =
                try {
                    performDownload(url, file, sourceId, title, notificationId)
                } catch (e: IOException) {
                    Timber.w(e, "Download of $itemId interrupted")
                    val now = if (file.exists()) file.length() else 0L
                    Outcome.Retry(madeProgress = now > startBytes)
                } catch (e: IllegalArgumentException) {
                    // The download address is not a valid URL
                    Timber.e(e)
                    Outcome.Failed(appContext.getString(CoreR.string.downloading_error))
                }

            when (outcome) {
                is Outcome.Failed -> {
                    Result.failure(workDataOf(KEY_ERROR to outcome.message))
                }
                is Outcome.Retry -> {
                    if (outcome.madeProgress) {
                        resetFailures(appContext, sourceId)
                        Result.retry()
                    } else {
                        val failures = incrementFailures(appContext, sourceId)
                        if (failures >= MAX_FAILURES_WITHOUT_PROGRESS) {
                            resetFailures(appContext, sourceId)
                            Result.failure(
                                workDataOf(
                                    KEY_ERROR to
                                        appContext.getString(CoreR.string.download_error_gave_up)
                                )
                            )
                        } else {
                            Result.retry()
                        }
                    }
                }
                is Outcome.Done -> finishDownload(itemId, sourceId, file, title)
            }
        }

    private suspend fun performDownload(
        url: String,
        file: File,
        sourceId: String,
        title: String,
        notificationId: Int,
    ): Outcome {
        file.parentFile?.mkdirs()
        val existingBytes = if (file.exists()) file.length() else 0L
        val expectedBytes = getExpectedBytes(appContext, sourceId)

        val requestBuilder =
            Request.Builder()
                .url(url)
                // Compressed responses would make byte offsets meaningless for resuming
                .header("Accept-Encoding", "identity")
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val code = response.code

            if (code == 416) {
                // Range not satisfiable: we either have the whole file or it changed on the server
                val total = parseUnsatisfiedRangeTotal(response.header("Content-Range"))
                val knownTotal = total ?: expectedBytes
                if (existingBytes > 0 && knownTotal == existingBytes) {
                    return Outcome.Done
                }
                file.delete()
                return Outcome.Retry(madeProgress = false)
            }

            if (!response.isSuccessful) {
                Timber.w("Download request failed with HTTP $code")
                return if (code == 408 || code == 429 || code >= 500) {
                    Outcome.Retry(madeProgress = false)
                } else {
                    Outcome.Failed(appContext.getString(CoreR.string.download_error_http, code))
                }
            }

            val body = response.body
            val contentLength = body.contentLength()
            val startOffset: Long
            val totalBytes: Long
            if (code == 206) {
                val range = parseContentRange(response.header("Content-Range"))
                startOffset = range?.start ?: existingBytes
                totalBytes =
                    range?.total
                        ?: if (contentLength >= 0) startOffset + contentLength else expectedBytes
            } else {
                // The server ignored the Range header and sends the whole file again
                startOffset = 0L
                totalBytes = if (contentLength >= 0) contentLength else expectedBytes
            }

            if (startOffset > existingBytes) {
                // A gap would corrupt the file, so start over
                file.delete()
                return Outcome.Retry(madeProgress = false)
            }

            if (totalBytes > 0) {
                saveExpectedBytes(appContext, sourceId, totalBytes)
                val parent = file.parentFile ?: file.absoluteFile.parentFile
                if (parent != null) {
                    val available = StatFs(parent.path).availableBytes
                    val needed = totalBytes - startOffset
                    if (needed > available) {
                        return Outcome.Failed(
                            appContext.getString(
                                CoreR.string.not_enough_storage,
                                Formatter.formatFileSize(appContext, needed),
                                Formatter.formatFileSize(appContext, available),
                            )
                        )
                    }
                }
            }

            var downloaded = startOffset
            RandomAccessFile(file, "rw").use { output ->
                if (code != 206) {
                    output.setLength(0)
                }
                // Writing at an explicit position keeps the file correct even if an earlier,
                // just-cancelled run writes its last chunk at the same time: both write the same
                // bytes at the same offsets.
                output.seek(startOffset)

                val buffer = ByteArray(BUFFER_SIZE)
                var lastUpdate = 0L
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            reportProgress(downloaded, totalBytes, title, notificationId)
                        }
                    }
                }

                if (totalBytes > 0 && downloaded < totalBytes) {
                    // The connection ended before everything arrived
                    return Outcome.Retry(madeProgress = downloaded > existingBytes)
                }
                if (totalBytes > 0 && output.length() > totalBytes) {
                    output.setLength(totalBytes)
                }
            }
        }
        return Outcome.Done
    }

    private suspend fun finishDownload(
        itemId: UUID,
        sourceId: String,
        file: File,
        title: String,
    ): Result {
        // The user may have cancelled while the last bytes were written
        val source = database.getSources(itemId).firstOrNull { it.id == sourceId }
        if (source == null || source.path != file.path) {
            return Result.failure()
        }

        val finalFile = File(file.path.removeSuffix(DOWNLOAD_SUFFIX))
        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (!file.renameTo(finalFile)) {
            Timber.e("Could not rename ${file.path} to ${finalFile.path}")
            return Result.failure(
                workDataOf(KEY_ERROR to appContext.getString(CoreR.string.downloading_error))
            )
        }
        database.setSourcePath(sourceId, finalFile.path)
        clearDownloadState(appContext, sourceId)
        showCompletedNotification(itemId, title)
        return Result.success()
    }

    private suspend fun reportProgress(
        downloaded: Long,
        total: Long,
        title: String,
        notificationId: Int,
    ) {
        setProgress(workDataOf(PROGRESS_DOWNLOADED to downloaded, PROGRESS_TOTAL to total))
        if (foregroundActive) {
            foregroundActive =
                tryForeground(createForegroundInfo(notificationId, title, downloaded, total))
        }
    }

    /**
     * Runs the download as a foreground service so Android doesn't stop it after a few minutes.
     * Android refuses this when the app is in the background (for example when a retry starts
     * later). The download then still runs, and if it is stopped it continues on the next run.
     */
    private suspend fun tryForeground(foregroundInfo: ForegroundInfo): Boolean =
        try {
            setForeground(foregroundInfo)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Timber.w(e, "Could not run download in the foreground")
            false
        } catch (e: SecurityException) {
            Timber.w(e, "Could not run download in the foreground")
            false
        }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        downloaded: Long,
        total: Long,
    ): ForegroundInfo {
        ensureNotificationChannel()
        val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
        val sizeText =
            if (total > 0) {
                "${Formatter.formatShortFileSize(appContext, downloaded)} / " +
                    Formatter.formatShortFileSize(appContext, total)
            } else {
                Formatter.formatShortFileSize(appContext, downloaded)
            }
        val notification =
            NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_download)
                .setContentTitle(
                    title.ifEmpty { appContext.getString(CoreR.string.download_downloading) }
                )
                .setContentText(sizeText)
                .setProgress(100, percent, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openAppIntent())
                .addAction(
                    CoreR.drawable.ic_pause,
                    appContext.getString(CoreR.string.pause_download),
                    WorkManager.getInstance(appContext).createCancelPendingIntent(id),
                )
                .build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun showCompletedNotification(itemId: UUID, title: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureNotificationChannel()
        val notification =
            NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(appContext.getString(CoreR.string.download_complete))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        // A different id than the progress notification, which WorkManager removes when done
        notificationManager.notify("$itemId-done".hashCode(), notification)
    }

    private fun openAppIntent(): PendingIntent? {
        val intent =
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?: return null
        return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                appContext.getString(CoreR.string.title_download),
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(channel)
    }

    private fun parseContentRange(header: String?): ContentRange? {
        if (header == null) return null
        val match = CONTENT_RANGE_REGEX.find(header) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        return ContentRange(start = start, total = match.groupValues[3].toLongOrNull())
    }

    private fun parseUnsatisfiedRangeTotal(header: String?): Long? {
        if (header == null) return null
        return UNSATISFIED_RANGE_REGEX.find(header)?.groupValues?.get(1)?.toLongOrNull()
    }

    companion object {
        const val TAG = "media_download"
        const val DOWNLOAD_SUFFIX = ".download"

        const val KEY_ITEM_ID = "KEY_ITEM_ID"
        const val KEY_SOURCE_ID = "KEY_SOURCE_ID"
        const val KEY_URL = "KEY_URL"
        const val KEY_TITLE = "KEY_TITLE"
        const val KEY_ERROR = "KEY_ERROR"

        const val PROGRESS_DOWNLOADED = "PROGRESS_DOWNLOADED"
        const val PROGRESS_TOTAL = "PROGRESS_TOTAL"

        private const val NOTIFICATION_CHANNEL_ID = "downloads"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 1000L
        private const val MAX_FAILURES_WITHOUT_PROGRESS = 8

        private const val PREFS_NAME = "media_downloads"

        private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
        private val UNSATISFIED_RANGE_REGEX = Regex("""bytes\s+\*/(\d+)""")

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun uniqueWorkName(itemId: UUID): String = "media_download_$itemId"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Remembers the full size of a download so paused downloads can show their progress. */
        fun saveExpectedBytes(context: Context, sourceId: String, bytes: Long) {
            prefs(context).edit().putLong("total_$sourceId", bytes).apply()
        }

        fun getExpectedBytes(context: Context, sourceId: String): Long =
            prefs(context).getLong("total_$sourceId", -1L)

        fun resetFailures(context: Context, sourceId: String) {
            prefs(context).edit().remove("failures_$sourceId").apply()
        }

        private fun incrementFailures(context: Context, sourceId: String): Int {
            val failures = prefs(context).getInt("failures_$sourceId", 0) + 1
            prefs(context).edit().putInt("failures_$sourceId", failures).apply()
            return failures
        }

        fun clearDownloadState(context: Context, sourceId: String) {
            prefs(context).edit().remove("total_$sourceId").remove("failures_$sourceId").apply()
        }

        fun errorMessage(outputData: Data): String? = outputData.getString(KEY_ERROR)
    }
}
