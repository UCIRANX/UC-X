package com.multivpn.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * اندروید خطاهای IKE را به برنامه‌ای که پروفایل را ساخته با broadcast اعلام می‌کند؛
 * این کلاس آن‌ها را در لاگ ثبت می‌کند و وضعیت را به‌روز می‌کند.
 */
object Ikev2Events {
    const val IDLE = "idle"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val FAILED = "failed"

    /** مهلت انتظار برای برقراری شبکهٔ VPN پس از شروع. */
    private const val TIMEOUT_MS = 60_000L
    private const val CHANNEL_ID = "ikev2_vpn"
    private const val NOTIFICATION_ID = 2301
    private const val CATEGORY_NETWORK_ERROR = "android.net.category.EVENT_NETWORK_ERROR"

    private val flow = MutableStateFlow(IDLE to "")

    /** (وضعیت، توضیح) برای نمایش در UI. */
    val status: StateFlow<Pair<String, String>> = flow.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
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

    /** ثبت گیرندهٔ رویدادها؛ چندبار صدا زدن بی‌اثر است. */
    fun register(ctx: Context) {
        appCtx = ctx.applicationContext
        if (registered) return
        val filter = IntentFilter(VpnManager.ACTION_VPN_MANAGER_EVENT).apply {
            addCategory(VpnManager.CATEGORY_EVENT_IKE_ERROR)
            addCategory(CATEGORY_NETWORK_ERROR)
            addCategory("android.net.category.EVENT_DEACTIVATED_BY_USER")
            addCategory("android.net.category.EVENT_ALWAYS_ON_STATE_CHANGED")
        }
        
        // اصلاح پرچم ثبت BroadcastReceiver جهت سازگاری کامل با اندروید ۱۲ به بالا
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }

        runCatching {
            ContextCompat.registerReceiver(
                ctx.applicationContext,
                receiver,
                filter,
                flags
            )
            registered = true
            AppLogger.log("IKEv2", "گیرندهٔ رویدادهای سیستم با موفقیت ثبت شد")
        }.onFailure { e ->
            AppLogger.log("IKEv2", "خطا در ثبت گیرنده رویداد: ${e.message}")
        }
    }

    /** بعد از درخواست شروع: وضعیت در حال اتصال و مهلت انتظار برای شبکه. */
    fun onStarted() {
        attempt++
        val mine = attempt
        flow.value = CONNECTING to ""
        AppLogger.log("IKEv2", "وضعیت: در حال اتصال (تلاش $mine)")
        show("IKEv2 · در حال اتصال…", "منتظر برقراری شبکهٔ VPN", true)
        cancelTimeout()
        val r = Runnable {
            if (attempt == mine && flow.value.first == CONNECTING) {
                fail("شبکهٔ VPN پس از ${TIMEOUT_MS / 1000} ثانیه برقرار نشد")
            }
        }
        timeout = r
        main.postDelayed(r, TIMEOUT_MS)
    }

    fun onNetworkUp() {
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
