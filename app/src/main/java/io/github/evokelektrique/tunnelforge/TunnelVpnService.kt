package io.github.evokelektrique.tunnelforge

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.Keep
import com.multivpn.app.VpnNotify
import com.multivpn.app.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * سرویس VPN برای L2TP/IPsec (IKEv1 با PSK) بر پایهٔ موتور C از TunnelForge.
 * نسخهٔ UC X: فقط تونل کامل؛ بدون پروکسی، Netty و gVisor.
 *
 * قرارداد با موتور C (vpn_jni.c / util.c) را نباید تغییر داد:
 *  - protectSocketFd(I)Z و onNativeTunnelReady(String) باید static در همین کلاس باشند.
 */
@Keep
class TunnelVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "io.github.evokelektrique.tunnelforge.CONNECT"
        const val ACTION_DISCONNECT = "io.github.evokelektrique.tunnelforge.DISCONNECT"
        const val ACTION_PAUSE = "io.github.evokelektrique.tunnelforge.PAUSE"
        const val ACTION_RESUME = "io.github.evokelektrique.tunnelforge.RESUME"
        const val EXTRA_SERVER = "server"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PSK = "psk"
        const val EXTRA_MTU = "mtu"

        const val STATE_IDLE = "idle"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"
        const val STATE_PAUSED = "paused"

        private const val CONNECT_TIMEOUT_MS = 150_000L
        private const val CHANNEL_ID = "l2tp_vpn"
        private const val NOTIFICATION_ID = 2101
        private const val NOTIFICATION_PAUSED_ID = 2102
        private const val DEFAULT_MTU = 1400
        private const val FALLBACK_DNS = "8.8.8.8"
        private const val PREFS = "proto_last_l2tp"

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

        /** پاک کردن خطای نمایش‌داده‌شده (وضعیت FAILED → IDLE). */
        fun dismissError() {
            if (stateFlow.value.first == STATE_FAILED) stateFlow.value = STATE_IDLE to ""
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

    @Volatile
    private var speedThread: Thread? = null

    @Volatile
    private var serverLabel: String = ""

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        instance = this
        VpnNotify.channel(this, CHANNEL_ID, "L2TP/IPsec")
    }

    override fun onDestroy() {
        // خطا را پاک نمی‌کنیم: وضعیت FAILED باید روی صفحه بماند
        releaseResources()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                clearPaused()
                stopTunnel()
                stopSelf()
            }
            ACTION_PAUSE -> pauseNow()
            ACTION_RESUME -> {
                val p = loadLast()
                if (p == null) {
                    fail("اتصال قبلی برای ادامه پیدا نشد")
                } else {
                    startConnect(p.server, p.user, p.password, p.psk, p.mtu)
                }
            }
            ACTION_CONNECT -> {
                val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
                val user = intent.getStringExtra(EXTRA_USER).orEmpty()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                val psk = intent.getStringExtra(EXTRA_PSK).orEmpty()
                val mtu = intent.getIntExtra(EXTRA_MTU, DEFAULT_MTU)
                saveLast(server, user, password, psk, mtu)
                startConnect(server, user, password, psk, mtu)
            }
        }
        return START_NOT_STICKY
    }

    private fun startConnect(server: String, user: String, password: String, psk: String, mtu: Int) {
        if (worker?.isAlive == true) return
        clearPaused()
        if (server.isBlank() || user.isBlank() || psk.isBlank()) {
            fail("سرور، PSK و نام کاربری الزامی است")
            return
        }
        serverLabel = server
        runCatching { startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال…", "L2TP/IPsec · $server")) }
        stateFlow.value = STATE_CONNECTING to ""
        AppLogger.log("L2TP", "اتصال به $server (کاربر $user) شروع شد")
        val attempt = ++attemptSeq
        worker = Thread({ runTunnel(server, user, password, psk, mtu) }, "l2tp-engine").also { it.start() }
        startWatchdog(attempt)
    }

    /** اگر تا مهلت به «متصل» نرسیم، مذاکره را متوقف و علت (پیام‌های موتور) را نشان می‌دهیم. */
    private fun startWatchdog(attempt: Int) {
        Thread({
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS)
            } catch (_: InterruptedException) {
            }
            if (attempt == attemptSeq && stateFlow.value.first == STATE_CONNECTING) {
                stateFlow.value = STATE_FAILED to
                    "مهلت ۱۵۰ ثانیه تمام شد · پیام‌های موتور: ${VpnTunnelEvents.recentLogs()}"
                AppLogger.log("L2TP", "مهلت ۱۵۰ ثانیه تمام شد")
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
            // اگر مهلت قبلاً خطا ثبت کرده، پیام مهلت بازنویسی نمی‌شود
            if (stateFlow.value.first != STATE_FAILED && stateFlow.value.first != STATE_PAUSED) {
                fail("اتصال L2TP برقرار نشد (کد $negotiated) · پیام‌های موتور: ${VpnTunnelEvents.recentLogs()}")
            } else {
                runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
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
        stateFlow.value = STATE_CONNECTED to ipv4(clientIp)
        updateNotification("متصل · ${ipv4(clientIp)}", "L2TP/IPsec · $serverLabel")
        startSpeed()

        // مرحلهٔ ۳: حلقهٔ تونل (تا قطع شدن ادامه دارد)
        val loopCode = try {
            VpnBridge.nativeStartLoop(pfd.fd)
        } catch (t: Throwable) {
            -1
        }
        stopSpeed()
        closeTun()
        AppLogger.log("L2TP", "حلقهٔ تونل پایان یافت (کد $loopCode)")
        if (stateFlow.value.first != STATE_FAILED && stateFlow.value.first != STATE_PAUSED) {
            stateFlow.value = STATE_IDLE to "قطع شد (کد $loopCode)"
        }
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** توقف موقت: تونل بسته می‌شود، ولی نوتیفیکیشن «اتصال مجدد» می‌ماند. */
    private fun pauseNow() {
        AppLogger.log("L2TP", "توقف موقت")
        stopSpeed()
        runCatching { VpnBridge.nativeStopTunnel() }
        closeTun()
        worker = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("paused", true).apply()
        stateFlow.value = STATE_PAUSED to ""
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_PAUSED_ID, buildPausedNotification())
        stopSelf()
    }

    private fun clearPaused() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("paused", false).apply()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_PAUSED_ID)
    }

    /** آزادسازی منابع بدون تغییر وضعیت (برای onDestroy). */
    private fun releaseResources() {
        stopSpeed()
        runCatching { VpnBridge.nativeStopTunnel() }
        closeTun()
        worker = null
    }

    private fun stopTunnel() {
        stopSpeed()
        runCatching { VpnBridge.nativeStopTunnel() }
        closeTun()
        worker = null
        stateFlow.value = STATE_IDLE to ""
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun fail(message: String) {
        AppLogger.log("L2TP", "خطا: $message")
        if (stateFlow.value.first != STATE_FAILED) stateFlow.value = STATE_FAILED to message
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** سرعت لحظه‌ای از شمارندهٔ tun، هر ثانیه در عنوان نوتیفیکیشن. */
    private fun startSpeed() {
        stopSpeed()
        val t = Thread({
            var prev = VpnNotify.tunCounters() ?: (0L to 0L)
            var prevT = SystemClock.elapsedRealtime()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000L)
                } catch (_: InterruptedException) {
                    break
                }
                val cur = VpnNotify.tunCounters() ?: continue
                val now = SystemClock.elapsedRealtime()
                val sec = ((now - prevT).coerceAtLeast(1L)) / 1000.0
                val down = ((cur.first - prev.first).coerceAtLeast(0L) / sec).toLong()
                val up = ((cur.second - prev.second).coerceAtLeast(0L) / sec).toLong()
                prev = cur
                prevT = now
                updateNotification("↓ ${VpnNotify.speedText(down)}   ↑ ${VpnNotify.speedText(up)}", "L2TP/IPsec · $serverLabel")
            }
        }, "l2tp-speed")
        t.isDaemon = true
        speedThread = t
        t.start()
    }

    private fun stopSpeed() {
        speedThread?.interrupt()
        speedThread = null
    }

    private fun isSet(a: IntArray): Boolean = a.any { it != 0 }

    private fun ipv4(a: IntArray): String = "${a[0]}.${a[1]}.${a[2]}.${a[3]}"

    private fun ipv4OrDefault(a: IntArray): String = if (isSet(a)) ipv4(a) else FALLBACK_DNS

    private fun buildNotification(title: String, text: String) = VpnNotify.build(
        this,
        CHANNEL_ID,
        title,
        text,
        listOf(
            "توقف موقت" to VpnNotify.serviceIntent(this, TunnelVpnService::class.java, ACTION_PAUSE, 3),
            "قطع کامل" to VpnNotify.serviceIntent(this, TunnelVpnService::class.java, ACTION_DISCONNECT, 4),
        ),
        ongoing = true,
        open = VpnNotify.openAppIntent(this, 20),
    )

    private fun buildPausedNotification() = VpnNotify.build(
        this,
        CHANNEL_ID,
        "L2TP/IPsec · متوقف موقت",
        "برای ادامه، اتصال مجدد را بزنید",
        listOf(
            "اتصال مجدد" to VpnNotify.serviceIntent(this, TunnelVpnService::class.java, ACTION_RESUME, 1),
            "قطع کامل" to VpnNotify.serviceIntent(this, TunnelVpnService::class.java, ACTION_DISCONNECT, 2),
        ),
        ongoing = false,
        open = VpnNotify.openAppIntent(this, 21),
    )

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private data class Last(val server: String, val user: String, val password: String, val psk: String, val mtu: Int)

    private fun saveLast(server: String, user: String, password: String, psk: String, mtu: Int) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("server", server).putString("user", user).putString("password", password)
            .putString("psk", psk).putInt("mtu", mtu).apply()
    }

    private fun loadLast(): Last? {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val server = p.getString("server", null) ?: return null
        return Last(server, p.getString("user", "").orEmpty(), p.getString("password", "").orEmpty(),
            p.getString("psk", "").orEmpty(), p.getInt("mtu", DEFAULT_MTU))
    }
}
