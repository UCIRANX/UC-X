package com.multivpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnManager
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
    private const val TIMEOUT_MS = 30_000L
    private const val CATEGORY_NETWORK_ERROR = "android.net.category.EVENT_NETWORK_ERROR"

    private val flow = MutableStateFlow(IDLE to "")

    /** (وضعیت، توضیح) برای نمایش در UI. */
    val status: StateFlow<Pair<String, String>> = flow.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var registered = false
    private var attempt = 0

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
        if (registered) return
        val filter = IntentFilter(VpnManager.ACTION_VPN_MANAGER_EVENT)
        filter.addCategory(VpnManager.CATEGORY_EVENT_IKE_ERROR)
        filter.addCategory(CATEGORY_NETWORK_ERROR)
        ContextCompat.registerReceiver(
            ctx.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        AppLogger.log("IKEv2", "گیرندهٔ رویدادهای سیستم ثبت شد")
    }

    /** بعد از درخواست شروع: وضعیت در حال اتصال و مهلت انتظار برای شبکه. */
    fun onStarted() {
        attempt++
        val mine = attempt
        flow.value = CONNECTING to ""
        AppLogger.log("IKEv2", "وضعیت: در حال اتصال (تلاش $mine)")
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
    }

    fun onNetworkDown() {
        if (flow.value.first == CONNECTED) {
            flow.value = IDLE to ""
            AppLogger.log("IKEv2", "وضعیت: قطع")
        }
    }

    fun onStopped() {
        cancelTimeout()
        flow.value = IDLE to ""
    }

    fun fail(message: String) {
        cancelTimeout()
        flow.value = FAILED to message
        AppLogger.log("IKEv2", "خطا: $message")
    }

    private fun cancelTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }
}
