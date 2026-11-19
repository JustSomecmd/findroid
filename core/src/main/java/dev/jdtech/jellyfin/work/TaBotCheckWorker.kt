package dev.jdtech.jellyfin.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaTaskResult
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * Looks at the results TubeArchivist keeps of its background tasks, and sends a notification when
 * YouTube's bot detection stopped one, such as the download queue. TubeArchivist itself only shows
 * this for about 20 seconds in its web interface. Runs every 15 minutes, the shortest interval
 * Android allows for background work. The server keeps task results for a day.
 *
 * Failures from before the first check aren't announced, so turning this on doesn't bring up old
 * news.
 */
@HiltWorker
class TaBotCheckWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val taApi: TaApi,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!appPreferences.getValue(appPreferences.tubeArchivistBotNotifications)) {
            return Result.success()
        }
        val base = taApi.baseUrl ?: return Result.success()
        if (!taApi.isConfigured) return Result.success()

        // Failures from before the first check for this server are old news. Kept before asking
        // the server, as it answers with an error while it has no task results at all
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sinceKey = "$base:since"
        val firstCheck = !prefs.contains(sinceKey)
        if (firstCheck) prefs.edit().putLong(sinceKey, System.currentTimeMillis()).apply()
        val since = prefs.getLong(sinceKey, 0L)

        val results =
            try {
                taApi.getTaskResults()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No results yet, no admin rights or offline. The next check tries again
                Timber.w(e, "Could not check TubeArchivist task results")
                return Result.success()
            }

        val seen = loadSeen(base)
        val botFailures = results.filter { it.taskId.isNotEmpty() && it.isBotDetection() }
        saveSeen(base, (botFailures.map { it.taskId } + seen).distinct().take(MAX_SEEN))

        val new = botFailures.filter { result ->
            result.taskId !in seen && (finishedAt(result)?.let { it >= since } ?: !firstCheck)
        }
        if (new.isNotEmpty()) showNotification(new)
        return Result.success()
    }

    private fun TaTaskResult.isBotDetection(): Boolean {
        val text = listOfNotNull(result, traceback).joinToString("\n")
        return BOT_MESSAGES.any { text.contains(it, ignoreCase = true) }
    }

    private fun taskLabel(name: String): String =
        context.getString(
            when (name) {
                "download_pending" -> CoreR.string.ta_bot_task_download
                "update_subscribed" -> CoreR.string.ta_bot_task_rescan
                "extract_download" -> CoreR.string.ta_bot_task_add
                "check_reindex" -> CoreR.string.ta_bot_task_reindex
                "subscribe_to" -> CoreR.string.ta_bot_task_subscribe
                else -> CoreR.string.ta_bot_task_other
            }
        )

    private fun finishedAt(result: TaTaskResult): Long? {
        val text = result.dateDone ?: return null
        // Celery writes UTC, with or without the offset depending on its version
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun showNotification(failures: List<TaTaskResult>) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(CoreR.string.ta_bot_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        // The latest failure names its task
        val latest = failures.maxByOrNull { finishedAt(it) ?: 0L } ?: return
        val text = context.getString(CoreR.string.ta_bot_text, taskLabel(latest.name))

        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_bot_alert)
                .setContentTitle(context.getString(CoreR.string.ta_bot_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
        finishedAt(latest)?.let { builder.setWhen(it).setShowWhen(true) }

        // One notification that a newer failure replaces
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun openAppIntent(): PendingIntent? {
        val intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** The task ids seen in earlier checks, newest first. */
    private fun loadSeen(key: String): List<String> =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun saveSeen(key: String, ids: List<String>) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, ids.joinToString(","))
            .apply()
    }

    companion object {
        const val WORK_NAME = "ta_bot_check"
        private const val CHANNEL_ID = "ta_bot_detection"
        private const val PREFS_NAME = "ta_bot_check"
        private const val NOTIFICATION_ID = 4210
        private const val MAX_SEEN = 200
        // TubeArchivist stops a task with "YouTube bot detection, abort!" when yt-dlp reports
        // YouTube's "Sign in to confirm you're not a bot"
        private val BOT_MESSAGES = listOf("bot detection", "not a bot")
    }
}
