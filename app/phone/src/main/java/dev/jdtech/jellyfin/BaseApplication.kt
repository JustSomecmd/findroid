package dev.jdtech.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.work.NewItemsWorker
import dev.jdtech.jellyfin.work.SyncWorker
import dev.jdtech.jellyfin.work.TaBotCheckWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@HiltAndroidApp
class BaseApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val mode =
                when (appPreferences.getValue(appPreferences.theme)) {
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        if (appPreferences.getValue(appPreferences.dynamicColors)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        val workManager = WorkManager.getInstance(applicationContext)

        scheduleUserDataSync(workManager)
        scheduleNewItemsCheck(workManager)
        scheduleTaBotCheck(workManager)
        // Turning the setting on or off takes effect right away
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            newItemsSettingListener
        )
    }

    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageClient },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
                add(SvgDecoder.Factory())
            }
            .diskCachePolicy(
                if (appPreferences.getValue(appPreferences.imageCache)) CachePolicy.ENABLED
                else CachePolicy.DISABLED
            )
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize) * 1024L * 1024
                    )
                    .build()
            }
            .crossfade(true)
            .build()
    }

    // Images from TubeArchivist and the JRWI archiver need their API tokens
    private val imageClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val token =
                    tokenFor(
                        request.url,
                        appPreferences.tubeArchivistUrl,
                        appPreferences.tubeArchivistToken,
                    ) ?: tokenFor(request.url, appPreferences.jrwiUrl, appPreferences.jrwiToken)
                if (token != null) {
                    chain.proceed(
                        request.newBuilder().header("Authorization", "Token $token").build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }

    /** The token set for the server at [url]'s address, if it's that server. */
    private fun tokenFor(
        url: HttpUrl,
        address: Preference<String?>,
        token: Preference<String?>,
    ): String? {
        val base = appPreferences.getValue(address)?.trim()?.toHttpUrlOrNull() ?: return null
        val value =
            appPreferences.getValue(token)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sameServer = url.host == base.host && url.port == base.port && url.scheme == base.scheme
        return value.takeIf { sameServer }
    }

    private fun scheduleUserDataSync(workManager: WorkManager) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val syncWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "syncUserData",
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = syncWorkRequest,
        )
    }

    // Kept here, as the preferences only hold a weak reference to their listeners
    private val newItemsSettingListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.newItemNotifications.backendName) {
                scheduleNewItemsCheck(WorkManager.getInstance(applicationContext))
            }
            if (key == appPreferences.tubeArchivistBotNotifications.backendName) {
                scheduleTaBotCheck(WorkManager.getInstance(applicationContext))
            }
        }

    /** Checks the server for new episodes and movies every 15 minutes while the setting is on. */
    private fun scheduleNewItemsCheck(workManager: WorkManager) {
        if (!appPreferences.getValue(appPreferences.newItemNotifications)) {
            workManager.cancelUniqueWork(NewItemsWorker.WORK_NAME)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<NewItemsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        workManager.enqueueUniquePeriodicWork(
            NewItemsWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Checks TubeArchivist every 15 minutes for tasks that YouTube's bot detection stopped, while
     * the setting is on. Without TubeArchivist set up each check ends right away.
     */
    private fun scheduleTaBotCheck(workManager: WorkManager) {
        if (!appPreferences.getValue(appPreferences.tubeArchivistBotNotifications)) {
            workManager.cancelUniqueWork(TaBotCheckWorker.WORK_NAME)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<TaBotCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        workManager.enqueueUniquePeriodicWork(
            TaBotCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
