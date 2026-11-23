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
import java.io.File
import java.io.IOException
import java.util.UUID
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
 * Every item on the server is remembered by its ID, which Jellyfin makes from the file's path, so
 * only an item the server never had before is announced. Jellyfin's own "date added", which picks
 * the newest items to look at, moves an old item to the front whenever its file changes on disk (a
 * TubeArchivist reindex, a file moved between disks), so it can't tell new from old by itself.
 *
 * The first check only remembers what is on the server, so turning this on doesn't announce the
 * whole library.
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

        // The newest episodes and movies, by the server's "date added"
        val newest =
            try {
                jellyfinApi.libraryApi
                    .getItems(
                        user.id,
                        includeItemTypes = ITEM_TYPES,
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
        val newestIds = newest.map { it.id.toString() }

        val key = "$serverId:${user.id}"
        val now = System.currentTimeMillis()
        val known = loadKnown(key)

        // The first check for this account, or the first since every item is remembered rather
        // than only the newest: remember the whole library, and announce nothing
        if (known == null) {
            val all = allIds(jellyfinApi, user.id) ?: return Result.success()
            saveKnown(key, all + newestIds, syncedAt = now)
            return Result.success()
        }

        val added = newest.filter { it.id.toString() !in known }
        if (added.isNotEmpty() && appPreferences.getValue(appPreferences.newItemNotifications)) {
            showNotification(added)
        }

        // Once a week the whole library is read again, so deleted items are let go of and items
        // from a batch too big for one check are remembered too
        val syncedAt = syncedAt(key)
        val all = if (now - syncedAt >= RESYNC_MS) allIds(jellyfinApi, user.id) else null
        // Written only when something changed, as the list can run to hundreds of kilobytes
        if (all != null || added.isNotEmpty()) {
            saveKnown(
                key,
                (all ?: known) + newestIds,
                syncedAt = if (all != null) now else syncedAt,
            )
        }
        return Result.success()
    }

    /** Every episode and movie on the server, by ID, read a page at a time; null if that failed. */
    private suspend fun allIds(jellyfinApi: JellyfinApi, userId: UUID): Set<String>? =
        try {
            val ids = HashSet<String>()
            var start = 0
            while (start < MAX_ITEMS) {
                val page =
                    jellyfinApi.libraryApi
                        .getItems(
                            userId,
                            includeItemTypes = ITEM_TYPES,
                            recursive = true,
                            // Oldest first, so what's added while it reads goes at the end
                            sortBy = listOf(ItemSortBy.DATE_CREATED),
                            sortOrder = listOf(SortOrder.ASCENDING),
                            startIndex = start,
                            limit = PAGE_SIZE,
                            enableImages = false,
                            enableUserData = false,
                            enableTotalRecordCount = false,
                        )
                        .content
                        .items
                page.forEach { ids += it.id.toString() }
                if (page.size < PAGE_SIZE) break
                start += PAGE_SIZE
            }
            ids
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tried again at the next check
            Timber.w(e, "Could not read the library for new items")
            null
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

    // One file of IDs per server and account, as there can be tens of thousands
    private fun knownFile(key: String): File =
        File(File(context.noBackupFilesDir, KNOWN_DIR), key.replace(Regex("[^A-Za-z0-9-]"), "_"))

    /** The IDs of every item the server has had, or null before the whole library was read. */
    private fun loadKnown(key: String): Set<String>? {
        val file = knownFile(key)
        if (!file.exists()) return null
        return try {
            file.readLines().filterTo(HashSet()) { it.isNotBlank() }
        } catch (e: IOException) {
            Timber.w(e, "Could not read the known items")
            null
        }
    }

    /** When the whole library was last read, in milliseconds. */
    private fun syncedAt(key: String): Long = prefs().getLong("$key:synced", 0L)

    private fun saveKnown(key: String, ids: Set<String>, syncedAt: Long) {
        val file = knownFile(key)
        try {
            file.parentFile?.mkdirs()
            // Written beside it first, so a check stopped halfway leaves the old list whole
            val next = File(file.parentFile, file.name + ".new")
            next.writeText(ids.joinToString("\n"))
            if (!next.renameTo(file)) {
                file.delete()
                next.renameTo(file)
            }
        } catch (e: IOException) {
            Timber.w(e, "Could not save the known items")
            return
        }
        // The list of the newest IDs that came before is no longer used
        prefs().edit().putLong("$key:synced", syncedAt).remove(key).commit()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val WORK_NAME = "new_items_check"
        private const val CHANNEL_ID = "new_items"
        private const val PREFS_NAME = "new_items"
        private val ITEM_TYPES = listOf(BaseItemKind.EPISODE, BaseItemKind.MOVIE)
        // How many of the newest items each check looks at
        private const val CHECK_LIMIT = 100
        // Reading the whole library, a page at a time, up to this many items
        private const val PAGE_SIZE = 1000
        private const val MAX_ITEMS = 200_000
        private const val RESYNC_MS = 7L * 24 * 60 * 60 * 1000
        private const val KNOWN_DIR = "new_items"
        // Lines shown in the expanded notification
        private const val MAX_LINES = 6
    }
}
