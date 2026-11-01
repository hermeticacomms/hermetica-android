package com.hermeticacomms.sdk

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Sends the persisted queue once a network is back, when the app went to the background with items still pending.
 * The SDK schedules it; apps never need to. Public only because WorkManager creates workers by reflection.
 */
class HermeticaFlushWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // WorkManager starts the process through Application.onCreate, where the app initializes the SDK.
        val core = Hermetica.core ?: return Result.success()
        return if (core.flushNow()) Result.success() else Result.retry()
    }

    internal companion object {
        const val NAME = "hermetica-flush"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequest.Builder(HermeticaFlushWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            try {
                // getInstance initializes WorkManager on first use: the default initializer, or the app's Configuration.Provider.
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
            } catch (_: IllegalStateException) {
                // The app turned WorkManager's initializer off without a Configuration.Provider: the queue waits for the next launch.
            }
        }
    }
}
