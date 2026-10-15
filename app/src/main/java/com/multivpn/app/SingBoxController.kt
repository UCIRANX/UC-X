package com.multivpn.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multivpn.app.core.CoreConfigGenerator
import com.multivpn.app.core.CoreEngineRunner
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * وضعیت و عملیات حالت Sing-Box.
 * داخل یک object است تا با عوض شدن تب (VPN ↔ تور) قطع نشود؛ فقط خود کاربر یا خطای واقعی آن را تغییر می‌دهد.
 */
object SingBoxController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connJob: Job? = null
    private var pingJob: Job? = null
    private var loaded = false
    private var counter = 0

    var configs: List<VpnConfig> by mutableStateOf<List<VpnConfig>>(emptyList())
        private set
    var selectedId: String? by mutableStateOf<String?>(null)
        private set
    /** ترتیب رسیدن پینگ‌های موفق؛ برای نمایش زنده به همان ترتیب. */
    var order: Map<String, Int> by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var connected: Boolean by mutableStateOf(false)
        private set
    var connecting: Boolean by mutableStateOf(false)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var status: String by mutableStateOf("")
        private set

    val selected: VpnConfig?
        get() = configs.firstOrNull { it.id == selectedId }

    fun setStatus(msg: String) {
        status = msg
    }

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        configs = SubManager.loadCachedConfigs(ctx)
        selectedId = configs.firstOrNull()?.id
        status = if (configs.isEmpty()) "ابتدا سرورها را بروزرسانی کنید" else "آماده اتصال"
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

    fun start(ctx: Context, target: VpnConfig? = selected) {
        val t = target ?: configs.firstOrNull()
        if (t == null) {
            status = "سروری برای اتصال وجود ندارد"
            return
        }
        if (TorController.state.value.phase != Phase.IDLE) TorController.disconnect()
        if (connected || connecting) stop(ctx, "در حال تغییر سرور…")

        selectedId = t.id
        connecting = true
        status = "در حال اتصال به ${t.name}…"
        connJob = scope.launch {
            val ok = CoreEngineRunner.startCore(ctx, t)
            if (!isActive) {
                CoreEngineRunner.stopCore()
                return@launch
            }
            if (!ok) {
                fail(ctx, t, "سرور وصل نشد: ${CoreEngineRunner.lastErrorLog.ifBlank { "هسته اجرا نشد" }}")
                return@launch
            }
            val ping = CoreEngineRunner.measureRealTunnelDelay()
            if (!isActive) {
                CoreEngineRunner.stopCore()
                return@launch
            }
            if (ping <= 0) {
                fail(ctx, t, "سرور وصل شد ولی اینترنت از آن عبور نکرد؛ سرور دیگری را امتحان کنید")
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

    /** انتخاب سرور: اگر وصل هستیم، به سرور جدید وصل می‌شویم؛ وگرنه فقط انتخاب می‌شود. */
    fun select(ctx: Context, cfg: VpnConfig) {
        if (connected || connecting) start(ctx, cfg) else {
            selectedId = cfg.id
            status = "${cfg.name} انتخاب شد"
        }
    }

    private fun fail(ctx: Context, t: VpnConfig, msg: String) {
        CoreEngineRunner.stopCore()
        ctx.startService(stopIntent(ctx))
        connecting = false
        connected = false
        setPing(t.id, -2L)
        status = msg
    }

    private fun setPing(id: String, ping: Long) {
        configs = configs.map { if (it.id == id) it.copy(pingMs = ping) else it }
        if (ping > 0 && !order.containsKey(id)) {
            counter++
            order = order + (id to counter)
        }
    }

    /** تست پینگ واقعی؛ نتیجه‌ی هر سرور همان لحظه‌ای که آماده می‌شود نمایش داده می‌شود. */
    fun pingAll(ctx: Context) {
        if (configs.isEmpty()) {
            status = "ابتدا سرورها را بروزرسانی کنید"
            return
        }
        if (busy) return
        if (connected || connecting) stop(ctx, "برای تست پینگ، اتصال قطع شد")

        busy = true
        order = emptyMap()
        counter = 0
        val list = configs
        val total = list.size
        var done = 0
        var found = 0
        status = "شروع تست پینگ برای $total سرور…"
        pingJob = scope.launch {
            try {
                PingTester.pingAllConfigsRealHttp(
                    context = ctx,
                    configs = list,
                    onProgress = { },
                    onResult = { cfg ->
                        scope.launch {
                            done++
                            if (cfg.pingMs > 0) found++
                            setPing(cfg.id, cfg.pingMs)
                            status = "در حال تست: $done از $total · $found سرور پاسخ داد"
                        }
                    },
                )
                status = if (found > 0) "تست تمام شد: $found سرور پاسخ داد" else "هیچ سروری در این لحظه پاسخ نداد"
            } finally {
                busy = false
            }
        }
    }

    fun updateSubscriptions(ctx: Context) {
        if (busy) return
        busy = true
        status = "در حال دریافت سرورها از ساب‌لینک…"
        pingJob = scope.launch {
            try {
                val fetched = SubManager.fetchAndParseSub(ctx)
                configs = fetched
                order = emptyMap()
                counter = 0
                if (fetched.none { it.id == selectedId }) selectedId = fetched.firstOrNull()?.id
                status = if (fetched.isEmpty()) "سروری در ساب‌لینک پیدا نشد" else "${fetched.size} سرور دریافت شد"
            } catch (e: Exception) {
                status = "دریافت ساب‌لینک ناموفق بود؛ اتصال اینترنت را بررسی کنید"
            } finally {
                busy = false
            }
        }
    }
}
