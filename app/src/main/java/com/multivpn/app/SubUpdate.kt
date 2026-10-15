package com.multivpn.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.multivpn.app.security.TimeGuard
import com.multivpn.app.sub.SubManager
import java.util.concurrent.TimeUnit

private const val SUB_UPDATED_AT = "sub_updated_at_ms"

/** بروزرسانی خودکار ساب‌لینک‌ها هر ۲۴ ساعت (حتی وقتی برنامه بسته است). */
object SubUpdate {
    fun lastUpdatedMs(ctx: Context): Long =
        TimeGuard.getSecurePrefs(ctx).getLong(SUB_UPDATED_AT, 0L)

    fun markUpdated(ctx: Context) {
        TimeGuard.getSecurePrefs(ctx).edit().putLong(SUB_UPDATED_AT, System.currentTimeMillis()).apply()
    }

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<SubUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "sub-update-24h",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class SubUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result = try {
        val list = SubManager.fetchAndParseSub(applicationContext)
        if (list.isEmpty()) androidx.work.ListenableWorker.Result.retry()
        else {
            SubUpdate.markUpdated(applicationContext)
            androidx.work.ListenableWorker.Result.success()
        }
    } catch (e: Exception) {
        androidx.work.ListenableWorker.Result.retry()
    }
}
