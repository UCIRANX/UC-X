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
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** نوع پروتکل یک سرور در بخش «پروتکل‌ها». */
enum class VpnType(val label: String) {
    IKEV2("IKEv2"),
    L2TP("L2TP/IPsec"),
    OPENCONNECT("OpenConnect"),
}

/**
 * یک سرور در لیست‌های بخش پروتکل‌ها (دستی یا ساب‌لینک).
 * rawKey کلید ذخیرهٔ اصلی در فروشگاه همان پروتکل است (برای ویرایش و حذف).
 */
data class ProtoServer(
    val type: VpnType,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val psk: String,
    val remoteId: String,
    val authGroup: String,
    val certPin: String,
    val rawKey: String,
    /** کد دو حرفی کشور از ساب‌لینک (اختیاری)؛ نام فارسی آن در نمایش ساخته می‌شود. */
    val country: String = "",
    /** آدرس منبع ساب‌لینک که این سرور از آن آمده است. */
    val source: String = "",
) {
    val key: String get() = "${type.name}|$host|$port|$user"
    val label: String get() = if (name.isNotBlank()) name else host
}

/** فروشگاه لیست‌های بخش پروتکل‌ها: دستی، ساب‌لینک، قلب‌ها و انتخاب. */
object ProtoLists {
    private const val K_SEL = "PROTO_SELECTED"
    private const val K_FAV_MAN = "PROTO_FAV_MAN"
    private const val K_FAV_SUB = "PROTO_FAV_SUB"
    private const val K_SUB = "PROTO_SUB"

    /**
     * لینک‌های ساب‌لینک بخش پروتکل‌ها. هنوز تنظیم نشده‌اند؛ پس از ارسال لینک و
     * نمونهٔ فرمت، اینجا اضافه می‌شوند.
     */
    /** ساب‌لینک اصلی پلاس؛ سرورهای آن در لیست بالاتر از بقیه نشان داده می‌شوند. */
    const val PLUS_URL = "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/UcPlus.txt"

    val SUB_URLS: List<String> = listOf(
        "https://raw.githubusercontent.com/UCIRANX/Comp/refs/heads/main/UcL2tp.txt",
        PLUS_URL,
        "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/UCX.txt",
    )

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    /** کلید ذخیره‌سازی در فروشگاه پروتکل مربوط. */
    fun rawKeyFor(item: ProtoServer): String =
        if (item.type == VpnType.OPENCONNECT) "${item.host}|${item.port}|${item.user}" else "${item.host}|${item.user}"

    // ───── سرورهای دستی ─────

    fun manual(ctx: Context): List<ProtoServer> {
        val out = mutableListOf<ProtoServer>()
        Ikev2Store.load(ctx).forEach {
            out += ProtoServer(VpnType.IKEV2, it.name, it.server, 0, it.username, it.password, it.psk, it.remoteId, "", "", it.key)
        }
        ProtocolStore.loadL2tp(ctx).forEach {
            out += ProtoServer(VpnType.L2TP, it.name, it.server, 0, it.username, it.password, it.psk, "", "", "", it.key)
        }
        ProtocolStore.loadOc(ctx).forEach {
            out += ProtoServer(VpnType.OPENCONNECT, it.name, it.server, it.port, it.username, it.password, "", "", it.authGroup, it.certPin, it.key)
        }
        return out
    }

    /** افزودن یا ویرایش؛ replaceRaw کلید سرور قبلی است (در ویرایش). */
    fun saveManual(ctx: Context, item: ProtoServer, replaceRaw: String?) {
        val newRaw = rawKeyFor(item)
        when (item.type) {
            VpnType.IKEV2 -> {
                val rest = Ikev2Store.load(ctx).filter { it.key != replaceRaw && it.key != newRaw }
                Ikev2Store.save(ctx, rest + Ikev2Server(item.name, item.host, item.user, item.password, item.remoteId, item.psk))
            }
            VpnType.L2TP -> {
                val rest = ProtocolStore.loadL2tp(ctx).filter { it.key != replaceRaw && it.key != newRaw }
                ProtocolStore.saveL2tp(ctx, rest + L2tpEntry(item.name, item.host, item.psk, item.user, item.password))
            }
            VpnType.OPENCONNECT -> {
                val rest = ProtocolStore.loadOc(ctx).filter { it.key != replaceRaw && it.key != newRaw }
                ProtocolStore.saveOc(
                    ctx,
                    rest + OcEntry(item.name, item.host, item.port, item.user, item.password, item.authGroup, item.certPin),
                )
            }
        }
    }

    fun deleteManual(ctx: Context, item: ProtoServer) {
        when (item.type) {
            VpnType.IKEV2 -> Ikev2Store.save(ctx, Ikev2Store.load(ctx).filter { it.key != item.rawKey })
            VpnType.L2TP -> ProtocolStore.saveL2tp(ctx, ProtocolStore.loadL2tp(ctx).filter { it.key != item.rawKey })
            VpnType.OPENCONNECT -> ProtocolStore.saveOc(ctx, ProtocolStore.loadOc(ctx).filter { it.key != item.rawKey })
        }
    }

    // ───── قلب‌ها ─────

    fun favManKeys(ctx: Context): List<String> = readStrings(prefs(ctx).getString(K_FAV_MAN, "[]"))

    /** برمی‌گرداند: آیا الان قلب‌دار است. */
    fun toggleManFav(ctx: Context, key: String): Boolean {
        val cur = favManKeys(ctx).toMutableList()
        val nowFav = if (cur.contains(key)) {
            cur.remove(key)
            false
        } else {
            cur.add(0, key)
            true
        }
        prefs(ctx).edit().putString(K_FAV_MAN, JSONArray(cur).toString()).apply()
        return nowFav
    }

    /**
     * قلب سرورهای ساب‌لینک: نسخهٔ کامل سرور ذخیره می‌شود تا با بروزرسانی حذف نشود.
     * فقط وقتی قلب برداشته شود و در ساب‌لینک جدید نباشد، از لیست حذف می‌شود.
     */
    fun favSub(ctx: Context): List<ProtoServer> = readServers(prefs(ctx).getString(K_FAV_SUB, "[]"))

    fun toggleSubFav(ctx: Context, item: ProtoServer): Boolean {
        val cur = favSub(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.key == item.key }
        val nowFav = if (idx >= 0) {
            cur.removeAt(idx)
            false
        } else {
            cur.add(0, item)
            true
        }
        prefs(ctx).edit().putString(K_FAV_SUB, writeServers(cur)).apply()
        return nowFav
    }

    // ───── ساب‌لینک ─────

    fun loadSub(ctx: Context): List<ProtoServer> = readServers(prefs(ctx).getString(K_SUB, "[]"))

    fun saveSub(ctx: Context, list: List<ProtoServer>) {
        prefs(ctx).edit().putString(K_SUB, writeServers(list)).apply()
    }

    /**
     * بروزرسانی ساب‌لینک‌های بخش پلاس؛ لینک‌های تودرتو داخل فایل‌ها هم خوانده می‌شوند.
     * اول از VPN فعال رد می‌شود؛ اگر خطا داد، مستقیم.
     */
    suspend fun refreshSub(ctx: Context): String {
        val tunnel = SubRefresh.tunnelProxy(ctx)
        var got: List<Pair<String, String>>? = null
        if (tunnel != null) got = withTimeoutOrNull(20_000L) { collectAll(tunnel) }
        if (got == null) got = collectAll(null)
        val res = got ?: return "دریافت ساب‌لینک ناموفق بود"
        // تجزیه و ذخیره روی پس‌زمینه انجام می‌شود تا رابط کاربری قفل نشود
        return withContext(Dispatchers.IO) {
            val servers = LinkedHashMap<String, ProtoServer>()
            res.forEach { (url, text) ->
                PlusParser.parse(text).forEach { servers.putIfAbsent(it.key, it.copy(source = url)) }
            }
            saveSub(ctx, servers.values.toList())
            markUpdated(ctx)
            "${servers.size} سرور از ساب‌لینک دریافت شد"
        }
    }

    /** هر لینک اصلی جدا جمع می‌شود تا سرورهای هر منبع بتوانند اولویت بگیرند. */
    private suspend fun collectAll(proxy: java.net.Proxy?): List<Pair<String, String>>? {
        val visited = mutableSetOf<String>()
        val results = mutableListOf<Pair<String, String>>()
        for (u in SUB_URLS) {
            val out = StringBuilder()
            collect(u, 0, visited, out, proxy)
            if (out.isNotBlank()) results += u to out.toString()
        }
        return if (results.isEmpty()) null else results
    }

    private suspend fun collect(url: String, depth: Int, visited: MutableSet<String>, out: StringBuilder, proxy: java.net.Proxy?) {
        if (depth > 2 || !visited.add(url)) return
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

    /** بروزرسانی خودکار هر ۲۴ ساعت. */
    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<ProtoSubWorker>(24, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("proto-sub-24h", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private const val K_UPDATED = "PROTO_SUB_UPDATED"

    /** زمان آخرین بروزرسانی موفق ساب‌لینک بخش پلاس (۰ یعنی هرگز). */
    fun lastUpdated(ctx: Context): Long = prefs(ctx).getLong(K_UPDATED, 0L)

    private fun markUpdated(ctx: Context) {
        prefs(ctx).edit().putLong(K_UPDATED, System.currentTimeMillis()).apply()
    }

    // ───── انتخاب ─────

    fun selected(ctx: Context): String? = prefs(ctx).getString(K_SEL, null)

    fun setSelected(ctx: Context, key: String?) {
        prefs(ctx).edit().putString(K_SEL, key).apply()
    }

    // ───── JSON ─────

    private fun readStrings(s: String?): List<String> = runCatching {
        val arr = JSONArray(s ?: "[]")
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeServers(list: List<ProtoServer>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("t", it.type.name).put("n", it.name).put("h", it.host).put("o", it.port)
                    .put("u", it.user).put("p", it.password).put("k", it.psk).put("r", it.remoteId)
                    .put("g", it.authGroup).put("c", it.certPin).put("raw", it.rawKey).put("cc", it.country)
            )
        }
        return arr.toString()
    }

    private fun readServers(s: String?): List<ProtoServer> = runCatching {
        val arr = JSONArray(s ?: "[]")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            ProtoServer(
                type = VpnType.valueOf(o.getString("t")),
                name = o.optString("n"),
                host = o.getString("h"),
                port = o.optInt("o", 0),
                user = o.optString("u"),
                password = o.optString("p"),
                psk = o.optString("k"),
                remoteId = o.optString("r"),
                authGroup = o.optString("g"),
                certPin = o.optString("c"),
                rawKey = o.optString("raw"),
                country = o.optString("cc"),
            )
        }
    }.getOrDefault(emptyList())
}

class ProtoSubWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result = try {
        ProtoLists.refreshSub(applicationContext)
        ListenableWorker.Result.success()
    } catch (_: Exception) {
        ListenableWorker.Result.retry()
    }
}

/**
 * تجزیهٔ ساب‌لینک بخش پلاس. فرمت هر خط (فیلد خالی = مقدار خالی، بدون پیش‌فرض):
 *   IKEV2:server:port:username:password:psk:remoteid:name:country
 *   L2TP:server:port:username:password:psk:name:country
 *   OPENCONNECT:server:port:username:password:authgroup:certpin:name:country
 * خطوطی که پیشوند شناخته‌شده ندارند نادیده گرفته می‌شوند.
 */
object PlusParser {
    fun parse(text: String): List<ProtoServer> = text.lines().mapNotNull { parseLine(it.trim()) }

    private fun parseLine(line: String): ProtoServer? {
        if (!line.contains(':')) return null
        val type = when (line.substringBefore(':').trim().uppercase()) {
            "IKEV2" -> VpnType.IKEV2
            "L2TP" -> VpnType.L2TP
            "OPENCONNECT" -> VpnType.OPENCONNECT
            else -> return null
        }
        val f = line.substringAfter(':').split(':')
        fun at(i: Int): String = f.getOrNull(i)?.trim().orEmpty()
        val host = at(0)
        if (host.isBlank()) return null
        val port = at(1).toIntOrNull() ?: 0
        return when (type) {
            VpnType.IKEV2 -> ProtoServer(type, at(6), host, port, at(2), at(3), at(4), at(5), "", "", "", at(7))
            VpnType.L2TP -> ProtoServer(type, at(5), host, port, at(2), at(3), at(4), "", "", "", "", at(6))
            VpnType.OPENCONNECT -> ProtoServer(type, at(6), host, port, at(2), at(3), "", "", at(4), at(5), "", at(7))
        }
    }
}
