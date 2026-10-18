package com.multivpn.app

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.multivpn.app.sub.DohClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** لینک‌های ساب‌لینک SSTP. برای اضافه کردن ساب‌لینک جدید، آدرس آن را به این فهرست اضافه کنید. */
val SSTP_SUB_URLS = listOf(
    "https://raw.githubusercontent.com/UCIRANX/Comp/refs/heads/main/UCsstp.txt",
    "https://zaya.io/UCsstp",
    "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/UCX.txt",
)

/** یک سرور SSTP. country کد دو حرفی کشور (مثل IR) یا خالی است. */
data class SstpServer(
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val country: String,
) {
    val key: String get() = "$host:$port:$user"
    val label: String
        get() {
            val c = sstpCountryName(country)
            val hp = "$host:$port"
            return if (c.isBlank()) hp else "$c · $hp"
        }
}

private val COUNTRY_FA = mapOf(
    "IR" to "ایران", "US" to "آمریکا", "JP" to "ژاپن", "KR" to "کره جنوبی", "DE" to "آلمان",
    "FR" to "فرانسه", "GB" to "بریتانیا", "UK" to "بریتانیا", "NL" to "هلند", "SE" to "سوئد",
    "CA" to "کانادا", "AU" to "استرالیا", "IT" to "ایتالیا", "ES" to "اسپانیا", "RU" to "روسیه",
    "CN" to "چین", "TR" to "ترکیه", "AE" to "امارات", "IN" to "هند", "SG" to "سنگاپور",
    "HK" to "هنگ‌کنگ", "TW" to "تایوان", "VN" to "ویتنام", "TH" to "تایلند", "MY" to "مالزی",
    "ID" to "اندونزی", "PH" to "فیلیپین", "BR" to "برزیل", "AR" to "آرژانتین", "MX" to "مکزیک",
    "PL" to "لهستان", "CZ" to "چک", "AT" to "اتریش", "CH" to "سوئیس", "BE" to "بلژیک",
    "DK" to "دانمارک", "NO" to "نروژ", "FI" to "فنلاند", "UA" to "اوکراین", "RO" to "رومانی",
    "BG" to "بلغارستان", "GR" to "یونان", "PT" to "پرتغال", "IE" to "ایرلند", "IL" to "اسرائیل",
    "SA" to "عربستان", "EG" to "مصر", "ZA" to "آفریقای جنوبی", "NZ" to "نیوزیلند",
    "KZ" to "قزاقستان", "AZ" to "آذربایجان", "AM" to "ارمنستان", "GE" to "گرجستان",
    "IQ" to "عراق", "PK" to "پاکستان", "BD" to "بنگلادش", "LV" to "لتونی", "LT" to "لیتوانی",
    "EE" to "استونی", "HU" to "مجارستان", "SK" to "اسلواکی", "SI" to "اسلوونی", "RS" to "صربستان",
    "HR" to "کرواسی", "BY" to "بلاروس", "MD" to "مولداوی", "CL" to "شیلی", "CO" to "کلمبیا", "PE" to "پرو",
)

/** نام فارسی کشور از کد دو حرفی؛ اگر ناشناس بود، همان کد. */
fun sstpCountryName(code: String): String {
    val c = code.trim().uppercase()
    if (c.isBlank()) return ""
    return COUNTRY_FA[c] ?: c
}

/**
 * فرمت‌های پذیرفته‌شده:
 *   hostname:port:username:password:country
 *   ip:port:username:password:country
 * اگر کشور نباشد، سرور هم پذیرفته می‌شود. اگر پورت نباشد، 443 است.
 */
object SstpParser {
    fun parse(text: String): List<SstpServer> = text.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.contains("://")) null else parseLine(line)
    }

    private fun parseLine(line: String): SstpServer? {
        val f = line.split(":").map { it.trim() }
        val host = f.getOrNull(0).orEmpty()
        if (host.isBlank()) return null
        val second = f.getOrNull(1).orEmpty()
        return if (second.isEmpty() || second.all { it.isDigit() }) {
            // host:port:user:pass[:country]
            SstpServer(
                host = host,
                port = second.toIntOrNull() ?: 443,
                user = f.getOrNull(2).orEmpty(),
                pass = f.getOrNull(3).orEmpty(),
                country = f.getOrNull(4).orEmpty(),
            )
        } else {
            // host:user:pass[:country]  (پورت نوشته نشده)
            SstpServer(
                host = host,
                port = 443,
                user = second,
                pass = f.getOrNull(2).orEmpty(),
                country = f.getOrNull(3).orEmpty(),
            )
        }
    }
}

/** ذخیرهٔ سرورهای دستی و کش ساب‌لینک SSTP. */
object SstpStore {
    private const val K_MANUAL = "SSTP_MANUAL"
    private const val K_SUB = "SSTP_SUB_CACHE"
    private const val K_UPDATED = "SSTP_SUB_UPDATED"
    private const val K_FAV = "SSTP_FAV"

    fun loadFav(ctx: Context): List<SstpServer> = fromJson(prefs(ctx).getString(K_FAV, "[]"))

    /** قلب زدن/برداشتن؛ برمی‌گرداند: آیا الان قلب‌دار است. */
    fun toggleFav(ctx: Context, s: SstpServer): Boolean {
        val cur = loadFav(ctx).toMutableList()
        val i = cur.indexOfFirst { it.key == s.key }
        val nowFav = i < 0
        if (i >= 0) cur.removeAt(i) else cur.add(0, s)
        prefs(ctx).edit().putString(K_FAV, toJson(cur)).apply()
        return nowFav
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun loadManual(ctx: Context): List<SstpServer> = fromJson(prefs(ctx).getString(K_MANUAL, "[]"))
    fun saveManual(ctx: Context, list: List<SstpServer>) {
        prefs(ctx).edit().putString(K_MANUAL, toJson(list)).apply()
    }

    fun loadSub(ctx: Context): List<SstpServer> = fromJson(prefs(ctx).getString(K_SUB, "[]"))
    fun saveSub(ctx: Context, list: List<SstpServer>) {
        prefs(ctx).edit().putString(K_SUB, toJson(list)).apply()
    }

    fun lastUpdated(ctx: Context): Long = prefs(ctx).getLong(K_UPDATED, 0L)

    internal fun markUpdated(ctx: Context) {
        prefs(ctx).edit().putLong(K_UPDATED, System.currentTimeMillis()).apply()
    }

    private fun toJson(list: List<SstpServer>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("h", it.host).put("p", it.port).put("u", it.user).put("w", it.pass).put("c", it.country))
        }
        return arr.toString()
    }

    private fun fromJson(s: String?): List<SstpServer> = runCatching {
        val arr = JSONArray(s ?: "[]")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            SstpServer(o.getString("h"), o.getInt("p"), o.getString("u"), o.getString("w"), o.optString("c", ""))
        }
    }.getOrDefault(emptyList())
}

/** بروزرسانی ساب‌لینک‌های SSTP (دستی و هر ۱۲ ساعت). لینک‌های تودرتو داخل فایل‌ها هم خوانده می‌شوند. */
object SstpSub {
    private const val MAX_DEPTH = 2
    private const val TUNNEL_TIMEOUT_MS = 20_000L

    private suspend fun collect(url: String, depth: Int, visited: MutableSet<String>, out: StringBuilder, proxy: java.net.Proxy?) {
        if (depth > MAX_DEPTH || !visited.add(url)) return
        val text = try {
            DohClient.fetchUrlText(url, proxy)
        } catch (_: Exception) {
            return
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
                collect(line, depth + 1, visited, out, proxy)
            } else {
                out.append(line).append('\n')
            }
        }
    }

    /** همهٔ ساب‌لینک‌ها از یک مسیر (پروکسی یا مستقیم)؛ اگر چیزی نبود null. */
    private suspend fun fetchAll(proxy: java.net.Proxy?): String? {
        val out = StringBuilder()
        val visited = mutableSetOf<String>()
        for (url in SSTP_SUB_URLS) collect(url, 0, visited, out, proxy)
        return if (out.isBlank()) null else out.toString()
    }

    /** اول از VPN فعال (سینگ‌باکس/گارد/تور)؛ اگر خطا یا خالی بود، مستقیم. */
    suspend fun refresh(ctx: Context): Int {
        val tunnel = SubRefresh.tunnelProxy(ctx)
        val text = (if (tunnel != null) withTimeoutOrNull(TUNNEL_TIMEOUT_MS) { fetchAll(tunnel) } else null)
            ?: fetchAll(null)
            ?: throw Exception("دریافت ساب‌لینک SSTP ناموفق بود")
        return withContext(Dispatchers.IO) {
            val servers = LinkedHashMap<String, SstpServer>()
            SstpParser.parse(text).forEach { servers.putIfAbsent(it.key, it) }
            SstpStore.saveSub(ctx, servers.values.toList())
            SstpStore.markUpdated(ctx)
            servers.size
        }
    }

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<SstpSubWorker>(12, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "sstp-sub-12h",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class SstpSubWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result = try {
        SstpSub.refresh(applicationContext)
        ListenableWorker.Result.success()
    } catch (_: Exception) {
        ListenableWorker.Result.retry()
    }
}
