package com.multivpn.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.multivpn.app.security.TimeGuard
import com.multivpn.app.sub.SubManager
import ir.uciran.uctor.Phase
import ir.uciran.uctor.Prefs
import ir.uciran.uctor.TorController
import ir.uciranx.ucg.core.StateBus
import ir.uciranx.ucg.core.VpnState
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Proxy
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
    override suspend fun doWork(): ListenableWorker.Result = try {
        val count = SubRefresh.refreshAll(applicationContext)
        if (count == 0) {
            ListenableWorker.Result.retry()
        } else {
            SubUpdate.markUpdated(applicationContext)
            ListenableWorker.Result.success()
        }
    } catch (e: Exception) {
        ListenableWorker.Result.retry()
    }
}

/**
 * بروزرسانی همهٔ منابع: ساب‌لینک‌های ثابت و شخصی تیک‌خورده.
 * اگر یک تونل فعال باشد (سینگ‌باکس/گارد/تور) از پروکسی SOCKS آن رد می‌شود؛
 * در صورت خطا یا نتیجهٔ خالی، خودکار مستقیم امتحان می‌شود.
 */
object SubRefresh {
    private const val TUNNEL_TIMEOUT_MS = 15_000L
    private const val DIRECT_TIMEOUT_MS = 25_000L
    private const val GUARD_SOCKS_PORT = 10819

    private fun socks(port: Int): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

    /** پروکسی تونل فعال؛ null یعنی مستقیم. */
    fun tunnelProxy(ctx: Context): Proxy? {
        SingBoxController.localSocksPort()?.let { return socks(it) }
        if (StateBus.state.value is VpnState.Connected) return socks(GUARD_SOCKS_PORT)
        if (TorController.state.value.phase == Phase.CONNECTED) {
            val port = Prefs(ctx).socksPort
            if (port > 0) return socks(port)
        }
        return null
    }

    private suspend fun fetchSmart(ctx: Context, urls: List<String>): List<com.multivpn.app.sub.VpnConfig> {
        tunnelProxy(ctx)?.let { proxy ->
            val viaTunnel = withTimeoutOrNull(TUNNEL_TIMEOUT_MS) {
                try {
                    SubManager.fetchUrls(urls, proxy)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (!viaTunnel.isNullOrEmpty()) return viaTunnel
        }
        return withTimeoutOrNull(DIRECT_TIMEOUT_MS) { SubManager.fetchUrls(urls) } ?: emptyList()
    }

    private suspend fun fetchBuiltin(ctx: Context): Int {
        tunnelProxy(ctx)?.let { proxy ->
            val viaTunnel = withTimeoutOrNull(TUNNEL_TIMEOUT_MS) {
                try {
                    SubManager.fetchAndParseSub(ctx, emptyList(), proxy)
                } catch (_: Exception) {
                    null
                }
            }
            if (!viaTunnel.isNullOrEmpty()) return viaTunnel.size
        }
        return (withTimeoutOrNull(DIRECT_TIMEOUT_MS) { SubManager.fetchAndParseSub(ctx) }
            ?: throw Exception("مهلت دریافت ساب‌لینک‌ها تمام شد")).size
    }

    suspend fun refreshAll(ctx: Context): Int {
        val count = fetchBuiltin(ctx)
        val personal = SingBoxStore.loadPersonal(ctx)
        val cache = SingBoxStore.loadPersonalCache(ctx)
        personal.filter { it.enabled }.forEach { sub ->
            val fetched = fetchSmart(ctx, listOf(sub.url))
            if (fetched.isNotEmpty()) cache[sub.url] = fetched.map { it.rawConfig }
        }
        cache.keys.retainAll(personal.map { it.url }.toSet())
        SingBoxStore.savePersonalCache(ctx, cache)
        return count
    }
}
