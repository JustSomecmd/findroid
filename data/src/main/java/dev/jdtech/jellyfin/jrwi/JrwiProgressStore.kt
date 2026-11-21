package dev.jdtech.jellyfin.jrwi

import android.content.Context
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Progress of JRWI episodes as saved from this phone: the last of each episode, for when the
 * archiver can't be reached, and saves that haven't reached it yet, sent later with the time they
 * were made.
 */
class JrwiProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** The last progress of an episode saved from this phone, in the timeline it was saved in. */
    fun last(itemId: Long): JrwiProgress? = read(LAST + itemId)

    fun remember(itemId: Long, progress: JrwiProgress) = write(LAST + itemId, progress)

    /** Saves that haven't reached the archiver yet, by item ID: the newest of each episode. */
    fun pending(): Map<Long, JrwiProgress> =
        prefs.all.keys
            .filter { it.startsWith(PENDING) }
            .mapNotNull { key ->
                val itemId = key.removePrefix(PENDING).toLongOrNull() ?: return@mapNotNull null
                read(key)?.let { itemId to it }
            }
            .toMap()

    fun pending(itemId: Long): JrwiProgress? = read(PENDING + itemId)

    fun setPending(itemId: Long, progress: JrwiProgress) = write(PENDING + itemId, progress)

    /** Drops a waiting save once sent, unless a newer one has come since [sent] was read. */
    @Synchronized
    fun clearPending(itemId: Long, sent: JrwiProgress? = null) {
        if (sent != null && pending(itemId)?.updatedAt != sent.updatedAt) return
        prefs.edit().remove(PENDING + itemId).apply()
    }

    private fun read(key: String): JrwiProgress? {
        val text = prefs.getString(key, null) ?: return null
        return try {
            json.decodeFromString(JrwiProgress.serializer(), text)
        } catch (e: Exception) {
            Timber.w(e, "Could not read saved JRWI progress $key")
            null
        }
    }

    @Synchronized
    private fun write(key: String, progress: JrwiProgress) {
        prefs
            .edit()
            .putString(key, json.encodeToString(JrwiProgress.serializer(), progress))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "jrwi_progress"
        private const val LAST = "last_"
        private const val PENDING = "pending_"

        // With "+00:00" rather than "Z", as ISO 8601 allows and the archiver reads
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

        /** Now, in UTC, as the archiver writes its times. */
        fun now(): String =
            OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(TIME_FORMAT)
    }
}
