package dev.jdtech.tubedroid.desktop

import androidx.compose.runtime.mutableStateListOf
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A video being saved to this computer, or saved already. */
data class LocalDownload(
    val videoId: String,
    val title: String,
    val file: File,
    /** From 0 to 1, or null while the size isn't known. */
    val progress: Float? = null,
    val state: State = State.RUNNING,
    val error: String? = null,
) {
    enum class State {
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
    }
}

/**
 * Saves video files from TubeArchivist straight into a folder on this computer, such as Downloads.
 * Nothing more: they aren't tracked for playing offline.
 */
class Downloads(private val settings: AppSettings, private val api: TaApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    private val jobs = mutableMapOf<String, Job>()

    /** This session's downloads, newest first. */
    val items = mutableStateListOf<LocalDownload>()

    fun stateOf(videoId: String): LocalDownload? = items.firstOrNull { it.videoId == videoId }

    fun start(video: TaVideo) {
        val url = api.mediaUrl(video.mediaUrl) ?: return
        if (jobs[video.youtubeId]?.isActive == true) return
        val extension =
            video.mediaUrl?.substringAfterLast('.', "mp4")?.takeIf { it.length <= 4 } ?: "mp4"
        val folder = File(settings.downloadFolder).also { it.mkdirs() }
        val file = File(folder, "${safeName(video.title)} [${video.youtubeId}].$extension")
        update(LocalDownload(video.youtubeId, video.title, file))
        jobs[video.youtubeId] = scope.launch {
            try {
                withContext(Dispatchers.IO) { save(url, file, video.youtubeId) }
                update(
                    stateOf(video.youtubeId)!!.copy(progress = 1f, state = LocalDownload.State.DONE)
                )
            } catch (e: CancellationException) {
                file.delete()
                stateOf(video.youtubeId)?.let {
                    update(it.copy(state = LocalDownload.State.CANCELLED))
                }
                throw e
            } catch (e: Exception) {
                file.delete()
                stateOf(video.youtubeId)?.let {
                    update(
                        it.copy(
                            state = LocalDownload.State.FAILED,
                            error = e.message ?: e.toString(),
                        )
                    )
                }
            }
        }
    }

    /** Looks the video up first, for when only its ID is at hand, such as in a playlist. */
    fun startById(videoId: String) {
        if (jobs[videoId]?.isActive == true) return
        scope.launch {
            try {
                start(api.getVideo(videoId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(
                    LocalDownload(
                        videoId = videoId,
                        title = videoId,
                        file = File(settings.downloadFolder),
                        state = LocalDownload.State.FAILED,
                        error = e.message ?: e.toString(),
                    )
                )
            }
        }
    }

    fun cancel(videoId: String) {
        jobs.remove(videoId)?.cancel()
    }

    /** Takes a finished, failed or cancelled download off the list. The file stays. */
    fun forget(videoId: String) {
        items.removeAll { it.videoId == videoId && it.state != LocalDownload.State.RUNNING }
    }

    private suspend fun save(url: String, file: File, videoId: String) {
        val request =
            Request.Builder()
                .url(url)
                .apply { api.authHeader?.let { header("Authorization", it) } }
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful)
                throw IOException("TubeArchivist answered with ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }
            val partial = File(file.parentFile, file.name + ".part")
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastShown = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (total != null && now - lastShown > 250) {
                            lastShown = now
                            val progress = done.toFloat() / total
                            withContext(Dispatchers.Main) {
                                stateOf(videoId)?.let { update(it.copy(progress = progress)) }
                            }
                        }
                    }
                }
            }
            if (file.exists()) file.delete()
            if (!partial.renameTo(file)) throw IOException("Couldn't save ${file.name}")
        }
    }

    private fun update(download: LocalDownload) {
        val index = items.indexOfFirst { it.videoId == download.videoId }
        if (index >= 0) items[index] = download else items.add(0, download)
    }

    /** A file name without the characters Windows doesn't allow. */
    private fun safeName(title: String): String =
        title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(150)
            .ifBlank { "video" }
}
