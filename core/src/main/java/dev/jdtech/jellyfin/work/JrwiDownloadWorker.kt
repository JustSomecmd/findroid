package dev.jdtech.jellyfin.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiAudio
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiDownload
import dev.jdtech.jellyfin.jrwi.JrwiDownloader
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.jrwi.JrwiTranscripts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Downloads a JRWI episode's audio, with its picture, the episode's details, the audio post's own
 * text and comments, and the post's files unless they were left out, so it can be listened to and
 * its page shown without a connection. A stopped download goes on where it stopped with a Range
 * request, and the finished audio is checked against the size the archiver gives. Run for a video's
 * download through Jellyfin, it keeps only the post's files.
 *
 * The downloads wait in one queue and run one at a time, in the order they were asked for, as the
 * app's video downloads do. So that one going wrong doesn't take the rest of the queue with it, a
 * run always ends as done: a download that can't be had is marked failed, for Try again, and one
 * taken off the phone meanwhile is skipped, or stopped where it is, rather than cancelled, which
 * would cancel everything after it too.
 */
@HiltWorker
class JrwiDownloadWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val jrwiApi: JrwiApi,
    private val jrwiProgress: JrwiProgressSync,
    private val downloads: JrwiDownloads,
    private val transcripts: JrwiTranscripts,
) : CoroutineWorker(appContext, params) {
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private var foreground = true

    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
        if (inputData.getBoolean(KEY_FILES_ONLY, false)) return keepFilesForVideo(itemId)
        // Taken off the phone before it could start
        if (itemId < 0 || downloads.get(itemId) == null) return Result.success()
        return try {
            withContext(Dispatchers.IO) { download(itemId) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Removed) {
            Result.success()
        } catch (e: JrwiApiException) {
            Timber.e(e, "Could not download JRWI episode $itemId")
            if (e.code in 400..499) {
                downloads.update(itemId) { it.copy(failed = true) }
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: IOException) {
            Timber.w(e, "JRWI download $itemId stopped, to go on later")
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "Could not download JRWI episode $itemId")
            downloads.update(itemId) { it.copy(failed = true) }
            Result.success()
        } finally {
            downloads.setProgress(itemId, null)
            // Taken off the phone while it ran, so nothing of it stays behind
            if (downloads.get(itemId) == null) downloads.delete(itemId)
        }
    }

    /**
     * What goes with a video's download: the transcript timed for the video (v0.23), and the post's
     * files when it still wants them.
     */
    private suspend fun keepFilesForVideo(itemId: Long): Result {
        val video = if (itemId < 0) null else downloads.savedVideo(itemId)
        if (video == null) return Result.success()
        return try {
            withContext(Dispatchers.IO) {
                video.item.transcriptPost(audio = false)?.let { transcripts.keepWithDownload(it) }
                if (downloads.videoKeepsFiles(itemId)) saveFiles(itemId)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: JrwiApiException) {
            Timber.e(e, "Could not keep the files of JRWI episode $itemId")
            if (e.code in 400..499) Result.success() else Result.retry()
        } catch (e: IOException) {
            Timber.w(e, "Keeping the files of JRWI episode $itemId stopped, to go on later")
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "Could not keep the files of JRWI episode $itemId")
            Result.success()
        } finally {
            // The video's download was removed while this ran
            downloads.dropUnwantedFiles(itemId)
        }
    }

    private suspend fun download(itemId: Long) {
        val item = jrwiApi.getItem(itemId)
        val audio = item.audio ?: throw JrwiApiException(404, "This episode has no audio")
        val showName = jrwiProgress.showNameOrNull(item.show)
        // Run again for a download that's whole, to keep what's new, such as the post's files
        val whole = downloads.get(itemId)?.done == true && downloads.audioFile(itemId).isFile
        downloads.update(itemId) {
            it.copy(
                audioPostId = audio.postId,
                title = item.title,
                showName = showName ?: it.showName,
                mime = audio.mime,
                failed = false,
            )
        } ?: return
        report(itemId, item.title, 0L, audio.size)
        // Its page's text and comments, for showing it without a connection
        downloads.saveDetails(itemId, item, post(audio.postId), comments(audio.postId))
        item.thumbnail?.let { saveFile(it, downloads.thumbnailFile(itemId)) }
        if (downloads.get(itemId)?.withFiles != false) saveFiles(itemId)
        // The lines to follow along with, timed for the audio (v0.23)
        item.transcriptPost(audio = true)?.let { transcripts.keepWithDownload(it) }
        if (whole) return
        val etag = downloadAudio(itemId, audio, item)
        downloads.update(itemId) {
            it.copy(
                size = audio.size ?: downloads.audioFile(itemId).length(),
                etag = etag,
                done = true,
            )
        }
    }

    /**
     * The files of the item's versions, apart from the video and audio, and what's inside those the
     * archiver can preview, so the file inspector works without a connection. PDFs are drawn on the
     * phone. Archivers older than 0.9.0 list no files.
     */
    private suspend fun saveFiles(itemId: Long) {
        val files =
            try {
                jrwiApi.getItemFiles(itemId)
            } catch (e: JrwiApiException) {
                if (e.code != NOT_FOUND) throw e
                emptyList()
            }
        downloads.saveFiles(itemId, files)
        for (file in files) {
            if (!file.saved || file.role == "video" || file.role == "audio") continue
            val path = file.path ?: continue
            val url = file.url ?: continue
            val target = downloads.fileFor(itemId, path)
            if (!target.isFile || (file.size != null && target.length() != file.size)) {
                saveFile(url, target)
            }
            val preview = file.preview ?: continue
            if (file.type == "pdf" || downloads.savedPreview(itemId, path) != null) continue
            try {
                downloads.savePreview(itemId, path, jrwiApi.getPreview(preview))
            } catch (e: JrwiApiException) {
                // The file itself is kept, which is enough to save it elsewhere
                if (e.code !in 400..499) throw e
            }
        }
    }

    /** The audio's own post, or null from an archiver without single posts. */
    private suspend fun post(postId: Long): JrwiItem? =
        try {
            jrwiApi.getPost(postId)
        } catch (e: JrwiApiException) {
            Timber.w(e, "No post $postId to keep")
            null
        }

    private suspend fun comments(postId: Long): List<JrwiComment> {
        val comments = mutableListOf<JrwiComment>()
        for (page in 1..MAX_COMMENT_PAGES) {
            val result = jrwiApi.getPostComments(postId, page)
            comments += result.items
            if (!result.hasMore || result.items.isEmpty()) break
        }
        return comments
    }

    /** Saves a small file, such as the picture, which can be done without if it fails. */
    private fun saveFile(path: String, target: File) {
        try {
            jrwiApi.openFile(path).use { response ->
                if (!response.isSuccessful) return
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".part")
                temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                if (!temp.renameTo(target)) temp.delete()
            }
        } catch (e: IOException) {
            Timber.w(e, "Could not keep $path")
        }
    }

    /** Downloads the audio, going on from what an earlier try left. Gives back its ETag. */
    private suspend fun downloadAudio(itemId: Long, audio: JrwiAudio, item: JrwiItem): String? {
        val part = downloads.partFile(itemId)
        part.parentFile?.mkdirs()
        val expected = audio.size
        var from = if (part.exists()) part.length() else 0L
        if (expected != null && from > expected) {
            part.delete()
            from = 0L
        }
        var etag: String? = null
        if (expected == null || from < expected) {
            jrwiApi.openFile(audio.url, from).use { response ->
                // All of it came on an earlier try
                if (response.code == RANGE_NOT_SATISFIABLE && from > 0) return@use
                if (!response.isSuccessful) {
                    throw JrwiApiException(response.code, "The archiver answered ${response.code}")
                }
                etag = response.header("ETag")
                // An archiver that sends all of it again starts it over
                if (response.code != PARTIAL_CONTENT && from > 0) {
                    part.delete()
                    from = 0L
                }
                val length = response.body.contentLength().takeIf { it >= 0 }
                val total = expected ?: length?.plus(from)
                write(response.body.byteStream(), part, from, total, itemId, item.title)
            }
        }
        if (expected != null && part.length() != expected) {
            throw IOException("Only ${part.length()} of $expected bytes of the audio came")
        }
        val file = downloads.audioFile(itemId)
        file.delete()
        if (!part.renameTo(file)) throw IOException("Could not keep the audio")
        return etag
    }

    private suspend fun write(
        input: InputStream,
        part: File,
        from: Long,
        total: Long?,
        itemId: Long,
        title: String,
    ) {
        var done = from
        var lastReport = 0L
        FileOutputStream(part, from > 0).use { out ->
            input.use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now
                        // Taken off the phone while it downloads: stopped here, for the next
                        if (downloads.get(itemId) == null) throw Removed()
                        report(itemId, title, done, total)
                    }
                }
            }
        }
    }

    private suspend fun report(itemId: Long, title: String, done: Long, total: Long?) {
        val fraction = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
        downloads.setProgress(itemId, fraction ?: 0f)
        setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to (total ?: -1L)))
        if (!foreground) return
        // Runs as a foreground service so Android doesn't stop it after a few minutes. Android
        // refuses that from the background, and the download then goes on without it.
        foreground =
            try {
                setForeground(foregroundInfo(itemId, title, done, total))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                Timber.w(e, "Could not run the JRWI download in the foreground")
                false
            } catch (e: SecurityException) {
                Timber.w(e, "Could not run the JRWI download in the foreground")
                false
            }
    }

    private fun foregroundInfo(itemId: Long, title: String, done: Long, total: Long?) =
        ForegroundInfo(
            "jrwi-$itemId".hashCode(),
            NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_download)
                .setContentTitle(title.ifEmpty { appContext.getString(CoreR.string.jrwi_audio) })
                .setContentText(
                    if (total != null && total > 0) {
                        "${Formatter.formatShortFileSize(appContext, done)} / " +
                            Formatter.formatShortFileSize(appContext, total)
                    } else {
                        Formatter.formatShortFileSize(appContext, done)
                    }
                )
                .setProgress(100, percent(done, total), total == null || total <= 0)
                .setSubText(waitingText(itemId))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openAppIntent())
                .build()
                .also { ensureNotificationChannel() },
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    // How many more wait in the queue after this one, or null when none do
    private fun waitingText(itemId: Long): String? {
        val waiting =
            downloads.downloads.value.count { !it.done && !it.failed && it.itemId != itemId }
        if (waiting == 0) return null
        return appContext.resources.getQuantityString(
            CoreR.plurals.jrwi_downloads_waiting,
            waiting,
            waiting,
        )
    }

    // A download taken off the phone while it ran
    private class Removed : Exception()

    private fun percent(done: Long, total: Long?): Int =
        if (total != null && total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0

    private fun openAppIntent(): PendingIntent? {
        val intent =
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                ?: return null
        return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    // The same channel as the other downloads
    private fun ensureNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                appContext.getString(CoreR.string.title_download),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        private const val KEY_ITEM_ID = "jrwiItemId"
        private const val KEY_FILES_ONLY = "filesOnly"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        private const val NOTIFICATION_CHANNEL_ID = "downloads"
        private const val PARTIAL_CONTENT = 206
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 64 * 1024
        // As often as the video downloads update theirs: each update redraws the notification
        private const val PROGRESS_INTERVAL_MS = 1000L
        private const val MAX_COMMENT_PAGES = 20
        private const val NOT_FOUND = 404

        // The one queue every JRWI download waits in, run one at a time
        private const val QUEUE = "jrwi_downloads"
        private const val QUEUE_PREFS = "jrwi_download_queue"
        private const val KEY_MOVED = "moved"

        private fun workName(itemId: Long) = "jrwi_download_$itemId"

        /** Puts the episode's download at the end of the queue. */
        fun enqueue(context: Context, itemId: Long) {
            val request =
                OneTimeWorkRequestBuilder<JrwiDownloadWorker>()
                    .setInputData(workDataOf(KEY_ITEM_ID to itemId))
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(workName(itemId))
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Moves the downloads that were waiting as jobs of their own, which ran all at once (before
         * 1.2.0-custom.129), into the queue. Once, away from the main thread.
         */
        fun moveToQueue(context: Context, downloads: JrwiDownloads) {
            val prefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MOVED, false)) return
            prefs.edit().putBoolean(KEY_MOVED, true).apply()
            thread(name = "jrwi-download-queue") {
                val workManager = WorkManager.getInstance(context)
                for (download in downloads.downloads.value) {
                    workManager.cancelUniqueWork(workName(download.itemId))
                    if (!download.done && !download.failed) enqueue(context, download.itemId)
                }
                for (video in downloads.savedVideos()) {
                    workManager.cancelUniqueWork(filesWorkName(video.item.id))
                    if (downloads.videoKeepsFiles(video.item.id)) {
                        enqueueFiles(context, video.item.id)
                    }
                }
            }
        }

        private fun filesWorkName(itemId: Long) = "jrwi_files_$itemId"

        /** Keeps the post's files for the video's download, apart from the audio's download. */
        fun enqueueFiles(context: Context, itemId: Long) {
            val request =
                OneTimeWorkRequestBuilder<JrwiDownloadWorker>()
                    .setInputData(workDataOf(KEY_ITEM_ID to itemId, KEY_FILES_ONLY to true))
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(filesWorkName(itemId))
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** Starts and removes JRWI downloads with [JrwiDownloadWorker]. */
class JrwiDownloaderImpl(private val context: Context, private val downloads: JrwiDownloads) :
    JrwiDownloader {
    override fun download(item: JrwiItem, withFiles: Boolean, showName: String?) {
        val audio = item.audio ?: return
        val download =
            downloads.get(item.id)
                ?: JrwiDownload(
                    itemId = item.id,
                    audioPostId = audio.postId,
                    addedAt = System.currentTimeMillis(),
                )
        downloads.put(
            download.copy(
                title = item.title,
                showName = showName?.takeIf { it.isNotBlank() } ?: download.showName,
                mime = audio.mime,
                failed = false,
                withFiles = withFiles,
            )
        )
        // Files kept by an earlier download go, unless the video's download keeps them
        if (!withFiles) downloads.dropUnwantedFiles(item.id)
        JrwiDownloadWorker.enqueue(context, item.id)
    }

    // Its turn in the queue is skipped, or a running download stops where it is: cancelling it
    // would cancel every download queued after it too
    override fun remove(itemId: Long) {
        downloads.delete(itemId)
    }

    override fun refresh(itemId: Long) {
        val download = downloads.get(itemId) ?: return
        if (download.done && download.withFiles) JrwiDownloadWorker.enqueue(context, itemId)
    }

    override fun keepFilesForVideo(itemId: Long) {
        JrwiDownloadWorker.enqueueFiles(context, itemId)
    }

    override fun forgetVideo(itemId: Long) {
        downloads.deleteVideo(itemId)
    }
}
