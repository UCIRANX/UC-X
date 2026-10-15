package ir.uciran.uctor

import com.multivpn.app.BuildConfig

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** اگر لیست بریج از این مدت قدیمی‌تر باشد، برنامه خودش از منابع اصلی جمع می‌کند (۲۴ ساعت). */
private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

/** تبدیل رشته‌ی «yyyy-MM-dd HH:mm UTC» به زمان میلادی. اگر خالی یا نامعتبر بود null برمی‌گرداند. */
private fun parseUpdated(s: String): Long? {
    val t = s.trim()
    if (t.isEmpty()) return null
    val utc = t.endsWith("UTC", ignoreCase = true)
    val core = t.replace(Regex("(?i)\\s*utc$"), "").trim()
    return runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        if (utc) f.timeZone = TimeZone.getTimeZone("UTC")
        f.parse(core)?.time
    }.getOrNull()
}

/**
 * منبع بریج‌ها (به ترتیب اولویت):
 *  1. فایل ریپوی خودتان (شاخه‌ی bridges، هر ساعت توسط GitHub Action پر می‌شود)؛
 *     اگر قدیمی‌تر از ۲۴ ساعت بود، به‌جایش مرحله‌ی ۲ اجرا می‌شود.
 *  2. جمع‌آوری مستقیم داخل برنامه از Moat رسمی Tor، ریپوهای گیت‌هاب و کانال‌های تلگرام
 *     (همان منابع tools/collect_bridges.py).
 *  3. لیست داخل APK (assets/bridges.json) فقط برای روش‌هایی که در کش هیچ بریجی ندارند.
 */
object BridgeRepository {

    class BridgeSet(val updated: String, val map: Map<String, List<String>>) {
        fun get(t: String): List<String> = map[t].orEmpty()
        fun total() = map.values.sumOf { it.size }

        /** زمان به‌روزرسانی (میلی‌ثانیه)؛ برای لیست داخل APK که تاریخ ندارد null است. */
        val updatedAtMs: Long? by lazy { parseUpdated(updated) }

        /** سن لیست؛ اگر تاریخ نداشته باشد «بی‌نهایت» حساب می‌شود. */
        fun ageMs(): Long = updatedAtMs?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
        fun isStale(): Boolean = ageMs() > MAX_AGE_MS

        fun summary(): String {
            val parts = listOf("webtunnel", "obfs4", "snowflake", "meek_lite", "vanilla")
                .joinToString("  ·  ") { "$it: ${get(it).size}" }
            return if (updated.isNotBlank()) "$parts\nآخرین به‌روزرسانی: $updated" else parts
        }
    }

    private const val CACHE = "bridges_cache.json"
    private const val MOAT = "https://bridges.torproject.org/moat/circumvention/builtin"
    private const val GH = "https://raw.githubusercontent.com"
    private const val TELEGRAM_PAGES = 2

    /** سقف تعداد بریج ذخیره‌شده برای هر روش (مثل collect_bridges.py). */
    private val LIMITS = mapOf("webtunnel" to 150, "obfs4" to 300, "vanilla" to 150)

    @Volatile private var current: BridgeSet? = null

    /** (نام منبع، آدرس) — همان فهرست collect_bridges.py. */
    private val TEXT_SOURCES = listOf(
        "onionhop" to "$GH/center2055/OnionHop-Bridges-Collector/main/bridge/webtunnel_tested.txt",
        "onionhop" to "$GH/center2055/OnionHop-Bridges-Collector/main/bridge/obfs4_tested.txt",
        "onionhop" to "$GH/center2055/OnionHop-Bridges-Collector/main/bridge/vanilla_tested.txt",
        "onionhop" to "$GH/center2055/OnionHop-Bridges-Collector/main/bridge/obfs4_72h.txt",
        "onionhop" to "$GH/center2055/OnionHop-Bridges-Collector/main/bridge/webtunnel_72h.txt",
        "delta-kronecker" to "$GH/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/webtunnel_tested.txt",
        "delta-kronecker" to "$GH/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/obfs4_tested.txt",
        "delta-kronecker" to "$GH/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/vanilla_tested.txt",
        "delta-kronecker" to "$GH/Delta-Kronecker/Tor-Bridges-Collector/main/bridges/webtunnel_tested.txt",
        "delta-kronecker" to "$GH/Delta-Kronecker/Tor-Bridges-Collector/main/bridges/obfs4_tested.txt",
        "scriptzteam-v2" to "$GH/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/webtunnel_tested.txt",
        "scriptzteam-v2" to "$GH/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/obfs4_tested.txt",
        "scriptzteam-v2" to "$GH/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/vanilla_tested.txt",
        "scriptzteam" to "$GH/scriptzteam/Tor-Bridges-Collector/main/bridges-webtunnel",
        "scriptzteam" to "$GH/scriptzteam/Tor-Bridges-Collector/main/bridges-obfs4",
        "scriptzteam" to "$GH/scriptzteam/Tor-Bridges-Collector/main/bridges-vanilla",
        "igareck" to "$GH/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_WEBTUNNEL.txt",
        "igareck" to "$GH/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_OBFS4.txt",
        "igareck" to "$GH/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_TOP100.txt",
    )

    private val TELEGRAM_CHANNELS = listOf("tor_bridges", "vanilla_bridges", "rutorbridgesobfs4webtunnel")

    // ───────────────────────── بارگذاری ─────────────────────────

    fun load(ctx: Context): BridgeSet {
        current?.let { return it }
        val cache = File(ctx.filesDir, CACHE)
        val fromCache = readCache(cache)
        val fromAssets = runCatching {
            ctx.assets.open("bridges.json").bufferedReader().use { parseJson(it.readText()) }
        }.getOrNull()
        val merged = withFallback(fromCache, fromAssets)
        current = merged
        return merged
    }

    fun invalidate() { current = null }

    private fun readCache(cache: File): BridgeSet? =
        if (cache.exists()) runCatching { parseJson(cache.readText()) }.getOrNull() else null

    private fun parseJson(text: String): BridgeSet {
        val o = JSONObject(text)
        val src = o.optJSONObject("bridges") ?: o
        val map = LinkedHashMap<String, MutableList<String>>()
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = src.optJSONArray(k) ?: continue
            for (i in 0 until arr.length()) {
                val line = Planner.normalize(arr.optString(i))
                val t = Planner.typeOf(line)
                if (line.isEmpty() || t !in Planner.KNOWN) continue
                val l = map.getOrPut(t) { mutableListOf() }
                if (line !in l) l += line
            }
        }
        return BridgeSet(o.optString("updated", ""), map)
    }

    /**
     * لیست اصلی (کش) اولویت دارد. لیست داخل APK فقط برای روش‌هایی استفاده می‌شود
     * که در کش اصلاً بریجی ندارند؛ یعنی بریج‌های قدیمیِ APK با داده‌ی تازه قاطی نمی‌شوند.
     */
    private fun withFallback(primary: BridgeSet?, fallback: BridgeSet?): BridgeSet {
        if (primary == null) return fallback ?: BridgeSet("", emptyMap())
        val map = LinkedHashMap<String, List<String>>(primary.map)
        fallback?.map?.forEach { (k, v) -> if (map[k].isNullOrEmpty()) map[k] = v }
        return BridgeSet(primary.updated, map)
    }

    // ───────────────────────── به‌روزرسانی ─────────────────────────

    private fun ownUrls(): List<String> {
        val repo = BuildConfig.BRIDGE_REPO.trim()
        if (repo.isEmpty()) return emptyList()
        return listOf(
            "https://raw.githubusercontent.com/$repo/bridges/bridges.json",
            "https://cdn.jsdelivr.net/gh/$repo@bridges/bridges.json",
            "https://fastly.jsdelivr.net/gh/$repo@bridges/bridges.json",
            "https://gcore.jsdelivr.net/gh/$repo@bridges/bridges.json",
        )
    }

    /** اتصال مستقیم (بدون Tor). دکمه‌ی دستی همیشه force=true می‌زند. */
    fun refreshDirect(ctx: Context, force: Boolean = true): Boolean =
        refresh(ctx, Proxy.NO_PROXY, 6_000, force)

    /** دانلود از داخل تونل HTTP خودِ Tor (بدون نشت DNS). */
    fun refreshViaTor(ctx: Context, httpPort: Int, force: Boolean = false): Boolean =
        httpPort > 0 && refresh(
            ctx, Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort)), 20_000, force,
        )

    private fun refresh(ctx: Context, proxy: Proxy, timeout: Int, force: Boolean): Boolean {
        val cache = File(ctx.filesDir, CACHE)
        val old = readCache(cache)

        // ۱) فایل ریپو؛ بین چهار آدرس تازه‌ترین را برمی‌داریم (کش jsDelivr تا ~۱۲ ساعت قدیمی است)
        var best: BridgeSet? = null
        var bestBody: String? = null
        for (u in ownUrls()) {
            val body = fetch(u, proxy, timeout) ?: continue
            val set = runCatching { parseJson(body) }.getOrNull() ?: continue
            if (set.total() == 0) continue
            if (best == null || set.ageMs() < best.ageMs()) {
                best = set
                bestBody = body
            }
            if (!set.isStale()) break
        }
        if (best != null && bestBody != null && !best.isStale()) {
            writeCache(cache, stamp(bestBody))
            invalidate()
            return true
        }

        // ۲) ریپو قدیمی بود یا در دسترس نبود. اگر خودکار (بعد از اتصال) صدا زده شده و
        //    کش فعلی هنوز تازه است، دوباره جمع نمی‌کنیم تا هر بار اتصال ۲۵ درخواست نرود.
        if (!force && old != null && !old.isStale()) return true

        val fresh = collectDirect(proxy, timeout, best)
        if (fresh == null) {
            // هیچ منبع تازه‌ای جواب نداد؛ فقط اگر نسخه‌ی ریپو از کش فعلی جدیدتر است ذخیره‌اش کن.
            val bt = best?.updatedAtMs
            if (best != null && bestBody != null && bt != null && bt > (old?.updatedAtMs ?: 0L)) {
                writeCache(cache, stamp(bestBody))
                invalidate()
            }
            return false
        }
        writeCache(cache, fresh.toString())
        invalidate()
        return true
    }

    /** اگر فایل منبع فیلد updated نداشت، زمان دریافت را به‌عنوان زمان بروزرسانی ثبت می‌کنیم. */
    private fun stamp(body: String): String = runCatching {
        val o = JSONObject(body)
        if (o.optString("updated").isBlank()) {
            o.put("updated", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()) + " UTC")
        }
        o.toString()
    }.getOrDefault(body)

    private fun writeCache(cache: File, body: String) {
        // اول در فایل موقت می‌نویسیم تا قطع شدن وسط نوشتن کش را خراب نکند.
        val tmp = File(cache.parentFile, cache.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(cache)) {
            cache.writeText(body)
            tmp.delete()
        }
    }

    // ───────────────────────── جمع‌آوری مستقیم ─────────────────────────

    private class Item(val type: String, val key: String, val line: String)
    private class Found(val type: String, val line: String, val sources: MutableSet<String> = mutableSetOf())
    private class Fetched(val src: String, val items: List<Item>, val moat: Map<String, List<String>> = emptyMap())

    /**
     * همه‌ی منابع را موازی می‌گیرد، تکراری‌ها را حذف می‌کند و بریج‌ها را بر اساس
     * «تعداد منابعی که تأییدشان می‌کنند» رتبه می‌دهد (تست TCP از گوشی ممکن نیست).
     * `repoSet` اگر وجود داشته باشد (نسخه‌ی قدیمیِ تست‌شده‌ی ریپو) یک منبع اضافه حساب می‌شود.
     * اگر هیچ منبع تازه‌ای جواب نداد null برمی‌گرداند.
     */
    private fun collectDirect(proxy: Proxy, timeout: Int, repoSet: BridgeSet?): JSONObject? {
        val tasks = mutableListOf<Callable<Fetched>>()
        tasks += Callable<Fetched> {
            val m = moatBuiltin(proxy, timeout)
            val text = (m["obfs4"].orEmpty() + m["webtunnel"].orEmpty()).joinToString("\n")
            Fetched("moat", parseBridges(text), m)
        }
        for ((src, url) in TEXT_SOURCES) {
            tasks += Callable<Fetched> { Fetched(src, fetch(url, proxy, timeout)?.let { parseBridges(it) }.orEmpty()) }
        }
        for (ch in TELEGRAM_CHANNELS) {
            tasks += Callable<Fetched> { Fetched("telegram:$ch", parseBridges(telegramText(ch, proxy, timeout))) }
        }

        val results = mutableListOf<Fetched>()
        val pool = Executors.newFixedThreadPool(24)
        try {
            for (f in pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                runCatching { f.get() }.getOrNull()?.let { results += it }
            }
        } finally {
            pool.shutdownNow()
        }

        val gotFresh = results.any { it.items.isNotEmpty() || it.moat.isNotEmpty() }
        if (!gotFresh) return null

        val found = LinkedHashMap<String, Found>()
        val sourceCounts = LinkedHashMap<String, Int>()
        fun add(src: String, items: List<Item>) {
            for (item in items) {
                found.getOrPut(item.type + "|" + item.key) { Found(item.type, item.line) }.sources += src
            }
            if (items.isNotEmpty()) sourceCounts[src] = (sourceCounts[src] ?: 0) + items.size
        }
        for (r in results) add(r.src, r.items)
        if (repoSet != null) {
            val text = listOf("obfs4", "webtunnel", "vanilla").flatMap { repoSet.get(it) }.joinToString("\n")
            add("repo", parseBridges(text))
        }

        val out = LinkedHashMap<String, List<String>>()
        for (t in listOf("webtunnel", "obfs4", "vanilla")) {
            val limit = LIMITS.getValue(t)
            val entries = found.values.filter { it.type == t }
            val v4 = entries.filter { !isIpv6(t, it.line) }
                .sortedWith(compareBy<Found>({ portRank(t, it.line) }, { -minOf(it.sources.size, 3) }))
            val v6 = entries.filter { isIpv6(t, it.line) }.sortedByDescending { it.sources.size }
            out[t] = v4.take(limit).map { it.line } + v6.take(maxOf(0, limit / 5)).map { it.line }
        }

        // snowflake و meek فقط از Moat می‌آیند؛ اگر Moat جواب نداد، نسخه‌ی ریپو را نگه می‌داریم.
        val moat = results.firstOrNull { it.src == "moat" }?.moat.orEmpty()
        val snow = moat["snowflake"].orEmpty().ifEmpty { repoSet?.get("snowflake").orEmpty() }
        val meek = moat["meek_lite"].orEmpty().ifEmpty { repoSet?.get("meek_lite").orEmpty() }
        if (snow.isNotEmpty()) out["snowflake"] = snow
        if (meek.isNotEmpty()) out["meek_lite"] = meek

        if (out.values.sumOf { it.size } == 0) return null

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date()) + " UTC"
        return JSONObject()
            .put("updated", now)
            .put("generator", "UC Tor in-app collector")
            .put("counts", JSONObject().apply { out.forEach { (k, v) -> put(k, v.size) } })
            .put("sources", JSONObject().apply { sourceCounts.forEach { (k, v) -> put(k, v) } })
            .put("bridges", JSONObject().apply { out.forEach { (k, v) -> put(k, JSONArray(v)) } })
    }

    private fun moatBuiltin(proxy: Proxy, timeout: Int): Map<String, List<String>> {
        val raw = fetch(MOAT, proxy, timeout, post = "{}") ?: fetch(MOAT, proxy, timeout) ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val res = LinkedHashMap<String, MutableList<String>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = o.optJSONArray(k) ?: continue
            val key = if (k == "meek-azure" || k == "meek") "meek_lite" else k
            for (i in 0 until arr.length()) {
                val line = Planner.normalize(arr.optString(i))
                if (line.isEmpty()) continue
                val l = res.getOrPut(key) { mutableListOf() }
                if (line !in l) l += line
            }
        }
        return res
    }

    private fun telegramText(channel: String, proxy: Proxy, timeout: Int): String {
        val sb = StringBuilder()
        var before: Int? = null
        for (i in 0 until TELEGRAM_PAGES) {
            val b = before
            val url = "https://t.me/s/$channel" + (if (b != null) "?before=$b" else "")
            val page = fetch(url, proxy, timeout) ?: break
            sb.append(htmlToText(page)).append('\n')
            val ids = Regex("data-post=\"[^\"/]+/(\\d+)\"").findAll(page)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            val nb = ids.minOrNull() ?: break
            if (b != null && nb >= b) break
            before = nb
        }
        return sb.toString()
    }

    private fun htmlToText(page: String): String {
        var p = Regex("(?i)<br\\s*/?>").replace(page, "\n")
        p = Regex("(?i)</(div|p|pre|code|li)>").replace(p, "\n")
        p = Regex("<[^>]+>").replace(p, "")
        return unescapeHtml(p)
    }

    private fun unescapeHtml(s: String): String {
        var r = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
        }
        r = Regex("&#[xX]([0-9a-fA-F]+);").replace(r) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
        }
        return r.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
    }

    // ───────────────────────── تجزیه‌ی متن بریج ─────────────────────────

    private const val FP = "[0-9A-Fa-f]{40}"
    private const val ADDR = "(?:\\[[0-9A-Fa-f:]+\\]|\\d{1,3}(?:\\.\\d{1,3}){3}):\\d{1,5}"
    private const val EXTRA = "((?:[ \\t]+[A-Za-z0-9_\\-]+=[^\\s<>\"']+)*)"
    private val RE_OBFS4 = Regex("obfs4\\s+($ADDR)\\s+($FP)\\s+cert=([A-Za-z0-9+/]+=*)\\s+iat-mode=([0-2])")
    private val RE_WT = Regex(
        "webtunnel\\s+($ADDR)\\s+($FP)\\s+url=(https?://[^\\s<>\"']+?)(?=\\s|ver=|webtunnel|obfs4|\$)$EXTRA"
    )
    private val RE_VAN = Regex("($ADDR)\\s+($FP)")
    private val STOP_WORDS = listOf("webtunnel", "obfs4", "snowflake", "meek")

    /** تلگرام گاهی خط بعدی را به انتهای خط قبلی می‌چسباند؛ این تابع آن را جدا می‌کند. */
    private fun cleanValue(v0: String): String {
        var v = v0
        for (w in STOP_WORDS) {
            val idx = v.indexOf(w)
            if (idx > 0) v = v.substring(0, idx)
        }
        return v
    }

    /** متن خام (فایل گیت‌هاب / صفحه‌ی تلگرام) را به بریج‌های obfs4 و webtunnel و vanilla تبدیل می‌کند. */
    private fun parseBridges(text0: String): List<Item> {
        val text = Regex("(?<=[0-9A-Za-z=])(obfs4|webtunnel|snowflake|meek_lite)(?=[ \\t])").replace(text0, "\n\$1")
        val out = mutableListOf<Item>()

        for (m in RE_OBFS4.findAll(text)) {
            val addr = m.groupValues[1]
            val fp = m.groupValues[2].uppercase(Locale.ROOT)
            out += Item("obfs4", fp, "obfs4 $addr $fp cert=${m.groupValues[3]} iat-mode=${m.groupValues[4]}")
        }

        for (m in RE_WT.findAll(text)) {
            val addr = m.groupValues[1]
            val fp = m.groupValues[2].uppercase(Locale.ROOT)
            val url = cleanValue(m.groupValues[3])
            val parts = mutableListOf<String>()
            for (tok in m.groupValues[4].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val k = tok.substringBefore('=')
                var v = cleanValue(tok.substringAfter('=', ""))
                if (k == "ver") {
                    v = Regex("^\\d+(?:\\.\\d+)*").find(v)?.value ?: continue
                }
                if (v.isNotEmpty()) parts += "$k=$v"
            }
            out += Item("webtunnel", fp, "webtunnel $addr $fp url=$url" + (if (parts.isEmpty()) "" else " " + parts.joinToString(" ")))
        }

        var stripped = RE_WT.replace(RE_OBFS4.replace(text, " "), " ")
        stripped = Regex("(snowflake|meek_lite|meek)\\s+\\S+").replace(stripped, " ")
        for (m in RE_VAN.findAll(stripped)) {
            val fp = m.groupValues[2].uppercase(Locale.ROOT)
            out += Item("vanilla", fp, "${m.groupValues[1]} $fp")
        }
        return out
    }

    private fun isIpv6(type: String, line: String): Boolean {
        if (type == "webtunnel") return false // آدرس IP در webtunnel فقط جانگهدار است؛ اتصال از طریق دامنه‌ی url انجام می‌شود
        val addr = if (type == "obfs4") line.split(' ').getOrNull(1) else line.split(' ').getOrNull(0)
        return addr?.startsWith("[") == true
    }

    /** پورت ۴۴۳ و ۸۰ در شبکه‌های محدود شانس بیشتری دارند، پس جلوتر می‌آیند. */
    private fun portRank(type: String, line: String): Int {
        val port = runCatching {
            if (type == "webtunnel") {
                val u = URI(Regex("url=(\\S+)").find(line)!!.groupValues[1])
                if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
            } else {
                val addr = if (type == "obfs4") line.split(' ')[1] else line.split(' ')[0]
                addr.substringAfterLast(':').toInt()
            }
        }.getOrDefault(-1)
        return if (port == 443 || port == 80) 0 else 1
    }

    // ───────────────────────── شبکه ─────────────────────────

    private fun fetch(u: String, proxy: Proxy, timeout: Int, post: String? = null): String? = try {
        val c = URL(u).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) UCTor")
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/vnd.api+json")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
