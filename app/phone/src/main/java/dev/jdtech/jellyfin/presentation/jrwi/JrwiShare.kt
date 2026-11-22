package dev.jdtech.jellyfin.presentation.jrwi

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Opens one of the archive's files in another app, such as a PDF reader, without handing that app
 * the archiver's token: the file is taken from the copy kept with a download, or fetched into the
 * app, then copied under its own name to a folder that's only reached through this app's file
 * provider, and handed over by an address the other app may only read, and only that file.
 */
internal fun openJrwiFileElsewhere(
    context: Context,
    scope: CoroutineScope,
    file: JrwiPostFile,
    fileOnPhone: suspend (JrwiPostFile) -> File,
) {
    scope.launch {
        // A note while it's fetched, when that takes a moment
        val note = launch {
            delay(FETCH_NOTE_DELAY_MILLIS)
            Toast.makeText(
                    context,
                    context.getString(CoreR.string.jrwi_file_getting, file.name),
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
        val shared =
            try {
                val source = fileOnPhone(file)
                withContext(Dispatchers.IO) { sharedCopy(context, source, file.name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.localizedMessage ?: file.name, Toast.LENGTH_LONG).show()
                return@launch
            } finally {
                note.cancel()
            }
        val uri = FileProvider.getUriForFile(context, context.packageName + SHARE_AUTHORITY, shared)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file.mime, shared))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, CoreR.string.jrwi_file_no_app, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * The file in the shared folder under [name], for the app that opens it to show. Only the newest is
 * kept there: what was shared before is cleared, as the app it went to has read it by then.
 */
private fun sharedCopy(context: Context, source: File, name: String): File {
    val dir = File(context.cacheDir, SHARE_DIR)
    val target = File(dir, safeFileName(name))
    dir.listFiles()?.forEach { if (it != target) it.deleteRecursively() }
    dir.mkdirs()
    if (!target.isFile || target.length() != source.length()) {
        source.copyTo(target, overwrite = true)
    }
    return target
}

private fun mimeOf(mime: String?, file: File): String =
    mime?.takeIf { it.isNotBlank() }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: "application/octet-stream"

/**
 * A name a file can have in a folder on the phone: no folder parts, nothing Android's file systems
 * refuse, not hidden, and not too long, with its extension kept.
 */
internal fun safeFileName(name: String): String {
    val cleaned =
        name.replace(UNSAFE_FILE_CHARACTERS, "_").trim().trimStart('.', ' ').ifBlank { "file" }
    if (cleaned.length <= MAX_FILE_NAME) return cleaned
    val extension = cleaned.substringAfterLast('.', "").take(MAX_EXTENSION)
    val base = cleaned.take(MAX_FILE_NAME - extension.length - 1)
    return if (extension.isEmpty()) cleaned.take(MAX_FILE_NAME) else "$base.$extension"
}

/**
 * Saves a file from the archiver to the phone through Android's download manager, at [subPath]
 * under [directory]. The archiver's token goes in the request's header, not in the address the
 * download manager keeps and shows.
 */
internal fun enqueueJrwiDownload(
    context: Context,
    url: String,
    title: String,
    directory: String,
    subPath: String,
) {
    val parsed = url.toHttpUrlOrNull()
    val token = parsed?.queryParameter("token")
    val address =
        if (token != null) parsed.newBuilder().removeAllQueryParameters("token").build() else parsed
    val request =
        DownloadManager.Request((address?.toString() ?: url).toUri())
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(directory, subPath)
    if (token != null) request.addRequestHeader("Authorization", "Token $token")
    context.getSystemService(DownloadManager::class.java).enqueue(request)
}

// Under the app's cache, as res/xml/jrwi_shared_files.xml names it for the file provider
private const val SHARE_DIR = "shared"
// The file provider's address, after the app's package name, as the manifest gives it
private const val SHARE_AUTHORITY = ".files"
private const val FETCH_NOTE_DELAY_MILLIS = 400L
private const val MAX_FILE_NAME = 120
private const val MAX_EXTENSION = 10
private val UNSAFE_FILE_CHARACTERS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
