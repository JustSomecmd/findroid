package dev.jdtech.jellyfin.audio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import dev.jdtech.jellyfin.jrwi.JrwiApi

/**
 * Loads the pictures of the audio player's media session, for its notification, the lock screen and
 * the media controls of the phone and other apps. The session shares a picture's address with those
 * apps, so the address the session holds has no token in it. The archiver's pictures need one,
 * which is added here, inside the app, and only for the archiver's own address.
 */
@OptIn(UnstableApi::class)
internal class ArchiverBitmapLoader(context: Context, private val jrwiApi: JrwiApi) : BitmapLoader {
    private val loader = CacheBitmapLoader(DataSourceBitmapLoader(context))

    override fun supportsMimeType(mimeType: String): Boolean = loader.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = loader.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        loader.loadBitmap(jrwiApi.withToken(uri.toString()).toUri())

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let {
            return decodeBitmap(it)
        }
        return metadata.artworkUri?.let { loadBitmap(it) }
    }
}
