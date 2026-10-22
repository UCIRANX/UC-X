package com.multivpn.app.oc

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.multivpn.app.VpnNotify
import com.multivpn.app.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.infradead.libopenconnect.LibOpenConnect

/** پارامترهای اتصال AnyConnect. */
data class OcParams(
    val server: String,
    val port: Int,
    val user: String,
    val password: String,
    val group: String,
    val pin: String,
)

/**
 * سرویس VPN برای OpenConnect (AnyConnect) بر پایهٔ libopenconnect.
 * الگو از OpenTunnel (LGPL-2.1)؛ نسخهٔ UC X: تونل کامل، توقف موقت و نوتیفیکیشن با سرعت لحظه‌ای.
 */
class OcVpnService : VpnService() {

    companion object {
        private const val TAG = "OpenConnect"
        const val ACTION_CONNECT = "com.multivpn.app.oc.CONNECT"
        const val ACTION_DISCONNECT = "com.multivpn.app.oc.DISCONNECT"
        const val ACTION_PAUSE = "com.multivpn.app.oc.PAUSE"
        const val ACTION_RESUME = "com.multivpn.app.oc.RESUME"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_GROUP = "group"
        const val EXTRA_PIN = "pin"

        const val STATE_IDLE = "idle"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"
        const val STATE_PAUSED = "paused"

        private const val CHANNEL_ID = "oc_vpn"
        private const val NOTIFICATION_ID = 2201
        private const val NOTIFICATION_PAUSED_ID = 2202
        private const val MTU = 1400
        private const val FALLBACK_DNS = "8.8.8.8"
        private const val PREFS = "proto_last_oc"

        private val stateFlow = MutableStateFlow(STATE_IDLE to "")
        /** (وضعیت، توضیح) برای نمایش در UI. */
        val state: StateFlow<Pair<String, String>> = stateFlow.asStateFlow()

        @Volatile
        private var instance: OcVpnService? = null

        /** پاک کردن خطای نمایش‌داده‌شده (وضعیت FAILED → IDLE). */
        fun dismissError() {
            if (stateFlow.value.first == STATE_FAILED) stateFlow.value = STATE_IDLE to ""
        }

        fun connectIntent(context: Context, p: OcParams): Intent =
            Intent(context, OcVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_SERVER, p.server)
                .putExtra(EXTRA_PORT, p.port)
                .putExtra(EXTRA_USER, p.user)
                .putExtra(EXTRA_PASSWORD, p.password)
                .putExtra(EXTRA_GROUP, p.group)
                .putExtra(EXTRA_PIN, p.pin)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, OcVpnService::class.java).setAction(ACTION_DISCONNECT)
    }

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var session: OcSession? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var speedThread: Thread? = null

    @Volatile
    private var serverLabel: String = ""

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        instance = this
        VpnNotify.channel(this, CHANNEL_ID, "OpenConnect")
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
                stopAll()
                stopSelf()
            }
            ACTION_PAUSE -> pauseNow()
            ACTION_RESUME -> {
                val p = loadLast()
                if (p == null) fail("اتصال قبلی برای ادامه پیدا نشد") else startConnect(p)
            }
            ACTION_CONNECT -> {
                val p = OcParams(
                    server = intent.getStringExtra(EXTRA_SERVER).orEmpty(),
                    port = intent.getIntExtra(EXTRA_PORT, 443),
                    user = intent.getStringExtra(EXTRA_USER).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    group = intent.getStringExtra(EXTRA_GROUP).orEmpty(),
                    pin = intent.getStringExtra(EXTRA_PIN).orEmpty(),
                )
                saveLast(p)
                startConnect(p)
            }
        }
        return START_NOT_STICKY
    }

    private fun startConnect(p: OcParams) {
        if (worker?.isAlive == true) return
        clearPaused()
        if (p.server.isBlank() || p.user.isBlank()) {
            fail("سرور و نام کاربری الزامی است")
            return
        }
        serverLabel = p.server
        runCatching { startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال…", "OpenConnect · ${p.server}")) }
        stateFlow.value = STATE_CONNECTING to ""
        worker = Thread({ runTunnel(p) }, "oc-engine").also { it.start() }
    }

    private fun runTunnel(p: OcParams) {
        AppLogger.log("OpenConnect", "اتصال به ${p.server}:${p.port} (کاربر ${p.user}) شروع شد")
        // بارگذاری کتابخانه جداگانه: اگر نبود، پیام روشن به کاربر داده شود (نه کرش)
        val loadError = runCatching { System.loadLibrary("openconnect") }.exceptionOrNull()
        if (loadError != null) {
            val msg = "کتابخانهٔ OpenConnect (libopenconnect.so) در این نسخه بارگذاری نشد: ${loadError.javaClass.name}: ${loadError.message}"
            AppLogger.log("OpenConnect", msg)
            stateFlow.value = STATE_FAILED to msg
            runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        var s: OcSession? = null
        val error: String? = try {
            s = OcSession(p) { fd -> instance?.protect(fd) ?: false }
            session = s
            tunnel(s, p)
        } catch (t: Throwable) {
            describeError(t)
        }
        if (error != null) AppLogger.log("OpenConnect", error)
        stopSpeed()
        closeTun()
        runCatching { s?.destroy() }
        session = null
        if (stateFlow.value.first != STATE_FAILED && stateFlow.value.first != STATE_PAUSED) {
            stateFlow.value = if (error == null) STATE_IDLE to "" else STATE_FAILED to error
        }
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** null یعنی اتصال عادی پایان یافته؛ متن یعنی خطا. */
    private fun tunnel(s: OcSession, p: OcParams): String? {
        s.setLogLevel(LibOpenConnect.PRG_INFO)
        if (s.setProtocol("anyconnect") != 0) return "پروتکل AnyConnect پذیرفته نشد"
        runCatching { s.setSystemTrust(true) }

        val host = p.server.removePrefix("https://").removePrefix("http://").substringBefore("/")
        val url = "https://$host:${p.port}"
        if (s.parseURL(url) != 0) return "آدرس سرور قابل تجزیه نیست"

        if (s.obtainCookie() != 0) return "ورود ناموفق: نام کاربری، رمز یا گروه را بررسی کنید"
        if (s.makeCSTPConnection() != 0) return "سرور ورود را پذیرفت ولی تونل را برقرار نکرد"

        val ip = s.getIPInfo() ?: return "سرور تنظیمات IP نفرستاد"
        val pfd = buildTun(ip, host) ?: return "اجازهٔ VPN داده نشد یا رابط TUN ساخته نشد"
        tun = pfd
        if (s.setupTunFD(pfd.fd) != 0) return "تحویل رابط TUN به موتور ناموفق بود"

        stateFlow.value = STATE_CONNECTED to ip.addr
        updateNotification("↓ 0 B/s   ↑ 0 B/s", "OpenConnect · $serverLabel")
        startSpeed()
        while (true) {
            if (s.mainloop(10, LibOpenConnect.RECONNECT_INTERVAL_MIN) < 0) break
        }
        return null
    }

    private fun buildTun(ip: LibOpenConnect.IPInfo, server: String): ParcelFileDescriptor? = try {
        val b = Builder()
            .setSession("AnyConnect $server")
            .setMtu(MTU)
            .addAddress(ip.addr, prefixOf(ip.netmask))
            .addRoute("0.0.0.0", 0)
        val dns = ip.DNS.orEmpty().filter { it.isNotBlank() }
        if (dns.isEmpty()) b.addDnsServer(FALLBACK_DNS) else dns.forEach { b.addDnsServer(it) }
        runCatching { b.addDisallowedApplication(packageName) }
        b.establish()
    } catch (t: Throwable) {
        null
    }

    private fun describeError(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(3).joinToString(" ← ") {
            "${it.javaClass.name}${it.message?.let { m -> ": $m" } ?: ""}"
        }
        return "خطای موتور OpenConnect: $chain"
    }

    private fun prefixOf(mask: String?): Int =
        mask?.split(".")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }
            ?.sumOf { Integer.bitCount(it) } ?: 32

    /** توقف موقت: نشست بسته می‌شود، ولی نوتیفیکیشن «اتصال مجدد» می‌ماند. */
    private fun pauseNow() {
        AppLogger.log("OpenConnect", "توقف موقت")
        stopSpeed()
        runCatching { session?.cancel() }
        closeTun()
        worker = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("paused", true).apply()
        stateFlow.value = STATE_PAUSED to ""
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_PAUSED_ID, buildPausedNotification())
        stopSelf()
    }

    private fun clearPaused() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("paused", false).apply()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_PAUSED_ID)
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
                val cur = VpnNotify.tunCounters()
                if (cur == null) {
                    updateNotification("متصل", "OpenConnect · $serverLabel")
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val sec = ((now - prevT).coerceAtLeast(1L)) / 1000.0
                val down = ((cur.first - prev.first).coerceAtLeast(0L) / sec).toLong()
                val up = ((cur.second - prev.second).coerceAtLeast(0L) / sec).toLong()
                prev = cur
                prevT = now
                updateNotification("↓ ${VpnNotify.speedText(down)}   ↑ ${VpnNotify.speedText(up)}", "OpenConnect · $serverLabel")
            }
        }, "oc-speed")
        t.isDaemon = true
        speedThread = t
        t.start()
    }

    private fun stopSpeed() {
        speedThread?.interrupt()
        speedThread = null
    }

    /** آزادسازی منابع بدون تغییر وضعیت (برای onDestroy). */
    private fun releaseResources() {
        stopSpeed()
        runCatching { session?.cancel() }
        closeTun()
        worker = null
    }

    private fun stopAll() {
        stopSpeed()
        runCatching { session?.cancel() }
        closeTun()
        worker = null
        stateFlow.value = STATE_IDLE to ""
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun fail(message: String) {
        AppLogger.log(TAG, "خطا: $message")
        if (stateFlow.value.first != STATE_FAILED) stateFlow.value = STATE_FAILED to message
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification(title: String, text: String) = VpnNotify.build(
        this,
        CHANNEL_ID,
        title,
        text,
        listOf(
            "توقف موقت" to VpnNotify.serviceIntent(this, OcVpnService::class.java, ACTION_PAUSE, 3),
            "قطع کامل" to VpnNotify.serviceIntent(this, OcVpnService::class.java, ACTION_DISCONNECT, 4),
        ),
        ongoing = true,
        open = VpnNotify.openAppIntent(this, 22),
    )

    private fun buildPausedNotification() = VpnNotify.build(
        this,
        CHANNEL_ID,
        "OpenConnect · متوقف موقت",
        "برای ادامه، اتصال مجدد را بزنید",
        listOf(
            "اتصال مجدد" to VpnNotify.serviceIntent(this, OcVpnService::class.java, ACTION_RESUME, 1),
            "قطع کامل" to VpnNotify.serviceIntent(this, OcVpnService::class.java, ACTION_DISCONNECT, 2),
        ),
        ongoing = false,
        open = VpnNotify.openAppIntent(this, 23),
    )

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun saveLast(p: OcParams) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("server", p.server).putInt("port", p.port).putString("user", p.user)
            .putString("password", p.password).putString("group", p.group).putString("pin", p.pin).apply()
    }

    private fun loadLast(): OcParams? {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val server = sp.getString("server", null) ?: return null
        return OcParams(server, sp.getInt("port", 443), sp.getString("user", "").orEmpty(),
            sp.getString("password", "").orEmpty(), sp.getString("group", "").orEmpty(), sp.getString("pin", "").orEmpty())
    }
}
