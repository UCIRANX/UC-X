package ir.uciran.uctor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File

class UcTorApp : Application() {

    companion object {
        const val CHANNEL_ID = "uctor_service"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "اتصال Tor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // The UI brain only lives in the main process; the ":tor" process just runs tor.
        if (isMainProcess()) {
            // پروسه‌ی تازه یعنی سرویس VPN قبلی دیگر زنده نیست؛ پرچم قدیمی مانع شروع دوباره‌ی VPN نشود.
            Prefs(this).vpnRunning = false
            TorController.init(this)
        }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().trim { it == '\u0000' || it.isWhitespace() }
            }.getOrDefault(packageName)
        }
        return name == packageName
    }
}
