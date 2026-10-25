package com.multivpn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import com.multivpn.app.log.AppLogger
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.OvpnBootstrap
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.StringReader

/**
 * اتصال OpenVPN از بخش پلاس.
 * کانفیگ (base64 از ساب‌لینک) تجزیه می‌شود، موقت ثبت می‌شود، و LaunchVPN
 * اجازهٔ سیستم را می‌گیرد و سرویس هستهٔ ics-openvpn را راه می‌اندازد.
 * وضعیت از VpnStatus خوانده می‌شود.
 */
object OpenVpnManager : VpnStatus.StateListener {
    const val IDLE = "idle"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val PAUSED = "paused"
    const val FAILED = "failed"

    private val flow = MutableStateFlow(IDLE to "")
    /** (وضعیت، پیام آخر) برای نمایش در UI. */
    val status: StateFlow<Pair<String, String>> = flow.asStateFlow()

    private var listening = false

    /** مقداردهی یک‌باره‌ی هسته و ثبت شنونده‌ی وضعیت. */
    @Synchronized
    fun init(ctx: Context) {
        OvpnBootstrap.init(ctx)
        if (!listening) {
            VpnStatus.addStateListener(this)
            listening = true
        }
    }

    override fun updateState(
        state: String,
        logmessage: String,
        localizedResId: Int,
        level: ConnectionStatus,
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

    fun isActive(): Boolean = flow.value.first != IDLE

    /** این نسخهٔ هسته این متد را هم صدا می‌زند؛ فعلاً به پروفایل فعال نیازی نداریم. */
    override fun setConnectedVPN(uuid: String) {}

    /** null یعنی شروع شد؛ وگرنه متن خطا. */
    fun connect(ctx: Context, s: ProtoServer): String? {
        init(ctx)
        val text = try {
            String(Base64.decode(s.config, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return "کانفیگ OpenVPN نامعتبر است"
        }
        val profile: VpnProfile = try {
            val parser = ConfigParser()
            parser.parseConfig(StringReader(text))
            parser.convertProfile()
        } catch (e: Exception) {
            AppLogger.log("OpenVPN", "خطا در تجزیهٔ کانفیگ: ${e.message}")
            return "کانفیگ قابل خواندن نیست: ${e.message}"
        }
        profile.mName = s.label
        ProfileManager.setTemporaryProfile(ctx, profile)
        AppLogger.log("OpenVPN", "شروع OpenVPN ${s.host} · ${s.country.ifBlank { "-" }}")
        flow.value = CONNECTING to ""
        ctx.startActivity(
            Intent(ctx, LaunchVPN::class.java)
                .putExtra(LaunchVPN.EXTRA_KEY, profile.getUUIDString())
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }

    /** قطع اتصال فعال با فراخوانی stopVPN روی سرویس هسته. */
    fun disconnect(ctx: Context) {
        AppLogger.log("OpenVPN", "قطع اتصال OpenVPN")
        val intent = Intent(ctx, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching { IOpenVPNServiceInternal.Stub.asInterface(service).stopVPN(false) }
                runCatching { ctx.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        runCatching { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
    }
}
