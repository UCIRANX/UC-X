package com.multivpn.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multivpn.app.awg.AwgState
import com.multivpn.app.core.CoreConfigGenerator
import com.multivpn.app.core.CoreEngineRunner
import com.multivpn.app.security.TimeGuard
import com.multivpn.app.sub.PingTester
import com.multivpn.app.sub.SubManager
import com.multivpn.app.sub.VpnConfig
import ir.uciran.uctor.Phase
import ir.uciran.uctor.TorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * وضعیت و عملیات حالت سینگ‌باکس.
 * شناسهٔ هر سرور = هش کانفیگ؛ پس قلب، پینگ و انتخاب بین بروزرسانی‌ها پایدار می‌مانند.
 * فهرست نهایی از سه منبع ساخته می‌شود: ساب‌لینک‌ها، کلیپ‌برد، و سرورهای قلب‌دار.
 */
object SingBoxController {
    private const val PING_KEY = "singbox_ping_cache"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connJob: Job? = null
    private var pingJob: Job? = null
    private var pingFlushJob: Job? = null
    private var loaded = false
    private var awgActive = false
    private var appCtx: Context? = null
    private var loadedStamp = 0L

    private var subConfigs: List<VpnConfig> = emptyList()
    private val clips = mutableListOf<ClipItem>()
    private val favRaws = mutableListOf<String>()
    private val pingMap = HashMap<String, Long>()
    private val pendingPings = LinkedHashMap<String, VpnConfig>()

    var configs: List<VpnConfig> by mutableStateOf<List<VpnConfig>>(emptyList())
        private set

    /** فهرست نمایشی به ترتیب: قلب‌دارها، کلیپ‌برد معلق، بقیه. */
    var displayed: List<VpnConfig> by mutableStateOf<List<VpnConfig>>(emptyList())
        private set

    var favKeys: Set<String> by mutableStateOf<Set<String>>(emptySet())
        private set
    var clipKeys: Set<String> by mutableStateOf<Set<String>>(emptySet())
        private set
    var personal: List<PersonalSub> by mutableStateOf<List<PersonalSub>>(emptyList())
        private set

    var selectedId: String? by mutableStateOf<String?>(null)
        private set
    var connected: Boolean by mutableStateOf(false)
        private set
    var connecting: Boolean by mutableStateOf(false)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var pinging: Boolean by mutableStateOf(false)
        private set
    var status: String by mutableStateOf("")
        private set
    var lastUpdate: String by mutableStateOf("")
        private set
    var paused: Boolean by mutableStateOf(false)
        private set

    val selected: VpnConfig?
        get() = configs.firstOrNull { it.id == selectedId }

    fun resume(ctx: Context) {
        if (!paused) return
        status = "در حال اتصال مجدد…"
        ctx.startService(Intent(ctx, MultiCoreVpnService::class.java).setAction(MultiCoreVpnService.ACTION_RESUME))
    }

    /** پورت SOCKS هستهٔ Xray وقتی وصل است؛ برای AmneziaWG و حالت قطع null. */
    fun localSocksPort(): Int? = if (connected && !awgActive) CoreConfigGenerator.LOCAL_SOCKS_PORT else null

    fun reportStatus(msg: String) {
        status = msg
    }

    // ───────── کلید و ذخیرهٔ پینگ ─────────

    private fun keyRaw(raw: String): String =
        MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun keyOf(c: VpnConfig): String = keyRaw(c.rawConfig)

    private fun loadPings(ctx: Context) {
        pingMap.clear()
        runCatching {
            val o = JSONObject(TimeGuard.getSecurePrefs(ctx).getString(PING_KEY, "{}") ?: "{}")
            o.keys().forEach { k -> pingMap[k] = o.getLong(k) }
        }
    }

    private fun savePings(ctx: Context) {
        val o = JSONObject()
        pingMap.forEach { (k, v) -> if (v != -1L) o.put(k, v) }
        TimeGuard.getSecurePrefs(ctx).edit().putString(PING_KEY, o.toString()).apply()
    }

    // ───────── ساختن فهرست ─────────

    private fun formatTime(ms: Long): String =
        if (ms <= 0L) "" else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ms))

    /** فهرست کامل را از ساب‌لینک‌ها، کلیپ‌برد و قلب‌ها می‌سازد؛ شناسه‌ها هش‌اند و پینگ از روی ذخیره. */
    private fun rebuild() {
        val seen = LinkedHashSet<String>()
        val merged = ArrayList<VpnConfig>()
        fun add(c: VpnConfig) {
            val k = keyOf(c)
            if (seen.add(k)) merged += c.copy(id = k, pingMs = pingMap[k] ?: -1L)
        }
        subConfigs.forEach(::add)
        clips.forEach { item -> SubManager.parseText(item.raw).firstOrNull()?.let(::add) }
        favRaws.forEach { raw -> SubManager.parseText(raw).firstOrNull()?.let(::add) }

        val favs = favRaws.map { keyRaw(it) }.toSet()
        val pendingKeys = clips.filter { it.pending }.map { keyRaw(it.raw) }
        val clipAll = clips.map { keyRaw(it.raw) }.toSet()

        val favGroup = merged.filter { it.id in favs }
        val pendingGroup = pendingKeys.mapNotNull { k -> merged.firstOrNull { it.id == k && it.id !in favs } }
        val pendingSet = pendingKeys.toSet()
        val restGroup = merged.filter { it.id !in favs && it.id !in pendingSet }

        favKeys = favs
        clipKeys = clipAll
        configs = merged
        displayed = sortPing(favGroup) + pendingGroup + sortPing(restGroup)
        selectedId = merged.firstOrNull { it.id == selectedId }?.id ?: merged.firstOrNull()?.id
    }

    /** سرورهای دارای پینگ بر اساس کمترین پینگ؛ بقیه بعد از آن‌ها و به ترتیب قبلی. */
    private fun sortPing(list: List<VpnConfig>): List<VpnConfig> =
        list.withIndex().sortedWith(
            compareBy<IndexedValue<VpnConfig>>(
                { if (it.value.pingMs > 0) 0 else 1 },
                { if (it.value.pingMs > 0) it.value.pingMs else 0L },
                { it.index },
            )
        ).map { it.value }

    private fun setPing(id: String, ping: Long) {
        pingMap[id] = ping
        appCtx?.let { savePings(it) }
        rebuild()
    }

    private fun flushPings() {
        if (pendingPings.isEmpty()) return
        pendingPings.forEach { (id, cfg) -> pingMap[id] = cfg.pingMs }
        pendingPings.clear()
        appCtx?.let { savePings(it) }
        rebuild()
    }

    private fun clearPings(ctx: Context) {
        pingMap.clear()
        TimeGuard.getSecurePrefs(ctx).edit().remove(PING_KEY).apply()
        rebuild()
    }

    fun refreshFromStore(ctx: Context) {
        val ts = SubUpdate.lastUpdatedMs(ctx)
        if (ts == loadedStamp || busy) return
        loadedStamp = ts
        lastUpdate = formatTime(ts)
        reloadSources(ctx)
        rebuild()
    }

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        loadedStamp = SubUpdate.lastUpdatedMs(ctx)
        lastUpdate = formatTime(loadedStamp)
        loadPings(ctx)
        favRaws.clear()
        favRaws.addAll(SingBoxStore.loadFavs(ctx))
        clips.clear()
        clips.addAll(SingBoxStore.loadClips(ctx))
        personal = SingBoxStore.loadPersonal(ctx)
        reloadSources(ctx)
        rebuild()
        selectTab(ctx, false)
        status = if (configs.isEmpty()) "ابتدا سرورها را بروزرسانی کنید" else "آماده اتصال"

        scope.launch {
            MultiCoreVpnService.pausedFlow.collect { p ->
                paused = p
                if (connected) status = if (p) "متوقف موقت" else "متصل"
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000L)
                if (!connected) continue
                if (awgActive) {
                    val st = MultiCoreVpnService.awgStatus.value
                    if (!paused && (st is AwgState.Idle || st is AwgState.Failed)) {
                        connected = false
                        awgActive = false
                        appCtx?.let { it.startService(stopIntent(it)) }
                        status = "اتصال قطع شد: تونل AmneziaWG بسته شد"
                    }
                } else if (!CoreEngineRunner.isCoreRunning()) {
                    connected = false
                    status = "اتصال قطع شد: هسته متوقف شد"
                }
            }
        }
    }

    // ───────── قلب، کلیپ‌برد، ساب‌لینک شخصی ─────────

    /** قلب زدن/برداشتن. برداشتن قلب کانفیگ کلیپ‌برد را از حالت معلق خارج می‌کند. */
    fun toggleFavorite(ctx: Context, cfg: VpnConfig) {
        val k = cfg.id
        if (k in favKeys) {
            favRaws.removeAll { keyRaw(it) == k }
            clips.filter { keyRaw(it.raw) == k }.forEach { it.pending = false }
        } else {
            favRaws.add(cfg.rawConfig)
        }
        SingBoxStore.saveFavs(ctx, favRaws)
        SingBoxStore.saveClips(ctx, clips)
        rebuild()
    }

    /** افزودن هر تعداد کانفیگ از کلیپ‌برد؛ بالای لیست و معلق تا تست پینگ یا برداشتن قلب. */
    fun addFromClipboard(ctx: Context) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            status = "کلیپ‌برد خالی است"
            return
        }
        val text = buildString {
            for (i in 0 until clip.itemCount) append(clip.getItemAt(i).coerceToText(ctx)).append('\n')
        }
        val parsed = SubManager.parseText(text)
        if (parsed.isEmpty()) {
            status = "کانفیگ معتبری در کلیپ‌برد پیدا نشد"
            return
        }
        val existing = subConfigs.map { keyOf(it) }.toMutableSet()
        existing += clips.map { keyRaw(it.raw) }
        var added = 0
        for (cfg in parsed.reversed()) {
            val k = keyOf(cfg)
            if (k in existing) continue
            existing += k
            clips.add(0, ClipItem(cfg.rawConfig, pending = true))
            added++
        }
        SingBoxStore.saveClips(ctx, clips)
        rebuild()
        status = if (added == 0) "این کانفیگ‌ها قبلاً در لیست هستند" else "$added کانفیگ از کلیپ‌برد اضافه شد"
    }

    /** حذف کانفیگ کلیپ‌برد با سطل زباله. */
    fun deleteClip(ctx: Context, key: String) {
        clips.removeAll { keyRaw(it.raw) == key }
        favRaws.removeAll { keyRaw(it) == key }
        SingBoxStore.saveClips(ctx, clips)
        SingBoxStore.saveFavs(ctx, favRaws)
        rebuild()
    }

    fun addPersonal(ctx: Context, name: String, url: String) {
        personal = personal + PersonalSub(name, url, enabled = true)
        SingBoxStore.savePersonal(ctx, personal)
    }

    fun editPersonal(ctx: Context, index: Int, name: String, url: String) {
        if (index !in personal.indices) return
        val old = personal[index]
        personal = personal.toMutableList().also { it[index] = old.copy(name = name, url = url) }
        SingBoxStore.savePersonal(ctx, personal)
        if (old.url != url) {
            val cache = SingBoxStore.loadPersonalCache(ctx)
            cache.remove(old.url)
            SingBoxStore.savePersonalCache(ctx, cache)
        }
        reloadSourcesAsync(ctx)
    }

    fun togglePersonal(ctx: Context, index: Int) {
        if (index !in personal.indices) return
        val old = personal[index]
        personal = personal.toMutableList().also { it[index] = old.copy(enabled = !old.enabled) }
        SingBoxStore.savePersonal(ctx, personal)
        reloadSourcesAsync(ctx)
    }

    /** حذف ساب‌لینک شخصی؛ سرورهایش با بروزرسانی بعدی حذف می‌شوند مگر قلب‌دار باشند. */
    fun deletePersonal(ctx: Context, index: Int) {
        if (index !in personal.indices) return
        val removedUrl = personal[index].url
        personal = personal.toMutableList().also { it.removeAt(index) }
        SingBoxStore.savePersonal(ctx, personal)
        val cache = SingBoxStore.loadPersonalCache(ctx)
        cache.remove(removedUrl)
        SingBoxStore.savePersonalCache(ctx, cache)
        reloadSourcesAsync(ctx)
    }

    /** ساب‌لینک‌های ثابت + سرورهای ساب‌لینک‌های شخصی تیک‌خورده (از کش). */
    /** تجزیهٔ منابع (سنگین)؛ باید روی رشتهٔ پس‌زمینه اجرا شود. */
    private fun computeSubConfigs(ctx: Context): List<VpnConfig> {
        val builtin = SubManager.loadCachedConfigs(ctx)
        val cache = SingBoxStore.loadPersonalCache(ctx)
        val enabled = personal.filter { it.enabled }
        val extra = enabled.flatMap { cache[it.url].orEmpty() }.flatMap { SubManager.parseText(it) }
        return builtin + extra
    }

    private fun reloadSources(ctx: Context) {
        subConfigs = computeSubConfigs(ctx)
    }

    /** بعد از تغییر ساب‌لینک شخصی: تجزیه روی پس‌زمینه، سپس فهرست روی رشتهٔ اصلی. */
    private fun reloadSourcesAsync(ctx: Context) {
        scope.launch {
            subConfigs = withContext(Dispatchers.IO) { computeSubConfigs(ctx) }
            rebuild()
        }
    }

    // ───────── اتصال ─────────

    private fun stopIntent(ctx: Context): Intent =
        Intent(ctx, MultiCoreVpnService::class.java).setAction(MultiCoreVpnService.ACTION_STOP)

    private fun savedSel(ctx: Context, awg: Boolean): String? =
        TimeGuard.getSecurePrefs(ctx).getString(if (awg) "singbox_sel_awg" else "singbox_sel_all", null)

    private fun saveSel(ctx: Context, awg: Boolean, key: String) {
        TimeGuard.getSecurePrefs(ctx).edit().putString(if (awg) "singbox_sel_awg" else "singbox_sel_all", key).apply()
    }

    /** انتخاب آخرین سرور همان تب (AmneziaWG یا بقیه)؛ اگر نبود، اولین سرور آن تب. */
    fun selectTab(ctx: Context, awg: Boolean) {
        val list = displayed.filter { (it.coreType == "AmneziaWG") == awg }
        val pick = list.firstOrNull { it.id == savedSel(ctx, awg) } ?: list.firstOrNull() ?: return
        selectedId = pick.id
        saveSel(ctx, awg, pick.id)
    }

    /** بعد از بروزرسانی: اولین سرور هر تب انتخاب می‌شود. */
    private fun resetSelectionToFirst(ctx: Context) {
        val all = displayed.filter { it.coreType != "AmneziaWG" }
        val awg = displayed.filter { it.coreType == "AmneziaWG" }
        all.firstOrNull()?.let { saveSel(ctx, false, it.id) }
        awg.firstOrNull()?.let { saveSel(ctx, true, it.id) }
        (all.firstOrNull() ?: awg.firstOrNull())?.let { selectedId = it.id }
    }

    fun stop(ctx: Context, message: String = "اتصال قطع شد") {
        val active = connected || connecting || connJob != null
        connJob?.cancel()
        connJob = null
        connecting = false
        awgActive = false
        if (!active) return
        CoreEngineRunner.stopCore()
        ctx.startService(stopIntent(ctx))
        connected = false
        status = message
    }

    private suspend fun awaitPortsFree(timeoutMs: Long = 4000L) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val inUse = withContext(Dispatchers.IO) {
                CoreEngineRunner.isLocalPortListening(CoreConfigGenerator.LOCAL_SOCKS_PORT) ||
                    CoreEngineRunner.isLocalPortListening(CoreConfigGenerator.LOCAL_HTTP_PORT)
            }
            if (!inUse) return
            delay(150L)
        }
    }

    fun start(ctx: Context, target: VpnConfig? = selected) {
        val t = target ?: configs.firstOrNull() ?: run {
            status = "سروری برای اتصال وجود ندارد"
            return
        }
        if (t.coreType == "AmneziaWG") {
            startAwg(ctx, t)
            return
        }
        stopGuardIfActive(ctx)
        stopSstpIfActive(ctx)
        if (TorController.state.value.phase != Phase.IDLE) TorController.disconnect()
        val wasActive = connected || connecting
        if (wasActive) stop(ctx, "در حال تغییر سرور…")

        selectedId = t.id
        saveSel(ctx, t.coreType == "AmneziaWG", t.id)
        connecting = true
        status = "در حال اتصال به ${t.name}…"
        connJob = scope.launch {
            ctx.startService(Intent(ctx, MultiCoreVpnService::class.java).apply {
                action = MultiCoreVpnService.ACTION_CONNECTING
                putExtra("SERVER_NAME", t.name)
            })
            if (wasActive) {
                delay(400L)
                awaitPortsFree()
            }
            val ok = CoreEngineRunner.startCore(ctx, t)
            if (!isActive) {
                CoreEngineRunner.stopCore()
                return@launch
            }
            if (!ok) {
                fail(ctx, t)
                return@launch
            }
            val ping = CoreEngineRunner.measureRealTunnelDelay()
            if (!isActive) {
                CoreEngineRunner.stopCore()
                return@launch
            }
            if (ping <= 0) {
                fail(ctx, t)
                return@launch
            }
            setPing(t.id, ping)
            ctx.startService(Intent(ctx, MultiCoreVpnService::class.java).apply {
                putExtra("CORE_NAME", t.coreType)
                putExtra("SERVER_NAME", t.name)
                putExtra("SOCKS_PORT", CoreConfigGenerator.LOCAL_SOCKS_PORT)
                putExtra("SERVER_PING", ping)
            })
            connecting = false
            connected = true
            status = "متصل به ${t.name} · $ping ms"
        }
    }

    private fun startAwg(ctx: Context, t: VpnConfig) {
        stopSstpIfActive(ctx)
        val wasActive = connected || connecting
        if (wasActive) stop(ctx, "در حال تغییر سرور…")
        if (TorController.state.value.phase != Phase.IDLE) TorController.disconnect()
        selectedId = t.id
        saveSel(ctx, t.coreType == "AmneziaWG", t.id)
        connecting = true
        status = "در حال اتصال به ${t.name}…"
        connJob = scope.launch {
            ctx.startService(Intent(ctx, MultiCoreVpnService::class.java).apply {
                action = MultiCoreVpnService.ACTION_CONNECTING
                putExtra("SERVER_NAME", t.name)
            })
            if (wasActive) delay(400L)
            MultiCoreVpnService.awgStatus.value = AwgState.Connecting
            ctx.startService(Intent(ctx, MultiCoreVpnService::class.java).apply {
                putExtra("CORE_NAME", "AmneziaWG")
                putExtra("SERVER_NAME", t.name)
                putExtra("AWG_RAW", t.rawConfig)
            })
            val result = withTimeoutOrNull(30_000L) {
                MultiCoreVpnService.awgStatus.first { it is AwgState.Up || it is AwgState.Failed }
            }
            if (!isActive) return@launch
            connecting = false
            when (result) {
                is AwgState.Up -> {
                    connected = true
                    awgActive = true
                    status = "متصل به ${t.name} (AmneziaWG)"
                }
                is AwgState.Failed -> {
                    connected = false
                    status = "سرور وصل نشد: ${result.message}"
                    ctx.startService(stopIntent(ctx))
                }
                else -> {
                    connected = false
                    status = "سرور وصل نشد: زمان انتظار تمام شد"
                    ctx.startService(stopIntent(ctx))
                }
            }
        }
    }

    fun select(ctx: Context, cfg: VpnConfig) {
        if (connected || connecting) {
            start(ctx, cfg)
        } else {
            selectedId = cfg.id
            saveSel(ctx, cfg.coreType == "AmneziaWG", cfg.id)
            status = "${cfg.name} انتخاب شد"
        }
    }

    private fun fail(ctx: Context, t: VpnConfig) {
        CoreEngineRunner.stopCore()
        ctx.startService(stopIntent(ctx))
        connecting = false
        connected = false
        setPing(t.id, -2L)
        status = "سرور وصل نشد، سرور دیگری را امتحان کنید"
    }

    // ───────── تست پینگ ─────────

    fun stopPing() {
        pingJob?.cancel()
    }

    /** تست پینگ؛ پینگ‌های قبلی پاک می‌شوند و نتیجه‌ها هر ۳۰۰ میلی‌ثانیه روی فهرست می‌نشینند. */
    fun pingAll(ctx: Context) {
        if (configs.isEmpty()) {
            status = "ابتدا سرورها را بروزرسانی کنید"
            return
        }
        if (busy) return
        if (connected || connecting) stop(ctx, "برای تست پینگ، اتصال قطع شد")
        clearPings(ctx)
        // کانفیگ‌های کلیپ‌برد بعد از تست پینگ دیگر معلق نیستند
        clips.forEach { it.pending = false }
        SingBoxStore.saveClips(ctx, clips)

        busy = true
        pinging = true
        val list = configs
        val total = list.size
        var done = 0
        var found = 0
        pendingPings.clear()
        status = "شروع تست پینگ برای $total سرور…"

        pingFlushJob = scope.launch {
            while (pinging) {
                delay(300L)
                flushPings()
            }
        }
        pingJob = scope.launch {
            var completed = false
            try {
                PingTester.pingAllConfigsRealHttp(
                    context = ctx,
                    configs = list,
                    onProgress = { },
                    onResult = { cfg ->
                        scope.launch {
                            done++
                            if (cfg.pingMs > 0) found++
                            pendingPings[cfg.id] = cfg
                            status = "در حال تست: $done از $total · $found سرور پاسخ داد"
                        }
                    },
                )
                completed = true
            } finally {
                flushPings()
                busy = false
                pinging = false
                pingJob = null
                pingFlushJob?.cancel()
                status = when {
                    !completed -> "تست پینگ متوقف شد ($done از $total)"
                    found > 0 -> "تست تمام شد: $found سرور پاسخ داد"
                    else -> "هیچ سروری در این لحظه پاسخ نداد"
                }
            }
        }
    }

    // ───────── بروزرسانی دستی ─────────

    /** بروزرسانی همهٔ ساب‌لینک‌ها (ثابت + شخصی فعال)؛ پینگ‌ها پاک می‌شوند، قلب و کلیپ‌برد می‌مانند. */
    fun updateSubscriptions(ctx: Context) {
        if (busy) return
        clearPings(ctx)
        busy = true
        status = "در حال دریافت سرورها از ساب‌لینک…"
        scope.launch {
            try {
                val count = withTimeout(90_000L) { SubRefresh.refreshAll(ctx) }
                subConfigs = withContext(Dispatchers.IO) { computeSubConfigs(ctx) }
                rebuild()
                resetSelectionToFirst(ctx)
                if (count > 0) {
                    SubUpdate.markUpdated(ctx)
                    loadedStamp = SubUpdate.lastUpdatedMs(ctx)
                    lastUpdate = formatTime(loadedStamp)
                }
                status = if (configs.isEmpty()) "سروری در ساب‌لینک پیدا نشد" else "${configs.size} سرور در لیست است"
            } catch (e: Exception) {
                status = "دریافت ساب‌لینک ناموفق بود؛ اتصال اینترنت را بررسی کنید"
            } finally {
                busy = false
            }
        }
    }
}
