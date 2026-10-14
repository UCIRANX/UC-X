package com.multivpn.app.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

object TimeGuard {
    private const val PREF_FILE = "secure_time_prefs"
    private const val KEY_MAX_SEEN_EPOCH = "max_seen_epoch_sec"

    @Volatile
    private var anchorNetworkEpochSec: Long = 0L

    @Volatile
    private var anchorElapsedRealtimeSec: Long = 0L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val timeUrls = listOf(
        "https://www.digikala.com",
        "https://www.aparat.com",
        "http://cp.cloudflare.com/generate_204",
        "https://www.google.com"
    )

    fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
    }

    suspend fun syncNetworkTime(context: Context): Boolean = withContext(Dispatchers.IO) {
        for (url in timeUrls) {
            try {
                val request = Request.Builder().url(url).head().build()
                httpClient.newCall(request).execute().use { response ->
                    val dateHeader = response.header("Date")
                    if (!dateHeader.isNullOrBlank()) {
                        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                        val parsedDate = sdf.parse(dateHeader)
                        if (parsedDate != null) {
                            val serverSec = parsedDate.time / 1000L
                            if (serverSec > 1700000000L) {
                                anchorNetworkEpochSec = serverSec
                                anchorElapsedRealtimeSec = SystemClock.elapsedRealtime() / 1000L
                                updateMaxSeenTime(context, serverSec)
                                return@withContext true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        false
    }

    fun isClockTampered(context: Context): Boolean {
        val prefs = getSecurePrefs(context)
        val maxSeen = prefs.getLong(KEY_MAX_SEEN_EPOCH, 0L)
        val currentWallSec = System.currentTimeMillis() / 1000L

        // اگر ساعت شبکه را داریم، ساعت سیستم اهمیتی ندارد چون زمان دقیق را داریم
        if (anchorNetworkEpochSec > 0L) {
            return false
        }

        // اگر کاربر ساعت گوشی را بیش از ۵ دقیقه به عقب کشیده باشد
        if (maxSeen > 0L && currentWallSec + 300L < maxSeen) {
            return true
        }

        updateMaxSeenTime(context, currentWallSec)
        return false
    }

    fun getCurrentVerifiedTimeSec(context: Context): Long {
        val nowElapsedSec = SystemClock.elapsedRealtime() / 1000L
        val currentWallSec = System.currentTimeMillis() / 1000L
        val prefs = getSecurePrefs(context)
        val maxSeen = prefs.getLong(KEY_MAX_SEEN_EPOCH, 0L)

        val estimatedSec = if (anchorNetworkEpochSec > 0L) {
            anchorNetworkEpochSec + (nowElapsedSec - anchorElapsedRealtimeSec)
        } else {
            max(currentWallSec, maxSeen)
        }

        updateMaxSeenTime(context, estimatedSec)
        return estimatedSec
    }

    private fun updateMaxSeenTime(context: Context, newTimeSec: Long) {
        val prefs = getSecurePrefs(context)
        val currentMax = prefs.getLong(KEY_MAX_SEEN_EPOCH, 0L)
        if (newTimeSec > currentMax) {
            prefs.edit().putLong(KEY_MAX_SEEN_EPOCH, newTimeSec).apply()
        }
    }
}
    
