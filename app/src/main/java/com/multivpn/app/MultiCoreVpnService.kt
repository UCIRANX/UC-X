package com.multivpn.app

import android.app.NotificationChannel
import android.app.Service
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.multivpn.app.core.CoreConfigGenerator
import com.multivpn.app.core.CoreEngineRunner
import com.multivpn.app.core.Tun2SocksBridge
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MultiCoreVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.multivpn.app.action.STOP_VPN"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val coreName = intent?.getStringExtra("CORE_NAME") ?: "Sing-box (Xray)"
        val serverName = intent?.getStringExtra("SERVER_NAME") ?: "Server"
        val socksPort = intent?.getIntExtra("SOCKS_PORT", CoreConfigGenerator.LOCAL_SOCKS_PORT) ?: CoreConfigGenerator.LOCAL_SOCKS_PORT
        val serverPing = intent?.getLongExtra("SERVER_PING", -1L) ?: -1L

        startVpnForegroundNotification(coreName, serverName, serverPing)
        serviceScope.launch {
            establishFullTunWithMapDns(serverName, socksPort)
        }
        startExpirationWatchdog()
        return START_STICKY
    }

    // پیاده‌سازی دقیق Full TUN + MapDNS مطابق پروژه UC Tor برای باز شدن تلگرام و کل اپ‌ها
    private fun establishFullTunWithMapDns(serverName: String, socksPort: Int) {
        try {
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

            val pfd = builder.establish() ?: return
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
        } catch (_: Throwable) {}
    }

    private fun startVpnForegroundNotification(coreName: String, serverName: String, ping: Long) {
        val channelId = "vpn_active_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "وضعیت اتصال فیلترشکن", NotificationManager.IMPORTANCE_LOW))
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pingText = if (ping > 0) " (${ping}ms)" else ""
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("متصل به $coreName (Full TUN)")
            .setContentText("سرور: $serverName$pingText")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private fun startExpirationWatchdog() {
        serviceScope.launch {
            while (isActive) {
                if (LicenseManager.getCurrentLicenseStatus(applicationContext) !is LicenseStatus.Valid) {
                    sendExpirationNotification()
                    stopTunnel()
                    stopSelf()
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
        runCatching { CoreEngineRunner.stopCore() }
        stopTunnelOnly()
    }

    private fun sendExpirationNotification() {
        val channelId = "vpn_expiry_alert"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "هشدار انقضای کد ۵ روزه", NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("مهلت ۵ روزه به پایان رسید")
            .setContentText("اتصال فیلترشکن قطع شد. لطفاً کد جدید را از ربات تلگرام دریافت و وارد کنید.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notification)
    }

    override fun onDestroy() {
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
