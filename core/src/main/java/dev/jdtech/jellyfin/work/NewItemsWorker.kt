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
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber

/**
 * Looks for episodes and movies that were added to the server since the last check, and sends a
 * notification naming the show of each episode and each movie. Runs every 15 minutes, the shortest
 * interval Android allows for background work.
 *
 * The first check only remembers what is on the server, so turning this on doesn't announce the
 * whole library. Items that came out more than two weeks before they turned up aren't announced
 * either: a channel's older videos that TubeArchivist downloads now, the JRWI archiver filling in
 * its past episodes, or files that moved and came back as new items.
 */
@HiltWorker
class NewItemsWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (appPreferences.getValue(appPreferences.offlineMode)) return Result.success()
        val serverId =
            appPreferences.getValue(appPreferences.currentServer) ?: return Result.success()
        val address = database.getServerCurrentAddress(serverId) ?: return Result.success()
        val user = database.getServerCurrentUser(serverId) ?: return Result.success()
        val accessToken = user.accessToken ?: return Result.success()

        val jellyfinApi =
            JellyfinApi(
                androidContext = context.applicationContext,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )
        jellyfinApi.api.update(baseUrl = address.address, accessToken = accessToken)
        jellyfinApi.userId = user.id

        // The newest episodes and movies, by when they were added to the server
        val newest =
            try {
                jellyfinApi.libraryApi
                    .getItems(
                        user.id,
                        includeItemTypes = listOf(BaseItemKind.EPISODE, BaseItemKind.MOVIE),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        limit = CHECK_LIMIT,
                        enableImages = false,
                        enableUserData = false,
                    )
                    .content
                    .items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next check tries again
                Timber.w(e, "Could not check for new items")
                return Result.success()
            }

        val key = "$serverId:${user.id}"
        val seen = loadSeen(key)
        val newestIds = newest.map { it.id.toString() }
        saveSeen(key, (newestIds + seen.orEmpty()).distinct().take(MAX_SEEN))

        // First check for this account: only remember what is there
        if (seen == null) return Result.success()

        // Only what came out lately, going by its release date (a video's upload date, an
        // archived episode's air date); one without a date counts as new
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(RECENT_DAYS)
        val added = newest.filter { item ->
            item.id.toString() !in seen && item.premiereDate?.isBefore(cutoff) != true
        }
        if (added.isNotEmpty() && appPreferences.getValue(appPreferences.newItemNotifications)) {
            showNotification(added)
        }
        return Result.success()
    }

    private fun showNotification(added: List<BaseItemDto>) {
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
                context.getString(CoreR.string.new_items_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )

        val hideNumbers = appPreferences.getValue(appPreferences.hideEpisodeNumbers)
        val single = added.singleOrNull()
        val title: String
        val text: String
        when {
            single != null && single.type == BaseItemKind.EPISODE -> {
                title = context.getString(CoreR.string.new_episode_of, single.seriesName.orEmpty())
                text = episodeLabel(single, hideNumbers)
            }
            single != null -> {
                title = context.getString(CoreR.string.new_movie)
                text = single.name.orEmpty()
            }
            else -> {
                title =
                    context.resources.getQuantityString(
                        CoreR.plurals.new_items_count,
                        added.size,
                        added.size,
                    )
                // Each show and movie once, newest first
                text = added.map { showOrMovieName(it) }.distinct().joinToString(", ")
            }
        }

        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(CoreR.drawable.ic_film)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
        if (single == null) {
            // Expanded: one line per item, naming its show
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            added.take(MAX_LINES).forEach { item ->
                val line =
                    if (item.type == BaseItemKind.EPISODE) {
                        "${item.seriesName.orEmpty()}: ${episodeLabel(item, hideNumbers)}"
                    } else {
                        item.name.orEmpty()
                    }
                style.addLine(line)
            }
            if (added.size > MAX_LINES) {
                style.setSummaryText(
                    context.getString(CoreR.string.new_items_more, added.size - MAX_LINES)
                )
            }
            builder.setStyle(style)
        }

        // A new notification per check, so earlier ones that weren't looked at yet stay
        val id = (System.currentTimeMillis() / 1000 % Int.MAX_VALUE).toInt()
        notificationManager.notify(id, builder.build())
    }

    private fun showOrMovieName(item: BaseItemDto): String =
        if (item.type == BaseItemKind.EPISODE) item.seriesName.orEmpty() else item.name.orEmpty()

    private fun episodeLabel(item: BaseItemDto, hideNumbers: Boolean): String {
        val name = item.name.orEmpty()
        val season = item.parentIndexNumber
        val episode = item.indexNumber
        return if (hideNumbers || season == null || episode == null) {
            name
        } else {
            context.getString(CoreR.string.new_episode_label, season, episode, name)
        }
    }

    private fun openAppIntent(): PendingIntent? {
        val intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** The ids seen in earlier checks, newest first, or null before the first check. */
    private fun loadSeen(key: String): List<String>? =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }

    private fun saveSeen(key: String, ids: List<String>) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, ids.joinToString(","))
            .apply()
    }

    companion object {
        const val WORK_NAME = "new_items_check"
        private const val CHANNEL_ID = "new_items"
        private const val PREFS_NAME = "new_items"
        // How many of the newest items each check looks at
        private const val CHECK_LIMIT = 50
        // How many ids are remembered, so items that drop out of the newest and come back aren't
        // announced again
        private const val MAX_SEEN = 500
        // Lines shown in the expanded notification
        private const val MAX_LINES = 6
        // How long after its release an item still counts as new
        private const val RECENT_DAYS = 14L
    }
}
