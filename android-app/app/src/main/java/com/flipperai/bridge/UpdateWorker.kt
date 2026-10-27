package com.flipperai.bridge

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = SecureStore(applicationContext)
        try {
            val latest = Releases.latest(AppModel.includeBeta(store)) ?: return@withContext Result.success()
            val info = AppModel.evaluate(latest, store)
            val parts = ArrayList<String>()
            if (info.appUpdate) parts.add("Phone app ${latest.tag}")
            if (info.fapState == FapState.UpdateAvailable) parts.add("Flipper app ${latest.tag}")
            if (parts.isNotEmpty() && store.lastNotifiedTag != latest.tag) {
                Notifier.updateAvailable(applicationContext, parts.joinToString(" and ") + " is ready to install.")
                store.lastNotifiedTag = latest.tag
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "update-check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
