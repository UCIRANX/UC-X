package com.multivpn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import com.multivpn.app.log.AppLogger
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
            override fun updateState(
                state: String,
                logmessage: String,
                localizedResId: Int,
                level: de.blinkt.openvpn.core.ConnectionStatus,
                intent: Intent?,
            ) {
                val mapped = when (state) {
                    "CONNECTED" -> CONNECTED
                    "USERPAUSE", "SCREENOFF", "NONETWORK" -> PAUSED
                    "AUTH_FAILED" -> FAILED
                    "DISCONNECTED", "EXITING", "NOPROCESS" -> IDLE
                    else -> CONNECTING
                }
                flow.value = mapped to logmessage
            }

            override fun setConnectedVPN(uuid: String) {}
        }
    }

    /** مقداردهی کتابخانه‌ی OpenVPN؛ فقط هنگام اتصال صدا زده می‌شود. */
    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        de.blinkt.openvpn.core.OvpnBootstrap.init(ctx)
        de.blinkt.openvpn.core.VpnStatus.addStateListener(listener)
        initialized = true
    }

    fun isActive(): Boolean = flow.value.first != IDLE

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
        de.blinkt.openvpn.core.ProfileManager.setTemporaryProfile(ctx, profile)
        AppLogger.log("OpenVPN", "شروع OpenVPN ${s.host} · ${s.country.ifBlank { "-" }}")
        flow.value = CONNECTING to ""
        ctx.startActivity(
            Intent(ctx, de.blinkt.openvpn.LaunchVPN::class.java)
                .putExtra(de.blinkt.openvpn.LaunchVPN.EXTRA_KEY, profile.getUUIDString())
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
