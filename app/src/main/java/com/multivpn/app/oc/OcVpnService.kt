package com.multivpn.app.oc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
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
 * الگو از OpenTunnel (LGPL-2.1)؛ نسخهٔ سادهٔ UC X: فقط تونل کامل و بدون UI اضافی.
 */
class OcVpnService : VpnService() {

    companion object {
        private const val TAG = "OpenConnect"
        const val ACTION_CONNECT = "com.multivpn.app.oc.CONNECT"
        const val ACTION_DISCONNECT = "com.multivpn.app.oc.DISCONNECT"
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

        private const val CHANNEL_ID = "oc_vpn"
        private const val NOTIFICATION_ID = 2201
        private const val MTU = 1400
        private const val FALLBACK_DNS = "8.8.8.8"

        private val stateFlow = MutableStateFlow(STATE_IDLE to "")
        /** (وضعیت، توضیح) برای نمایش در UI. */
        val state: StateFlow<Pair<String, String>> = stateFlow.asStateFlow()

        @Volatile
        private var instance: OcVpnService? = null

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onDestroy() {
        stopAll()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopAll()
                stopSelf()
            }
            ACTION_CONNECT -> {
                if (worker?.isAlive == true) return START_NOT_STICKY
                val p = OcParams(
                    server = intent.getStringExtra(EXTRA_SERVER).orEmpty(),
                    port = intent.getIntExtra(EXTRA_PORT, 443),
                    user = intent.getStringExtra(EXTRA_USER).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    group = intent.getStringExtra(EXTRA_GROUP).orEmpty(),
                    pin = intent.getStringExtra(EXTRA_PIN).orEmpty(),
                )
                if (p.server.isBlank() || p.user.isBlank()) {
                    stateFlow.value = STATE_FAILED to "سرور و نام کاربری الزامی است"
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال…"))
                stateFlow.value = STATE_CONNECTING to ""
                worker = Thread({ runTunnel(p) }, "oc-engine").also { it.start() }
            }
        }
        return START_NOT_STICKY
    }

    private fun runTunnel(p: OcParams) {
        val s = OcSession(p, { fd -> instance?.protect(fd) ?: false })
        session = s
        val error: String? = try {
            tunnel(s, p)
        } catch (t: Throwable) {
            "خطای موتور OpenConnect: ${t.message ?: t.javaClass.simpleName}"
        }
        closeTun()
        runCatching { s.destroy() }
        session = null
        stateFlow.value = if (error == null) STATE_IDLE to "" else STATE_FAILED to error
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        updateNotification("متصل · ${ip.addr}")
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

    private fun prefixOf(mask: String?): Int =
        mask?.split(".")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }
            ?.sumOf { Integer.bitCount(it) } ?: 32

    private fun stopAll() {
        runCatching { session?.cancel() }
        closeTun()
        worker = null
        stateFlow.value = STATE_IDLE to ""
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "OpenConnect", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val disconnect = PendingIntent.getService(
            this, 0,
            Intent(this, OcVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("OpenConnect (AnyConnect)")
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

    /** نشست موتور: پاسخ به فرم احراز هویت، محافظت سوکت‌ها، و بررسی گواهی. */
    class OcSession(
        private val p: OcParams,
        private val protect: (Int) -> Boolean,
    ) : LibOpenConnect() {
        private var groupApplied = false
        private var passwordUsed = false

        override fun onProgress(level: Int, msg: String?) {
            Log.println(if (level == LibOpenConnect.PRG_ERR) Log.ERROR else Log.INFO, TAG, msg ?: "")
        }

        override fun onProtectSocket(fd: Int) {
            protect(fd)
        }

        override fun onValidatePeerCert(msg: String?): Int {
            if (p.pin.isBlank()) {
                Log.w(TAG, "گواهی سرور بدون پین بررسی شد؛ برای امنیت بیشتر پین sha256 را وارد کنید")
                return 0
            }
            return if (checkPeerCertHash(p.pin) == 0) 0 else -1
        }

        override fun onProcessAuthForm(form: LibOpenConnect.AuthForm?): Int {
            form ?: return LibOpenConnect.OC_FORM_RESULT_ERR

            val groupOpt = form.authgroupOpt
            if (groupOpt != null && p.group.isNotBlank() && !groupApplied) {
                val match = groupOpt.choices.orEmpty().firstOrNull {
                    it.name.equals(p.group, ignoreCase = true) || it.label.equals(p.group, ignoreCase = true)
                }
                groupApplied = true
                if (match != null && groupOpt.value != match.name) {
                    groupOpt.value = match.name
                    return LibOpenConnect.OC_FORM_RESULT_NEWGROUP
                }
            }

            var unfilled = false
            for (opt in form.opts.orEmpty()) {
                if (opt.flags and LibOpenConnect.OC_FORM_OPT_IGNORE.toLong() != 0L) continue
                when (opt.type) {
                    LibOpenConnect.OC_FORM_OPT_HIDDEN -> Unit
                    LibOpenConnect.OC_FORM_OPT_TEXT -> if (looksLikeUsername(opt)) opt.value = p.user else unfilled = true
                    LibOpenConnect.OC_FORM_OPT_PASSWORD -> if (p.password.isNotEmpty() && !passwordUsed) {
                        opt.value = p.password
                        passwordUsed = true
                    } else {
                        unfilled = true
                    }
                    LibOpenConnect.OC_FORM_OPT_SELECT -> {
                        val match = opt.choices.orEmpty().firstOrNull {
                            it.name.equals(p.group, ignoreCase = true) || it.label.equals(p.group, ignoreCase = true)
                        }
                        if (match != null) opt.value = match.name else unfilled = true
                    }
                    else -> unfilled = true
                }
            }
            return if (unfilled) LibOpenConnect.OC_FORM_RESULT_ERR else LibOpenConnect.OC_FORM_RESULT_OK
        }

        private fun looksLikeUsername(opt: LibOpenConnect.FormOpt): Boolean {
            val n = (opt.name.orEmpty() + " " + opt.label.orEmpty()).lowercase()
            return n.contains("user") || n.contains("login") || n.contains("name") || n.contains("email")
        }
    }
}
