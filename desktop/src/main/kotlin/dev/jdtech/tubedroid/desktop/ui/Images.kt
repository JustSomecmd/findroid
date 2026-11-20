package dev.jdtech.tubedroid.desktop.ui

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.jdtech.jellyfin.tubearchivist.TaApi
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Sets up image loading: pictures from TubeArchivist get its token, as it asks for one, and are
 * kept on disk so pages open quickly the next time.
 */
fun installImageLoader(api: TaApi) {
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val base = api.baseUrl?.toHttpUrlOrNull()
                val header = api.authHeader
                // The port counts too, as Jellyfin is often on the same computer
                val sameServer =
                    base != null && request.url.host == base.host && request.url.port == base.port
                if (sameServer && header != null) {
                    chain.proceed(request.newBuilder().header("Authorization", header).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    val cacheFolder =
        File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
            "Winchivist/images",
        )
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheFolder.toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
