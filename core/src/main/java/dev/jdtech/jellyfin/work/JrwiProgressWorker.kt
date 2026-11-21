package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import java.util.concurrent.TimeUnit

/**
 * Sends progress of JRWI episodes that was saved without a connection, each with the time it was
 * made, once the archiver can be reached.
 */
@HiltWorker
class JrwiProgressWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val jrwiProgress: JrwiProgressSync,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        if (jrwiProgress.sendPending()) Result.success() else Result.retry()

    companion object {
        private const val WORK_NAME = "jrwi_progress"

        /** Sends what's waiting once there's a connection. */
        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<JrwiProgressWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
