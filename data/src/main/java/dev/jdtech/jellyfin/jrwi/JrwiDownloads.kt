package dev.jdtech.jellyfin.jrwi

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/** An episode whose audio is kept on the phone, to listen to without a connection. */
@Serializable
data class JrwiDownload(
    val itemId: Long,
    val audioPostId: Long,
    val title: String = "",
    val showName: String? = null,
    val mime: String? = null,
    /** The audio's size and ETag as the archiver gave them, to tell when it has a new copy. */
    val size: Long? = null,
    val etag: String? = null,
    /** All of the audio is on the phone. */
    val done: Boolean = false,
    /** The archiver refused it, so it won't be tried again by itself. */
    val failed: Boolean = false,
    /** When it was asked for, so the newest come first. */
    val addedAt: Long = 0,
)

/** Starts and removes downloads of episodes' audio, which run in the background. */
interface JrwiDownloader {
    /** Downloads the audio of [item], with what's needed to show its page without a connection. */
    fun download(item: JrwiItem)

    /** Stops a download, if it's still going, and takes the episode off the phone. */
    fun remove(itemId: Long)
}

/**
 * The episodes whose audio is kept on the phone. Each has a folder of its own with the audio, its
 * picture, the episode as the archiver gave it, the audio's own post and its comments, so its page
 * and its audio both work without a connection.
 */
class JrwiDownloads(context: Context) {
    private val dir = File(context.filesDir, "jrwi")
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val _downloads = MutableStateFlow(readAll())

    /** Every episode on the phone or on its way, newest first. */
    val downloads: StateFlow<List<JrwiDownload>> = _downloads.asStateFlow()

    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** How far each running download has got, from 0 to 1, by item ID. */
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    fun get(itemId: Long): JrwiDownload? = _downloads.value.firstOrNull { it.itemId == itemId }

    /** The finished download whose audio is the post [audioPostId]. */
    fun forAudio(audioPostId: Long): JrwiDownload? =
        _downloads.value.firstOrNull { it.audioPostId == audioPostId && it.done }

    /** The download of an episode, by any of its post IDs that a page or the player has. */
    fun forPost(postId: Long): JrwiDownload? =
        get(postId) ?: _downloads.value.firstOrNull { it.audioPostId == postId }

    /** The episode's picture on the phone, as an address an image loader takes. */
    fun thumbnailUri(itemId: Long): String? =
        thumbnailFile(itemId).takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }

    fun audioFile(itemId: Long): File = File(folder(itemId), AUDIO)

    /** Where the audio goes while it downloads, so a stopped download can go on. */
    fun partFile(itemId: Long): File = File(folder(itemId), "$AUDIO.part")

    fun thumbnailFile(itemId: Long): File = File(folder(itemId), THUMBNAIL)

    /** The episode as it was when downloaded, with its picture from the phone. */
    fun savedItem(itemId: Long): JrwiItem? {
        val item = read(itemId, ITEM, JrwiItem.serializer()) ?: return null
        return item.copy(thumbnail = thumbnailUri(itemId) ?: item.thumbnail)
    }

    /** The audio's own post, with its description. */
    fun savedPost(itemId: Long): JrwiItem? = read(itemId, POST, JrwiItem.serializer())

    /** The comments on the audio's own post. */
    fun savedComments(itemId: Long): List<JrwiComment> =
        read(itemId, COMMENTS, ListSerializer(JrwiComment.serializer())).orEmpty()

    /** Keeps what's needed to show the episode's page without a connection. */
    fun saveDetails(itemId: Long, item: JrwiItem, post: JrwiItem?, comments: List<JrwiComment>) {
        write(itemId, ITEM, json.encodeToString(JrwiItem.serializer(), item))
        post?.let { write(itemId, POST, json.encodeToString(JrwiItem.serializer(), it)) }
        write(
            itemId,
            COMMENTS,
            json.encodeToString(
                ListSerializer(JrwiComment.serializer()),
                comments,
            ),
        )
    }

    /** Adds a download that's about to start, or changes one. */
    @Synchronized
    fun put(download: JrwiDownload) {
        write(download.itemId, STATE, json.encodeToString(JrwiDownload.serializer(), download))
        _downloads.update { list ->
            (list.filter { it.itemId != download.itemId } + download).sortedByDescending {
                it.addedAt
            }
        }
    }

    /** Changes a download that's still listed; one removed meanwhile stays removed. */
    @Synchronized
    fun update(itemId: Long, change: (JrwiDownload) -> JrwiDownload): JrwiDownload? {
        val download = get(itemId)?.let(change) ?: return null
        put(download)
        return download
    }

    fun setProgress(itemId: Long, fraction: Float?) {
        _progress.update { if (fraction == null) it - itemId else it + (itemId to fraction) }
    }

    /** Takes an episode off the phone. */
    @Synchronized
    fun delete(itemId: Long) {
        folder(itemId).deleteRecursively()
        _downloads.update { list -> list.filter { it.itemId != itemId } }
        setProgress(itemId, null)
    }

    private fun folder(itemId: Long) = File(dir, itemId.toString())

    private fun readAll(): List<JrwiDownload> =
        dir.listFiles()
            ?.mapNotNull { folder ->
                folder.name.toLongOrNull()?.let { read(it, STATE, JrwiDownload.serializer()) }
            }
            ?.sortedByDescending { it.addedAt }
            .orEmpty()

    private fun <T> read(
        itemId: Long,
        name: String,
        serializer: KSerializer<T>,
    ): T? {
        val file = File(folder(itemId), name)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            Timber.w(e, "Could not read $name of JRWI download $itemId")
            null
        }
    }

    // Through a file of its own first, so a half-written file never replaces a whole one
    private fun write(itemId: Long, name: String, text: String) {
        val folder = folder(itemId)
        folder.mkdirs()
        val temp = File(folder, "$name.tmp")
        temp.writeText(text)
        if (!temp.renameTo(File(folder, name))) {
            File(folder, name).writeText(text)
            temp.delete()
        }
    }

    private companion object {
        const val STATE = "download.json"
        const val ITEM = "item.json"
        const val POST = "post.json"
        const val COMMENTS = "comments.json"
        const val AUDIO = "audio"
        const val THUMBNAIL = "thumbnail"
    }
}
