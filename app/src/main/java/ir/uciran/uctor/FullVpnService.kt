package ir.uciran.uctor

import com.multivpn.app.R
import com.multivpn.app.MainActivity

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * کل ترافیک گوشی را از طریق پورت SOCKS محلی Tor عبور می‌دهد.
 *
 * نکته‌ی مهم: هیچ کار شبکه‌ای یا Thread.sleep روی main thread انجام نمی‌شود؛
 * همه‌ی مراحل راه‌اندازی/توقف روی یک worker تک‌نخی (و به‌ترتیب) اجرا می‌شود.
 */
class FullVpnService : VpnService() {
    companion object {
        const val ACTION_START = "ir.uciran.uctor.vpn.START"
        const val ACTION_STOP = "ir.uciran.uctor.vpn.STOP"
        const val ACTION_STOP_ALL = "ir.uciran.uctor.vpn.STOP_ALL"
        const val ACTION_PAUSE = "ir.uciran.uctor.vpn.PAUSE"
        const val ACTION_RESUME = "ir.uciran.uctor.vpn.RESUME"
        const val ACTION_STATUS = "ir.uciran.uctor.vpn.STATUS"
        const val EXTRA_READY = "ready"
        const val EXTRA_ERROR = "error"
        private const val ID = 1002
        private const val TAG = "UCTor-VPN"

        fun vpnLogFile(ctx: Context) = File(ctx.filesDir, "vpn.log")
        fun hevLogFile(ctx: Context) = File(ctx.filesDir, "hev.log")

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, FullVpnService::class.java).setAction(ACTION_START))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, FullVpnService::class.java).setAction(ACTION_STOP))
        fun togglePause(ctx: Context) = ctx.startService(Intent(ctx, FullVpnService::class.java).setAction(if (Prefs(ctx).vpnPaused) ACTION_RESUME else ACTION_PAUSE))
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    private val worker = Executors.newSingleThreadExecutor()
    private val starting = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val meterTick = object : Runnable {
        override fun run() {
            if (running && !Prefs(this@FullVpnService).vpnPaused) updateNotification()
            main.postDelayed(this, 1000)
        }
    }
    private var prevUp = 0L
    private var prevDown = 0L

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // ری‌استارت بدون اکشن توسط اندروید: هرگز VPN جدیدی نسازد (VPN بیرونی جایگزین نشود)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                // «قطع کامل» از نوتیفیکیشن: هم Tor و هم VPN
                TorController.disconnect()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopRequested = true
                worker.execute { stopTunnel() }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                Prefs(this).vpnPaused = true
                foreground()
                worker.execute { closeTunnel(); broadcast(false, "موقتاً متوقف است") }
                return START_STICKY
            }
            ACTION_RESUME -> {
                Prefs(this).vpnPaused = false
                stopRequested = false
                foreground()
                queueStart()
                return START_STICKY
            }
        }
        // شروع عادی (یا راه‌اندازی مجدد سیستم)
        Prefs(this).vpnPaused = false
        stopRequested = false
        foreground()
        main.removeCallbacks(meterTick); main.post(meterTick)
        queueStart()
        return START_STICKY
    }

    /** هر بار فقط یک راه‌اندازی در صف می‌ماند (درخواست‌های تکراری نادیده گرفته می‌شوند). */
    private fun queueStart() {
        if (!starting.compareAndSet(false, true)) return
        worker.execute { try { if (!running) startTunnel() } finally { starting.set(false) } }
    }

    override fun onRevoke() {
        // کاربر مجوز VPN را گرفته یا VPN دیگری فعال شده است.
        vlog("مجوز VPN لغو شد")
        stopRequested = true
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        main.removeCallbacks(meterTick)
        worker.execute { closeTunnel() }
        worker.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── راه‌اندازی تونل (روی worker) ─────────────────────────

    private fun startTunnel() {
        if (running) return
        val p = Prefs(this)
        try {
            vlogReset()
            // ۰) صبر برای کامل شدن اتصال Tor. ممکن است کاربر در این مدت از برنامه خارج شده باشد؛
            //    چون این سرویس از قبل foreground است، پروسه زنده می‌ماند و VPN خودکار بالا می‌آید.
            vlog("انتظار برای کامل شدن اتصال Tor")
            if (!waitForTor(300_000L)) {
                if (!stopRequested) { vlog("اتصال Tor کامل نشد؛ VPN لغو شد"); stopTunnel() }
                return
            }
            val socks = p.socksPort
            if (socks <= 0) error("پورت SOCKS تور هنوز مشخص نشده است")

            // ۱) اول مطمئن شو Tor واقعاً آماده است؛ بعد TUN بساز (نه برعکس).
            vlog("انتظار برای Tor SOCKS روی پورت $socks")
            if (!waitForLocalPort(socks, 15_000L)) error("Tor SOCKS روی پورت $socks پاسخ نمی‌دهد")
            if (stopRequested) { vlog("درخواست توقف رسید؛ شروع لغو شد"); return }

            // ۲) ساخت رابط VPN
            val b = Builder()
                .setSession("UC Tor")
                .setMtu(1500)
                .setMetered(false)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fc00::1", 128)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")
            if (p.tunnelMode == "selected") {
                // لیست مجاز و ممنوع در اندروید با هم قابل ترکیب نیست.
                // اگر لیست خالی بماند، همه‌ی برنامه‌ها (حتی خود UC Tor) وارد تونل می‌شوند و حلقه ایجاد می‌شود.
                var added = 0
                p.selectedApps.filter { it != packageName }.forEach {
                    try { b.addAllowedApplication(it); added++ } catch (_: Exception) { vlog("برنامه‌ی نصب‌نشده نادیده گرفته شد: $it") }
                }
                if (added == 0) error("هیچ برنامه‌ای برای عبور از Tor انتخاب نشده است")
            } else {
                // خود UC Tor (پروسه‌ی اصلی و :tor) باید بیرون از تونل بماند وگرنه ترافیک Tor به خودش برمی‌گردد.
                b.addDisallowedApplication(packageName)
                // حالت «همه به‌جز تیک‌خورده‌ها»: برنامه‌های تیک‌خورده مستقیم وصل می‌شوند.
                if (p.tunnelMode == "bypass") {
                    p.selectedApps.filter { it != packageName }.forEach {
                        try { b.addDisallowedApplication(it) } catch (_: Exception) { vlog("برنامه‌ی نصب‌نشده نادیده گرفته شد: $it") }
                    }
                }
            }
            val pfd = b.establish() ?: error("ساخت رابط VPN ممکن نشد (مجوز VPN داده نشده یا VPN دیگری فعال است)")
            tun = pfd
            vlog("رابط TUN ساخته شد")
            if (stopRequested) { closeTunnel(); return }

            // ۳) راه‌اندازی tun2socks
            val cfg = File(filesDir, "hev-tun.conf")
            val hevLog = hevLogFile(this).also { it.delete() }
            cfg.writeText(buildHevConfig(socks, hevLog))
            // fd را detach نمی‌کنیم تا همیشه بتوانیم TUN را خودمان ببندیم.
            if (!Tun2SocksBridge.start(cfg, pfd.fd)) error(Tun2SocksBridge.lastError.ifBlank { "tun2socks اجرا نشد" })
            vlog("tun2socks استارت شد؛ متدهای کتابخانه: ${Tun2SocksBridge.describe()}")

            if (!waitForTunnel(4_000L)) error("tun2socks بعد از استارت فعال نماند. لاگ: ${tail(hevLog, 5)}")

            prevUp = 0L; prevDown = 0L
            running = true
            p.vpnRunning = true
            vlog("VPN آماده است")
            broadcast(true, "")
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            vlog("خطا: $msg")
            closeTunnel()
            runCatching { File(filesDir, "vpn.state").writeText("error:$msg") }
            broadcast(false, msg)
            main.post {
                main.removeCallbacks(meterTick)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * کانفیگ hev-socks5-tunnel.
     * - mapdns: درخواست‌های DNS اندروید (۱۹۸.۱۸.۰.۲) را می‌گیرد، آی‌پی مجازی از محدوده‌ی network/netmask می‌دهد
     *   و نام دامنه را از طریق SOCKS5 به Tor می‌فرستد (بدون نشت DNS). network و netmask الزامی‌اند.
     * - udp: عمداً تنظیم نشده؛ Tor از UDP ASSOCIATE پشتیبانی نمی‌کند، پس UDP عمومی (مثل QUIC) سریع رد می‌شود
     *   و برنامه‌ها به TCP برمی‌گردند.
     * - connect-timeout پیش‌فرض (۵ ثانیه) برای مدارهای Tor کوتاه است.
     */
    private fun buildHevConfig(socks: Int, hevLog: File): String = listOf(
        "tunnel:",
        "  mtu: 1500",
        "  ipv4: 198.18.0.1",
        "  ipv6: 'fc00::1'",
        "socks5:",
        "  address: 127.0.0.1",
        "  port: $socks",
        "mapdns:",
        "  address: 198.18.0.2",
        "  port: 53",
        "  network: 100.64.0.0",
        "  netmask: 255.192.0.0",
        "  cache-size: 10000",
        "misc:",
        "  connect-timeout: 30000",
        "  read-write-timeout: 300000",
        "  log-file: '${hevLog.absolutePath}'",
        "  log-level: warn",
    ).joinToString("\n") + "\n"

    private fun waitForTor(timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        var idleSince = 0L
        while (System.currentTimeMillis() < until && !stopRequested) {
            val now = System.currentTimeMillis()
            when (TorController.state.value.phase) {
                Phase.CONNECTED -> return true
                Phase.CONNECTING -> idleSince = 0L
                else -> { // خطا / قطع / در حال توقف: چند ثانیه مهلت می‌دهیم و بعد منصرف می‌شویم
                    if (idleSince == 0L) idleSince = now else if (now - idleSince > 3000) return false
                }
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun waitForTunnel(timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        // کمی صبر می‌کنیم تا thread داخلی hev بالا بیاید و بعد وضعیت را می‌سنجیم.
        Thread.sleep(600)
        while (System.currentTimeMillis() < until) {
            if (Tun2SocksBridge.isRunning()) return true
            Thread.sleep(150)
        }
        return false
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until && !stopRequested) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return true
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        return false
    }

    // ───────────────────────── توقف ─────────────────────────

    /** ابتدا tun2socks متوقف می‌شود و بعد TUN بسته می‌شود. */
    private fun closeTunnel() {
        runCatching { Tun2SocksBridge.stop() }
        runCatching { tun?.close() }
        tun = null
        running = false
        Prefs(this).vpnRunning = false
    }

    private fun stopTunnel() {
        closeTunnel()
        vlog("VPN متوقف شد")
        broadcast(false, "متوقف شد")
        main.post {
            main.removeCallbacks(meterTick)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun broadcast(ready: Boolean, error: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_READY, ready).putExtra(EXTRA_ERROR, error))
    }

    // ───────────────────────── نوتیفیکیشن ─────────────────────────

    private fun buildNotification(): Notification {
        val paused = Prefs(this).vpnPaused
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val text = when {
            paused -> "موقتاً متوقف است"
            running -> {
                val (down, up) = speeds()
                "${TrafficMeter.text(down)} ↓   ${TrafficMeter.text(up)} ↑"
            }
            else -> "در حال اتصال…"
        }
        return NotificationCompat.Builder(this, UcTorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_uc)
            .setColor(0xFF39FF14.toInt()) // سبز فسفری برای آیکون در پرده‌ی نوتیفیکیشن
            .setContentTitle("UC Tor")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (paused) "اتصال مجدد" else "توقف موقت", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "قطع کامل", action(ACTION_STOP_ALL, 2))
            .build()
    }

    private fun foreground() {
        // SPECIAL_USE فقط از API 34 معنی دارد.
        val type = if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID, buildNotification(), type)
    }

    /** به‌روزرسانی نوتیفیکیشن بدون فراخوانی دوباره‌ی startForeground. */
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(ID, buildNotification())
    }

    private fun speeds(): Pair<Long, Long> {
        val a = Tun2SocksBridge.stats()
        if (a.size < 4) return 0L to 0L
        val up = a[1]; val down = a[3]
        val d = (down - prevDown).coerceAtLeast(0); val u = (up - prevUp).coerceAtLeast(0)
        prevDown = down; prevUp = up
        return d to u
    }

    private fun action(a: String, id: Int): PendingIntent =
        PendingIntent.getService(this, id, Intent(this, FullVpnService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // ───────────────────────── لاگ ─────────────────────────

    private fun vlogReset() { runCatching { vpnLogFile(this).writeText("") } }

    private fun vlog(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            vpnLogFile(this).appendText("[VPN $t] $msg\n")
        }
    }

    private fun tail(f: File, n: Int): String =
        runCatching { f.readLines().takeLast(n).joinToString(" | ") }.getOrDefault("")
}
