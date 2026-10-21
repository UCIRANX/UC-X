package io.github.evokelektrique.tunnelforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * سرویس VPN برای L2TP/IPsec (IKEv1 با PSK) بر پایهٔ موتور C از TunnelForge.
 * نسخهٔ UC X: فقط حالت تونل کامل؛ بدون پروکسی، Netty و gVisor.
 *
 * قرارداد با موتور C (vpn_jni.c / util.c) را نباید تغییر داد:
 *  - protectSocketFd(I)Z و onNativeTunnelReady(String) باید static در همین کلاس باشند.
 */
@Keep
class TunnelVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "io.github.evokelektrique.tunnelforge.CONNECT"
        const val ACTION_DISCONNECT = "io.github.evokelektrique.tunnelforge.DISCONNECT"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PSK = "psk"
        const val EXTRA_MTU = "mtu"

        const val STATE_IDLE = "idle"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"

        private const val CONNECT_TIMEOUT_MS = 150_000L
        private const val CHANNEL_ID = "l2tp_vpn"
        private const val NOTIFICATION_ID = 2101
        private const val DEFAULT_MTU = 1400
        private const val FALLBACK_DNS = "8.8.8.8"

        private val stateFlow = MutableStateFlow(STATE_IDLE to "")

        /** (وضعیت، توضیح) برای نمایش در UI. */
        val state: StateFlow<Pair<String, String>> = stateFlow.asStateFlow()

        @Volatile
        private var instance: TunnelVpnService? = null

        /** موتور C برای هر سوکت UDP خارج از تونل صدا می‌زند. */
        @JvmStatic
        fun protectSocketFd(fd: Int): Boolean = instance?.protect(fd) ?: false

        /** موتور C وقتی حلقهٔ تونل فعال شد صدا می‌زند. */
        @JvmStatic
        fun onNativeTunnelReady(detail: String?) {
            stateFlow.value = STATE_CONNECTED to (detail ?: "")
        }

        /** برای شروع از UI: Intent اتصال با پارامترهای پروفایل. */
        fun connectIntent(
            context: Context,
            server: String,
            user: String,
            password: String,
            psk: String,
            mtu: Int = DEFAULT_MTU,
        ): Intent = Intent(context, TunnelVpnService::class.java)
            .setAction(ACTION_CONNECT)
            .putExtra(EXTRA_SERVER, server)
            .putExtra(EXTRA_USER, user)
            .putExtra(EXTRA_PASSWORD, password)
            .putExtra(EXTRA_PSK, psk)
            .putExtra(EXTRA_MTU, mtu)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, TunnelVpnService::class.java).setAction(ACTION_DISCONNECT)
    }

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var attemptSeq: Int = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onDestroy() {
        stopTunnel()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
            }
            ACTION_CONNECT -> {
                if (worker?.isAlive == true) return START_NOT_STICKY
                val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
                val user = intent.getStringExtra(EXTRA_USER).orEmpty()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                val psk = intent.getStringExtra(EXTRA_PSK).orEmpty()
                val mtu = intent.getIntExtra(EXTRA_MTU, DEFAULT_MTU)
                if (server.isBlank() || user.isBlank() || psk.isBlank()) {
                    stateFlow.value = STATE_FAILED to "سرور، PSK و نام کاربری الزامی است"
                    stopSelf()
                    return START_NOT_STICKY
                }
                runCatching { startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال…")) }
                stateFlow.value = STATE_CONNECTING to ""
                val attempt = ++attemptSeq
                worker = Thread({ runTunnel(server, user, password, psk, mtu) }, "l2tp-engine").also { it.start() }
                startWatchdog(attempt)
            }
        }
        return START_NOT_STICKY
    }

    /** اگر تا مهلت به «متصل» نرسیم، مذاکره را متوقف و علت (آخرین پیام موتور) را نشان می‌دهیم. */
    private fun startWatchdog(attempt: Int) {
        Thread({
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS)
            } catch (_: InterruptedException) {
            }
            if (attempt == attemptSeq && stateFlow.value.first == STATE_CONNECTING) {
                stateFlow.value = STATE_FAILED to
                    "مهلت ۱۵۰ ثانیه تمام شد · پیام‌های موتور: ${VpnTunnelEvents.recentLogs()}"
                runCatching { VpnBridge.nativeStopTunnel() }
                runCatching { stopSelf() }
            }
        }, "l2tp-watchdog").start()
    }

    private fun runTunnel(server: String, user: String, password: String, psk: String, mtu: Int) {
        val clientIp = IntArray(4)
        val dns1 = IntArray(4)
        val dns2 = IntArray(4)
        val negotiated: Int
        try {
            // مرحلهٔ ۱: IKE + L2TP + PPP روی شبکهٔ واقعی (هنوز تونل نیست)
            VpnBridge.nativeSetSocketProtectionEnabled(true)
            negotiated = VpnBridge.nativeNegotiate(server, user, password, psk, mtu, clientIp, dns1, dns2)
        } catch (t: Throwable) {
            fail("موتور L2TP بارگذاری یا اجرا نشد: ${t.message ?: t.javaClass.simpleName}")
            return
        }
        if (negotiated != 0) {
            // اگر مهلت قبلاً خطا ثبت کرده، پیام مهلت را بازنویسی نمی‌کنیم
            if (stateFlow.value.first != STATE_FAILED) {
                fail("اتصال L2TP برقرار نشد (کد $negotiated) · پیام‌های موتور: ${VpnTunnelEvents.recentLogs()}")
            } else {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                runCatching { stopSelf() }
            }
            return
        }

        // مرحلهٔ ۲: ساخت رابط TUN با IP و DNS که سرور داده است
        val pfd = try {
            val b = Builder()
                .setSession("L2TP $server")
                .setMtu(mtu)
                .addAddress(ipv4(clientIp), 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(ipv4OrDefault(dns1))
            if (isSet(dns2)) b.addDnsServer(ipv4(dns2))
            // برنامه خودش از تونل بیرون بماند تا سوکت‌های UDP موتور داخل تونل نیفتند
            runCatching { b.addDisallowedApplication(packageName) }
            b.establish()
        } catch (t: Throwable) {
            null
        }
        if (pfd == null) {
            fail("مجوز VPN داده نشد یا رابط TUN ساخته نشد")
            return
        }
        tun = pfd
        updateNotification("متصل · ${ipv4(clientIp)}")

        // مرحلهٔ ۳: حلقهٔ تونل (تا قطع شدن ادامه دارد)
        val loopCode = try {
            VpnBridge.nativeStartLoop(pfd.fd)
        } catch (t: Throwable) {
            -1
        }
        closeTun()
        if (stateFlow.value.first != STATE_FAILED) {
            stateFlow.value = STATE_IDLE to "قطع شد (کد $loopCode)"
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTunnel() {
        runCatching { VpnBridge.nativeStopTunnel() }
        closeTun()
        worker = null
        stateFlow.value = STATE_IDLE to ""
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun fail(message: String) {
        if (stateFlow.value.first != STATE_FAILED) stateFlow.value = STATE_FAILED to message
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isSet(a: IntArray): Boolean = a.any { it != 0 }

    private fun ipv4(a: IntArray): String = "${a[0]}.${a[1]}.${a[2]}.${a[3]}"

    private fun ipv4OrDefault(a: IntArray): String = if (isSet(a)) ipv4(a) else FALLBACK_DNS

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "L2TP/IPsec", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val disconnect = PendingIntent.getService(
            this, 0,
            Intent(this, TunnelVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("L2TP/IPsec")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع", disconnect)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
