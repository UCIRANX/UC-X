package com.multivpn.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * وضعیت و عملیات حالت سینگ‌باکس.
 * داخل یک object است تا با عوض شدن تب قطع نشود.
 * پینگ هر سرور با هش خود کانفیگ ذخیره می‌شود، پس بعد از بروزرسانی ساب‌لینک و ریستارت پاک نمی‌شود.
 */
object SingBoxController {
    private const val PING_KEY = "singbox_ping_cache"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connJob: Job? = null
    private var loaded = false
    private var appCtx: Context? = null
    private var loadedStamp = 0L
    private val pendingPings = LinkedHashMap<String, VpnConfig>()

    var configs: List<VpnConfig> by mutableStateOf<List<VpnConfig>>(emptyList())
        private set

    /** فهرست نمایشی: سرورهای دارای پینگ بر اساس کمترین پینگ، بقیه به ترتیب اصلی. */
    var displayed: List<VpnConfig> by mutableStateOf<List<VpnConfig>>(emptyList())
        private set

    var selectedId: String? by mutableStateOf<String?>(null)
        private set
    var connected: Boolean by mutableStateOf(false)
        private set
    var connecting: Boolean by mutableStateOf(false)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var status: String by mutableStateOf("")
        private set
    /** زمان آخرین بروزرسانی ساب‌لینک (متن آماده نمایش). */
    var lastUpdate: String by mutableStateOf("")
        private set

    val selected: VpnConfig?
        get() = configs.firstOrNull { it.id == selectedId }

    /** توقف موقت: تونل بسته ولی نشست فعال است. */
    var paused: Boolean by mutableStateOf(false)
        private set

    fun resume(ctx: Context) {
        if (!paused) return
        status = "در حال اتصال مجدد…"
        ctx.startService(Intent(ctx, MultiCoreVpnService::class.java).setAction(MultiCoreVpnService.ACTION_RESUME))
    }

    fun reportStatus(msg: String) {
        status = msg
    }

    // ───────── ذخیرهٔ پینگ ─────────

    private fun keyOf(c: VpnConfig): String =
        MessageDigest.getInstance("SHA-1").digest(c.rawConfig.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun readPingMap(ctx: Context): JSONObject =
        runCatching { JSONObject(TimeGuard.getSecurePrefs(ctx).getString(PING_KEY, "{}") ?: "{}") }
            .getOrElse { JSONObject() }

    private fun withSavedPings(list: List<VpnConfig>): List<VpnConfig> {
        val ctx = appCtx ?: return list
        val map = readPingMap(ctx)
        return list.map { c ->
            val k = keyOf(c)
            if (map.has(k)) c.copy(pingMs = map.getLong(k)) else c
        }
    }

    private fun persistPings(list: List<VpnConfig>) {
        val ctx = appCtx ?: return
        val map = readPingMap(ctx)
        var changed = false
        for (c in list) {
            if (c.pingMs == -1L) continue
            val k = keyOf(c)
            if (!map.has(k) || map.getLong(k) != c.pingMs) {
                map.put(k, c.pingMs)
                changed = true
            }
        }
        if (changed) TimeGuard.getSecurePrefs(ctx).edit().putString(PING_KEY, map.toString()).apply()
    }

    // ───────── فهرست ─────────

    private fun applyList(list: List<VpnConfig>) {
        configs = list
        persistPings(list)
        displayed = list.withIndex().sortedWith(
            compareBy<IndexedValue<VpnConfig>>(
                { if (it.value.pingMs > 0) 0 else 1 },
                { if (it.value.pingMs > 0) it.value.pingMs else 0L },
                { it.index },
            )
        ).map { it.value }
    }

    /** جایگزین کردن فهرست (بعد از بروزرسانی) و نگه داشتن سرور انتخابی با هش کانفیگ. */
    private fun replaceConfigs(list: List<VpnConfig>) {
        val prevKey = selected?.let { keyOf(it) }
        applyList(withSavedPings(list))
        selectedId = configs.firstOrNull { keyOf(it) == prevKey }?.id ?: configs.firstOrNull()?.id
    }

    private fun setPing(id: String, ping: Long) {
        applyList(configs.map { if (it.id == id) it.copy(pingMs = ping) else it })
    }

    private fun formatTime(ms: Long): String =
        if (ms <= 0L) "" else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ms))

    /** اگر ساب‌لینک در پس‌زمینه بروز شده، فهرست را از روی کش تازه می‌خواند. */
    fun refreshFromStore(ctx: Context) {
        val ts = SubUpdate.lastUpdatedMs(ctx)
        if (ts == loadedStamp || busy) return
        loadedStamp = ts
        lastUpdate = formatTime(ts)
        val list = SubManager.loadCachedConfigs(ctx)
        if (list.isNotEmpty()) replaceConfigs(list)
    }

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        loadedStamp = SubUpdate.lastUpdatedMs(ctx)
        lastUpdate = formatTime(loadedStamp)
        replaceConfigs(SubManager.loadCachedConfigs(ctx))
        status = if (configs.isEmpty()) "ابتدا سرورها را بروزرسانی کنید" else "آماده اتصال"
        scope.launch {
            MultiCoreVpnService.pausedFlow.collect { p ->
                paused = p
                if (connected) status = if (p) "متوقف موقت" else "متصل"
            }
        }
        // اگر هسته بیرون از برنامه بسته شود، وضعیت باید درست شود
        scope.launch {
            while (isActive) {
                delay(1000L)
                if (connected && !CoreEngineRunner.isCoreRunning()) {
                    connected = false
                    status = "اتصال قطع شد: هسته متوقف شد"
                }
            }
        }
    }

    // ───────── اتصال ─────────

    private fun stopIntent(ctx: Context): Intent =
        Intent(ctx, MultiCoreVpnService::class.java).setAction(MultiCoreVpnService.ACTION_STOP)

    fun stop(ctx: Context, message: String = "اتصال قطع شد") {
        val active = connected || connecting || connJob != null
        connJob?.cancel()
        connJob = null
        connecting = false
        if (!active) return
        CoreEngineRunner.stopCore()
        ctx.startService(stopIntent(ctx))
        connected = false
        status = message
    }

    /** صبر می‌کند تا پورت‌های هسته قبلی واقعاً آزاد شوند؛ وگرنه اتصال جدید شکست می‌خورد. */
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
        if (TorController.state.value.phase != Phase.IDLE) TorController.disconnect()
        val wasActive = connected || connecting
        if (wasActive) stop(ctx, "در حال تغییر سرور…")

        selectedId = t.id
        connecting = true
        status = "در حال اتصال به ${t.name}…"
        connJob = scope.launch {
            // نوتیفیکیشن همان لحظهٔ شروع اتصال نشان داده شود
            ctx.startService(Intent(ctx, MultiCoreVpnService::class.java).apply {
                action = MultiCoreVpnService.ACTION_CONNECTING
                putExtra("SERVER_NAME", t.name)
            })
            if (wasActive) {
                // دقیقاً مثل قطع کامل و بعد اتصال: اول تونل قبلی بسته شود
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

    /** انتخاب سرور: اگر وصل هستیم، همان کار «قطع، انتخاب، اتصال» انجام می‌شود؛ وگرنه فقط انتخاب. */
    fun select(ctx: Context, cfg: VpnConfig) {
        if (connected || connecting) {
            start(ctx, cfg)
        } else {
            selectedId = cfg.id
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

    private fun flushPings() {
        if (pendingPings.isEmpty()) return
        val byId = pendingPings.toMap()
        pendingPings.clear()
        applyList(configs.map { byId[it.id] ?: it })
    }

    /** تست پینگ واقعی؛ نتایج هر ۳۰۰ میلی‌ثانیه یک‌جا روی فهرست نشست می‌کنند تا لیست روان بماند. */
    fun pingAll(ctx: Context) {
        if (configs.isEmpty()) {
            status = "ابتدا سرورها را بروزرسانی کنید"
            return
        }
        if (busy) return
        if (connected || connecting) stop(ctx, "برای تست پینگ، اتصال قطع شد")

        busy = true
        val list = configs
        val total = list.size
        var done = 0
        var found = 0
        pendingPings.clear()
        status = "شروع تست پینگ برای $total سرور…"

        scope.launch {
            while (busy) {
                delay(300L)
                flushPings()
            }
        }
        scope.launch {
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
                flushPings()
                status = if (found > 0) "تست تمام شد: $found سرور پاسخ داد" else "هیچ سروری در این لحظه پاسخ نداد"
            } finally {
                busy = false
            }
        }
    }

    // ───────── بروزرسانی دستی ─────────

    fun updateSubscriptions(ctx: Context) {
        if (busy) return
        busy = true
        status = "در حال دریافت سرورها از ساب‌لینک…"
        scope.launch {
            try {
                val fetched = SubManager.fetchAndParseSub(ctx)
                replaceConfigs(fetched)
                if (fetched.isNotEmpty()) {
                    SubUpdate.markUpdated(ctx)
                    loadedStamp = SubUpdate.lastUpdatedMs(ctx)
                    lastUpdate = formatTime(loadedStamp)
                }
                status = if (fetched.isEmpty()) "سروری در ساب‌لینک پیدا نشد" else "${fetched.size} سرور دریافت شد"
            } catch (e: Exception) {
                status = "دریافت ساب‌لینک ناموفق بود؛ اتصال اینترنت را بررسی کنید"
            } finally {
                busy = false
            }
        }
    }
}
