package ir.uciran.uctor

import com.multivpn.app.R
import com.multivpn.app.MainActivity

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.torproject.jni.TorService
import java.io.File

/**
 * Runs inside the ":tor" process. Starts the pluggable transports, writes torrc and starts tor.
 * Stopping kills the whole process, so the next attempt always starts from a clean state.
 */
class TorRunnerService : Service() {

    companion object {
        const val ACTION_START = "ir.uciran.uctor.action.START"
        const val ACTION_STOP = "ir.uciran.uctor.action.STOP"
        const val EXTRA_CONFIG = "config"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context, cfg: String) {
            val i = Intent(ctx, TorRunnerService::class.java).setAction(ACTION_START).putExtra(EXTRA_CONFIG, cfg)
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, TorRunnerService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                runCatching { ContextCompat.startForegroundService(ctx, i) }
            }
        }
    }

    private var pt: PtManager? = null
    private var bound = false
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            if (!started) {
                started = true
                val cfg = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                Thread({ launchTor(cfg) }, "uctor-start").start()
            }
        } else {
            shutdown()
        }
        return START_NOT_STICKY
    }

    private fun launchTor(json: String) {
        try {
            writeState("starting")
            val cfg = JSONObject(json)
            val arr = cfg.optJSONArray("bridges") ?: JSONArray()
            val lines = (0 until arr.length()).map { arr.getString(it) }
            val types = lines.map { Planner.typeOf(it) }.filter { it != "vanilla" }.toSet()
            val ports = LinkedHashMap<String, Int>()
            if (types.isNotEmpty()) {
                val m = PtManager(File(filesDir, "pt_state"))
                pt = m
                lines.firstOrNull { Planner.typeOf(it) == "snowflake" }?.let { m.configureSnowflake(it) }
                for (t in types) ports[t] = m.start(t)
            }
            val torrc = torrcFile()
            torrc.parentFile?.mkdirs()
            torrc.writeText(TorrcBuilder.build(cfg, lines, ports))
            main.post {
                bound = try {
                    // Binding alone does not start Tor. ACTION_START is required by tor-android.
                    startService(Intent(this, TorService::class.java).setAction(TorService.ACTION_START))
                    bindService(Intent(this, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    writeState("error:" + (e.message ?: "tor start failed"))
                    false
                }
            }
            val socks = cfg.optInt("socks", 0)
            if (socks <= 0 || !waitForPort(socks, 30_000L)) {
                writeState("error:Tor SOCKS آماده نشد")
                shutdown()
                return
            }
            // Do not tell the UI that Tor is connected until the SOCKS listener is real.
            writeState("running")
        } catch (t: Throwable) {
            writeState("error:" + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            try {
                java.net.Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                Thread.sleep(250)
            }
        }
        return false
    }

    private fun torrcFile(): File = try {
        TorService::class.java.getMethod("getTorrc", Context::class.java).invoke(null, this) as File
    } catch (e: Throwable) {
        File(getDir("TorService", Context.MODE_PRIVATE), "torrc")
    }

    private fun writeState(s: String) {
        runCatching { File(filesDir, "runner.state").writeText(s) }
    }

    private fun shutdown() {
        try {
            if (bound) unbindService(conn)
        } catch (_: Exception) {
        }
        bound = false
        runCatching { stopService(Intent(this, TorService::class.java)) }
        runCatching { pt?.stopAll() }
        writeState("stopped")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    private fun goForeground() {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val stopIntent = Intent(this, TorRunnerService::class.java).setAction(ACTION_STOP)
        val stop = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 1, stopIntent, flags)
        else PendingIntent.getService(this, 1, stopIntent, flags)
        val n = NotificationCompat.Builder(this, UcTorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_uctor_mono)
            .setContentTitle("UC Tor")
            .setContentText("اتصال Tor فعال است")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "قطع اتصال", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }
}
