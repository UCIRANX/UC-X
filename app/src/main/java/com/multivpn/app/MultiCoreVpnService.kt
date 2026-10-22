package com.multivpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.multivpn.app.awg.AwgConfig
import com.multivpn.app.awg.AwgNative
import com.multivpn.app.awg.AwgState
import com.multivpn.app.core.CoreConfigGenerator
import com.multivpn.app.core.CoreEngineRunner
import com.multivpn.app.core.Tun2SocksBridge
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetAddress
import java.io.File

/** سرویس VPN سینگ‌باکس با نوتیفیکیشن مشابه نوتیفیکیشن تور (سرعت لحظه‌ای، توقف موقت، قطع کامل). */
class MultiCoreVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.multivpn.app.action.STOP_VPN"       // قطع کامل
        const val ACTION_PAUSE = "com.multivpn.app.action.PAUSE_VPN"     // توقف موقت
        const val ACTION_RESUME = "com.multivpn.app.action.RESUME_VPN"   // اتصال مجدد
        const val ACTION_CONNECTING = "com.multivpn.app.action.CONNECTING"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "singbox_channel"

        /** وضعیت اتصال AmneziaWG؛ کنترلر Sing-Box آن را می‌خواند. */
        val awgStatus = MutableStateFlow<AwgState>(AwgState.Idle)

        private val pausedState = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** وضعیت توقف موقت؛ UI با این جریان به‌روز می‌شود. */
        val pausedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = pausedState
        val paused: Boolean get() = pausedState.value
        private fun setPaused(value: Boolean) { pausedState.value = value }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var expiryJob: Job? = null
    private var speedJob: Job? = null

    private var coreName = "سینگ‌باکس"
    private var serverName = ""
    private var socksPort = CoreConfigGenerator.LOCAL_SOCKS_PORT
    private var serverPing = -1L
    private var tunnelUp = false
    private var awgHandle = 0
    private var awgRaw = ""

    private var prevRx = 0L
    private var prevTx = 0L
    private var prevTs = 0L
    private var downBps = 0L
    private var upBps = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseTunnel()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeTunnel()
                return START_STICKY
            }
            ACTION_CONNECTING -> {
                // نوتیفیکیشن فوراً نشان داده می‌شود، همان لحظه‌ای که اتصال شروع شد
                serverName = intent.getStringExtra("SERVER_NAME") ?: serverName
                tunnelUp = false
                showNotification(connecting = true)
                return START_STICKY
            }
        }

        if (intent?.getStringExtra("CORE_NAME") == "AmneziaWG") {
            startAwg(intent.getStringExtra("AWG_RAW").orEmpty(), intent.getStringExtra("SERVER_NAME") ?: "AmneziaWG")
            return START_STICKY
        }
        coreName = intent?.getStringExtra("CORE_NAME") ?: coreName
        serverName = intent?.getStringExtra("SERVER_NAME") ?: "Server"
        socksPort = intent?.getIntExtra("SOCKS_PORT", CoreConfigGenerator.LOCAL_SOCKS_PORT) ?: CoreConfigGenerator.LOCAL_SOCKS_PORT
        serverPing = intent?.getLongExtra("SERVER_PING", -1L) ?: -1L

        serviceScope.launch {
            val ok = establishTunnel(serverName, socksPort)
            if (ok) {
                tunnelUp = true
                setPaused(false)
                showNotification(connecting = false)
                startSpeedUpdates()
            } else {
                showNotification(connecting = false)
            }
        }
        startExpirationWatchdog()
        return START_STICKY
    }

    /** تونل Full TUN + MapDNS؛ موفق بودن را برمی‌گرداند. */
    private fun establishTunnel(serverName: String, socksPort: Int): Boolean {
        return try {
            stopTunnelOnly()
            val builder = Builder()
                .setSession("MultiCoreVPN - $serverName")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fc00::1", 128)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            val pfd = builder.establish() ?: return false
            vpnInterface = pfd

            val hevConf = File(filesDir, "hev-tun.conf")
            val hevLog = File(filesDir, "hev.log").apply { delete() }
            hevConf.writeText(
                listOf(
                    "tunnel:",
                    "  mtu: 1500",
                    "  ipv4: 198.18.0.1",
                    "  ipv6: 'fc00::1'",
                    "socks5:",
                    "  address: 127.0.0.1",
                    "  port: $socksPort",
                    "mapdns:",
                    "  address: 198.18.0.2",
                    "  port: 53",
                    "  network: 100.64.0.0",
                    "  netmask: 255.192.0.0",
                    "  cache-size: 10000",
                    "misc:",
                    "  task-stack-size: 81920",
                    "  connect-timeout: 15000",
                    "  read-write-timeout: 300000",
                    "  log-file: '${hevLog.absolutePath}'",
                    "  log-level: warn"
                ).joinToString("\n") + "\n",
                Charsets.UTF_8
            )

            Tun2SocksBridge.start(hevConf, pfd.fd)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun pauseTunnel() {
        if (paused) return
        if (awgHandle > 0) stopAwgOnly()
        setPaused(true)
        tunnelUp = false
        speedJob?.cancel()
        stopTunnelOnly()
        showNotification(connecting = false)
    }

    private fun resumeTunnel() {
        if (!paused) return
        if (awgRaw.isNotEmpty()) {
            startAwg(awgRaw, serverName)
            return
        }
        serviceScope.launch {
            val ok = establishTunnel(serverName, socksPort)
            if (ok) {
                setPaused(false)
                tunnelUp = true
                startSpeedUpdates()
            }
            showNotification(connecting = false)
        }
    }

    private fun startSpeedUpdates() {
        speedJob?.cancel()
        val start = readCounters()
        prevRx = start.first
        prevTx = start.second
        prevTs = SystemClock.elapsedRealtime()
        speedJob = serviceScope.launch {
            while (isActive && tunnelUp) {
                delay(1000L)
                val (rx, tx) = readCounters()
                val now = SystemClock.elapsedRealtime()
                val seconds = ((now - prevTs).coerceAtLeast(1L)) / 1000.0
                downBps = ((rx - prevRx).coerceAtLeast(0L) / seconds).toLong()
                upBps = ((tx - prevTx).coerceAtLeast(0L) / seconds).toLong()
                prevRx = rx
                prevTx = tx
                prevTs = now
                showNotification(connecting = false)
            }
        }
    }

    /** مسیر Xray: شمارندهٔ خود رابط تونل (فقط ترافیک VPN). AmneziaWG: مجموع قبلی. */
    private fun readCounters(): Pair<Long, Long> {
        // شمارندهٔ خود رابط tun (هم Xray و هم AmneziaWG). TrafficStats کل ترافیک دستگاه است
        // و ترافیک تونل را دوبار (روی شبکه و روی tun) می‌شمارد؛ به همین دلیل عدد دو برابر می‌شد.
        tunCounters()?.let { return it }
        val uid = applicationInfo.uid
        return TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L) to TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
    }

    private fun tunCounters(): Pair<Long, Long>? = runCatching {
        File("/proc/net/dev").readLines().firstNotNullOfOrNull { line ->
            val t = line.trim()
            if (!t.startsWith("tun")) return@firstNotNullOfOrNull null
            val nums = t.substringAfter(":").trim().split(Regex("\\s+"))
            if (nums.size < 9) null else nums[0].toLong() to nums[8].toLong()
        }
    }.getOrNull()

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, MultiCoreVpnService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun showNotification(connecting: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "سینگ باکس", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            connecting -> "در حال اتصال به $serverName…"
            paused -> "متوقف موقت · $serverName"
            tunnelUp -> "↓ ${formatSpeed(downBps)}   ↑ ${formatSpeed(upBps)}"
            else -> "در حال اتصال…"
        }
        val pingText = if (serverPing > 0 && tunnelUp && !paused) "  ·  ${serverPing}ms" else ""

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_uc)
            .setColor(0xFF39FF14.toInt())
            .setContentTitle("سینگ باکس")
            .setContentText(text + pingText)
            .setSubText(if (tunnelUp) serverName else null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (tunnelUp || paused) {
            builder.addAction(
                0,
                if (paused) "اتصال مجدد" else "توقف موقت",
                pending(if (paused) ACTION_RESUME else ACTION_PAUSE, 1)
            )
        }
        builder.addAction(0, "قطع کامل", pending(ACTION_STOP, 2))

        val notification: Notification = builder.build()
        startForeground(NOTIF_ID, notification)
    }

    /** تونل AmneziaWG: رابط VpnService، سپس هسته، سپس بررسی هندشیک. */
    private fun startAwg(raw: String, name: String) {
        stopAwgOnly()
        awgRaw = raw
        serverName = name
        serviceScope.launch {
            awgStatus.value = AwgState.Connecting
            try {
                val conf = AwgConfig.decodeConf(raw)
                    ?: throw Exception("این فرمت AmneziaWG پشتیبانی نمی‌شود؛ از فرمت awg:// استفاده کنید")
                val parsed = AwgConfig.parse(conf) ?: throw Exception("کانفیگ AmneziaWG ناقص است")
                val endpointIp = InetAddress.getByName(parsed.endpointHost).hostAddress
                    ?: throw Exception("آدرس سرور پیدا نشد")
                val pfd = tunnelBuilder(name, parsed).establish()
                    ?: throw Exception("مجوز VPN داده نشد")
                vpnInterface = pfd
                val handle = AwgNative.turnOn("awg0", pfd.fd, AwgConfig.toUapi(parsed, endpointIp))
                if (handle <= 0) throw Exception("هسته AmneziaWG اجرا نشد: " + runCatching { AwgNative.lastError() }.getOrDefault(""))
                awgHandle = handle
                tunnelUp = true
                setPaused(false)
                if (!waitHandshake(handle, 12_000L)) {
                    throw Exception("سرور جواب نداد (هندشیک انجام نشد)")
                }
                awgStatus.value = AwgState.Up(name)
                showNotification(connecting = false)
                startSpeedUpdates()
            } catch (t: Throwable) {
                logAwgError(t)
                stopAwgOnly()
                stopTunnelOnly()
                awgStatus.value = AwgState.Failed(t.message ?: t.javaClass.simpleName)
                shutdown()
            }
        }
    }

    /** خطاهای AmneziaWG در فایل ثبت می‌شوند تا علت کرش دیده شود. */
    private fun logAwgError(t: Throwable) {
        runCatching { File(filesDir, "awg_last_error.txt").writeText(t.stackTraceToString()) }
    }

    private fun stopAwgOnly() {
        if (awgHandle > 0) {
            runCatching { AwgNative.turnOff(awgHandle) }
            awgStatus.value = AwgState.Idle
        }
        awgHandle = 0
    }

    private suspend fun waitHandshake(handle: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val info = runCatching { AwgNative.ipcGet(handle) }.getOrDefault("")
            val ts = info.lines()
                .firstOrNull { it.startsWith("last_handshake_time_sec=") }
                ?.substringAfter("=")?.toLongOrNull() ?: 0L
            if (ts > 0L) return true
            delay(500L)
        }
        return false
    }

    private fun tunnelBuilder(session: String, p: AwgConfig.Parsed): Builder {
        val b = Builder()
            .setSession("AmneziaWG - $session")
            .setMtu(p.mtu)
        for ((ip, prefix) in p.addresses) b.addAddress(ip, prefix)
        b.addRoute("0.0.0.0", 0)
        b.addRoute("::", 0)
        for (dns in p.dns) b.addDnsServer(dns)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        // برنامه خودش از تونل بیرون بماند تا سوکت UDP هسته داخل TUN نیفتد
        runCatching { b.addDisallowedApplication(packageName) }
        return b
    }

    private fun startExpirationWatchdog() {
        expiryJob?.cancel()
        expiryJob = serviceScope.launch {
            while (isActive) {
                if (LicenseManager.getCurrentLicenseStatus(applicationContext) !is LicenseStatus.Valid) {
                    sendExpirationNotification()
                    shutdown()
                    break
                }
                delay(5000L)
            }
        }
    }

    private fun stopTunnelOnly() {
        runCatching { Tun2SocksBridge.stop() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun stopTunnel() {
        stopAwgOnly()
        runCatching { CoreEngineRunner.stopCore() }
        stopTunnelOnly()
    }

    /** قطع کامل: هسته، تونل، نوتیفیکیشن و خود سرویس. */
    private fun shutdown() {
        awgRaw = ""
        speedJob?.cancel()
        expiryJob?.cancel()
        tunnelUp = false
        setPaused(false)
        stopTunnel()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendExpirationNotification() {
        val channelId = "vpn_expiry_alert"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "هشدار انقضای کد ۵ روزه", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("مهلت ۷ روزه به پایان رسید")
            .setContentText("اتصال فیلترشکن قطع شد. لطفاً کد جدید را از ربات تلگرام دریافت و وارد کنید.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notification)
    }

    override fun onDestroy() {
        speedJob?.cancel()
        expiryJob?.cancel()
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        serviceScope.cancel()
        super.onRevoke()
        stopSelf()
    }
}
