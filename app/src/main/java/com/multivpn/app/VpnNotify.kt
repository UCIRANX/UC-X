package com.multivpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import androidx.core.app.NotificationCompat
import java.io.File

/** ابزار مشترک نوتیفیکیشن سرویس‌های بخش پروتکل‌ها (L2TP و OpenConnect). */
object VpnNotify {
    const val EXTRA_OPEN = "open_mode"
    const val OPEN_PROTO = "PROTO"

    /** سرعت واقعی: فقط شمارندهٔ خود رابط tun (نه کل ترافیک دستگاه که دوبار می‌شمارد). */
    fun tunCounters(): Pair<Long, Long>? = runCatching {
        File("/proc/net/dev").readLines().firstNotNullOfOrNull { line ->
            val t = line.trim()
            if (!t.startsWith("tun")) return@firstNotNullOfOrNull null
            val nums = t.substringAfter(":").trim().split(Regex("\\s+"))
            if (nums.size < 9) null else nums[0].toLong() to nums[8].toLong()
        }
    }.getOrNull()

    /**
     * شمارندهٔ واقعی برای سرعت: اول رابط tun، و اگر خوانده نشد، آمار UID خود برنامه
     * (ترافیک تونل که موتور از سوکت‌های همین برنامه می‌فرستد). هرگز صفر ساختگی نیست.
     */
    fun counters(uid: Int): Pair<Long, Long> =
        tunCounters() ?: (TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L) to TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L))

    fun speedText(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    fun channel(ctx: Context, id: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** کلیک روی نوتیفیکیشن: برنامه باز می‌شود و بخش پروتکل‌ها نشان داده می‌شود. */
    fun openAppIntent(ctx: Context, code: Int): PendingIntent = PendingIntent.getActivity(
        ctx,
        code,
        Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, OPEN_PROTO)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun serviceIntent(ctx: Context, cls: Class<*>, action: String, code: Int): PendingIntent = PendingIntent.getService(
        ctx,
        code,
        Intent(ctx, cls).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * عنوان (title) همان خط اول نوتیفیکیشن است و بدون بزرگ‌کردن دیده می‌شود،
     * به همین دلیل سرعت لحظه‌ای در عنوان نوشته می‌شود.
     */
    fun build(
        ctx: Context,
        channelId: String,
        title: String,
        text: String,
        actions: List<Pair<String, PendingIntent>>,
        ongoing: Boolean,
        open: PendingIntent,
    ): Notification {
        val b = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        actions.forEach { (label, pi) -> b.addAction(android.R.drawable.ic_menu_close_clear_cancel, label, pi) }
        return b.build()
    }
}
