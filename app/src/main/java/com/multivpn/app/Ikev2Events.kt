package com.multivpn.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.multivpn.app.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * وضعیت IKEv2 و رویدادهای سیستم.
 * شنونده‌ی شبکه‌ی VPN و بررسی دوره‌ای داخل همین کلاس است و به صفحهٔ UI وابسته نیست،
 * تا وضعیت «متصل» حتی اگر صفحه بسته یا بازسازی شود به‌موقع ثبت شود.
 */
object Ikev2Events {
    const val IDLE = "idle"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val FAILED = "failed"

    /** مهلت انتظار برای برقراری شبکهٔ VPN پس از شروع. */
    private const val TIMEOUT_MS = 60_000L
    /** فاصلهٔ بررسی دوره‌ای وجود شبکهٔ VPN در حین اتصال. */
    private const val POLL_MS = 1_000L
    private const val CHANNEL_ID = "ikev2_vpn"
    private const val NOTIFICATION_ID = 2301
    private const val CATEGORY_NETWORK_ERROR = "android.net.category.EVENT_NETWORK_ERROR"

    private val flow = MutableStateFlow(IDLE to "")

    /** (وضعیت، توضیح) برای نمایش در UI. */
    val status: StateFlow<Pair<String, String>> = flow.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var poller: Runnable? = null
    private var vpnCb: ConnectivityManager.NetworkCallback? = null
    private var defaultCb: ConnectivityManager.NetworkCallback? = null
    private var registered = false
    private var attempt = 0
    private var appCtx: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val cats = intent.categories?.joinToString(",") ?: "-"
            val cls = intent.getIntExtra(VpnManager.EXTRA_ERROR_CLASS, -1)
            val code = intent.getIntExtra(VpnManager.EXTRA_ERROR_CODE, -1)
            AppLogger.log("IKEv2-sys", "رویداد سیستم: دسته=$cats کلاس=$cls کد=$code")
            if (cats.contains(VpnManager.CATEGORY_EVENT_IKE_ERROR) || cats.contains(CATEGORY_NETWORK_ERROR)) {
                fail("خطای سیستم: کد $code (کلاس $cls) — جزئیات در لاگ")
            }
        }
    }

    /** ثبت گیرندهٔ رویدادهای سیستم؛ چندبار صدا زدن بی‌اثر است. */
    fun register(ctx: Context) {
        appCtx = ctx.applicationContext
        if (registered) return
        val filter = IntentFilter(VpnManager.ACTION_VPN_MANAGER_EVENT).apply {
            addCategory(VpnManager.CATEGORY_EVENT_IKE_ERROR)
            addCategory(CATEGORY_NETWORK_ERROR)
            addCategory("android.net.category.EVENT_DEACTIVATED_BY_USER")
            addCategory("android.net.category.EVENT_ALWAYS_ON_STATE_CHANGED")
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }
        runCatching {
            ContextCompat.registerReceiver(ctx.applicationContext, receiver, filter, flags)
            registered = true
            AppLogger.log("IKEv2", "گیرندهٔ رویدادهای سیستم با موفقیت ثبت شد")
        }.onFailure { e ->
            AppLogger.log("IKEv2", "خطا در ثبت گیرنده رویداد: ${e.message}")
        }
    }

    /** بعد از درخواست شروع: وضعیت در حال اتصال، شنونده‌ها، بررسی دوره‌ای و مهلت انتظار. */
    fun onStarted() {
        attempt++
        val mine = attempt
        flow.value = CONNECTING to ""
        AppLogger.log("IKEv2", "وضعیت: در حال اتصال (تلاش $mine)")
        show("IKEv2 · در حال اتصال…", "منتظر برقراری شبکهٔ VPN", true)
        startWatch()
        cancelTimeout()
        val r = Runnable {
            if (attempt == mine && flow.value.first == CONNECTING) {
                fail("شبکهٔ VPN پس از ${TIMEOUT_MS / 1000} ثانیه برقرار نشد")
            }
        }
        timeout = r
        main.postDelayed(r, TIMEOUT_MS)
        schedulePoll(mine)
    }

    /** شنونده‌ی شبکهٔ VPN و شنونده‌ی شبکهٔ پیش‌فرض (فقط برای لاگ تشخیص قطع داده). */
    private fun startWatch() {
        val c = appCtx ?: return
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        if (vpnCb == null) {
            vpnCb = watchVpn(c) { up -> if (up) onNetworkUp() else onNetworkDown() }
        }
        if (defaultCb == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    AppLogger.log(
                        "IKEv2-net",
                        "شبکهٔ پیش‌فرض: VPN=${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} " +
                            "اینترنت=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                            "تأییدشده=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
                    )
                }

                override fun onLost(network: Network) {
                    AppLogger.log("IKEv2-net", "شبکهٔ پیش‌فرض قطع شد")
                }
            }
            runCatching { cm.registerDefaultNetworkCallback(cb) }
                .onSuccess { defaultCb = cb }
                .onFailure { AppLogger.log("IKEv2-net", "ثبت شنونده شبکهٔ پیش‌فرض ناموفق: ${it.message}") }
        }
    }

    private fun stopWatch() {
        val c = appCtx ?: return
        val cm = c.getSystemService(ConnectivityManager::class.java)
        vpnCb?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        defaultCb?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        vpnCb = null
        defaultCb = null
        poller?.let { main.removeCallbacks(it) }
        poller = null
    }

    /** هر ثانیه بررسی می‌کند آیا شبکهٔ VPN فعال شده؛ مستقل از شنونده‌ها. */
    private fun schedulePoll(mine: Int) {
        val r = object : Runnable {
            override fun run() {
                if (attempt != mine || flow.value.first != CONNECTING) return
                val c = appCtx
                if (c != null && vpnUp(c)) {
                    AppLogger.log("IKEv2", "شبکهٔ VPN با بررسی دوره‌ای تشخیص داده شد")
                    onNetworkUp()
                    return
                }
                main.postDelayed(this, POLL_MS)
            }
        }
        poller = r
        main.postDelayed(r, POLL_MS)
    }

    private fun vpnUp(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun onNetworkUp() {
        if (flow.value.first == CONNECTED) return
        cancelTimeout()
        flow.value = CONNECTED to ""
        AppLogger.log("IKEv2", "وضعیت: متصل")
        show("IKEv2 · متصل", "شبکهٔ VPN فعال است", true)
    }

    fun onNetworkDown() {
        if (flow.value.first == CONNECTED) {
            flow.value = IDLE to ""
            AppLogger.log("IKEv2", "وضعیت: قطع")
            cancelNotice()
        }
    }

    fun onStopped() {
        cancelTimeout()
        stopWatch()
        flow.value = IDLE to ""
        cancelNotice()
    }

    fun fail(message: String) {
        cancelTimeout()
        appCtx?.let { stopIkev2(it) }
        flow.value = FAILED to message
        AppLogger.log("IKEv2", "خطا: $message")
        cancelNotice()
    }

    /** نوتیفیکیشن وضعیت IKEv2. */
    private fun show(title: String, text: String, ongoing: Boolean) {
        val c = appCtx ?: return
        VpnNotify.channel(c, CHANNEL_ID, "IKEv2")
        val n = VpnNotify.build(c, CHANNEL_ID, title, text, emptyList(), ongoing, VpnNotify.openAppIntent(c, 30))
        c.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun cancelNotice() {
        appCtx?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun cancelTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }
}
