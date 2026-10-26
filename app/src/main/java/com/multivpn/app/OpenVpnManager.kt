package com.multivpn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.multivpn.app.log.AppLogger
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.StringReader

/**
 * اتصال OpenVPN از بخش پلاس.
 *
 * نکته‌ی مهم: این شیء هنگام شروع برنامه هیچ کلاس کتابخانه‌ی OpenVPN را بارگذاری نمی‌کند.
 * هیچ‌کدام از کلاس‌های ics (مثل VpnStatus و کتابخانه‌های native) تا لحظه‌ی اولین اتصال
 * OpenVPN لود نمی‌شوند؛ وگرنه کرش در شروع برنامه رخ می‌دهد.
 */
object OpenVpnManager {
    const val IDLE = "idle"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val PAUSED = "paused"
    const val FAILED = "failed"

    private val flow = MutableStateFlow(IDLE to "")
    /** (وضعیت، پیام آخر) برای نمایش در UI. */
    val status: StateFlow<Pair<String, String>> = flow.asStateFlow()

    @Volatile private var initialized = false

    /** شنونده‌ی وضعیت هسته؛ فقط در اولین اتصال ساخته می‌شود. */
    private val listener by lazy {
        object : de.blinkt.openvpn.core.VpnStatus.StateListener {
            // کتابخانه‌ی OpenVPN ممکن است هر کدام از این مقادیر را null بفرستد؛
            // پارامترها باید nullable باشند تا کاتلین با NullPointerException کرش نکند.
            override fun updateState(
                state: String?,
                logmessage: String?,
                localizedResId: Int,
                level: ConnectionStatus?,
                intent: Intent?,
            ) {
                // نگاشت بر اساس level (نه نام وضعیت) تا وضعیت‌هایی مثل
                // لغو اجازه‌ی VPN یا timeout به‌درستی IDLE/FAILED شوند و UI گیر نکند.
                val mapped = when (level) {
                    ConnectionStatus.LEVEL_CONNECTED -> CONNECTED
                    ConnectionStatus.LEVEL_VPNPAUSED,
                    ConnectionStatus.LEVEL_NONETWORK -> PAUSED
                    ConnectionStatus.LEVEL_AUTH_FAILED -> FAILED
                    ConnectionStatus.LEVEL_NOTCONNECTED -> IDLE
                    else -> CONNECTING
                }
                flow.value = mapped to (logmessage ?: "")
            }

            override fun setConnectedVPN(uuid: String?) {}
        }
    }

    /** مقداردهی کتابخانه‌ی OpenVPN؛ فقط هنگام اتصال صدا زده می‌شود. */
    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        de.blinkt.openvpn.core.OvpnBootstrap.init(ctx)
        de.blinkt.openvpn.core.VpnStatus.addStateListener(listener)
        val appCtx = ctx.applicationContext
        // خروجی هسته OpenVPN را هم در لاگ داخل برنامه می‌نویسیم تا علت قطع دیده شود
        de.blinkt.openvpn.core.VpnStatus.addLogListener { item ->
            runCatching { AppLogger.log("OpenVPN-core", item.getString(appCtx)) }
        }
        initialized = true
    }

    fun isActive(): Boolean = flow.value.first != IDLE

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var attemptId = 0

    /** اگر ۳۰ ثانیه بعد از شروع هنوز در حال اتصال باشیم، اتصال را قطع و علت را نشان می‌دهیم. */
    private fun armWatchdog(app: Context, port: String) {
        val id = ++attemptId
        mainHandler.postDelayed({
            if (id == attemptId && flow.value.first == CONNECTING) {
                AppLogger.log("OpenVPN", "بدون پاسخ پس از ۳۰ ثانیه (پورت $port)")
                flow.value = FAILED to "سرور پاسخ نداد (پورت $port)"
                disconnect(app)
            }
        }, 30_000L)
    }

    /** null یعنی شروع شد؛ وگرنه متن خطا. */
    fun connect(ctx: Context, s: ProtoServer): String? {
        try {
            init(ctx)
        } catch (e: Throwable) {
            AppLogger.log("OpenVPN", "مقداردهی ناموفق: ${e.message}")
            return "موتور OpenVPN راه‌اندازی نشد: ${e.message}"
        }
        val text = try {
            String(Base64.decode(s.config, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return "کانفیگ OpenVPN نامعتبر است"
        }
        val profile = try {
            val parser = de.blinkt.openvpn.core.ConfigParser()
            parser.parseConfig(StringReader(text))
            parser.convertProfile()
        } catch (e: Exception) {
            AppLogger.log("OpenVPN", "خطا در تجزیهٔ کانفیگ: ${e.message}")
            return "کانفیگ قابل خواندن نیست: ${e.message}"
        }
        profile.mName = s.label
        val remote = Regex("(?m)^\\s*remote\\s+(\\S+)\\s+(\\d+)").find(text)
        val remoteHost = remote?.groupValues?.get(1) ?: "?"
        val remotePort = remote?.groupValues?.get(2) ?: "?"
        AppLogger.log("OpenVPN", "سرور $remoteHost:$remotePort")
        de.blinkt.openvpn.core.ProfileManager.setTemporaryProfile(ctx, profile)
        AppLogger.log("OpenVPN", "شروع OpenVPN ${s.host} · ${s.country.ifBlank { "-" }}")
        flow.value = CONNECTING to ""
        // جلوگیری از کرش: صفحهٔ LogWindow کتابخانه در manifest تعریف نشده است.
        de.blinkt.openvpn.core.Preferences.getDefaultSharedPreferences(ctx)
            .edit().putBoolean("showlogwindow", false).apply()
        ctx.startActivity(
            Intent(ctx, de.blinkt.openvpn.LaunchVPN::class.java)
                .putExtra(de.blinkt.openvpn.LaunchVPN.EXTRA_KEY, profile.getUUIDString())
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        armWatchdog(ctx.applicationContext, remotePort)
        return null
    }

    /** قطع اتصال فعال با فراخوانی stopVPN روی سرویس هسته. */
    fun disconnect(ctx: Context) {
        AppLogger.log("OpenVPN", "قطع اتصال OpenVPN")
        val intent = Intent(ctx, de.blinkt.openvpn.core.OpenVPNService::class.java)
            .setAction(de.blinkt.openvpn.core.OpenVPNService.START_SERVICE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching {
                    de.blinkt.openvpn.core.IOpenVPNServiceInternal.Stub.asInterface(service).stopVPN(false)
                }
                runCatching { ctx.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        runCatching { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
    }
}
