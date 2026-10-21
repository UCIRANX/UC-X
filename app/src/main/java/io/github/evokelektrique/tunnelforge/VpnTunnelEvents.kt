package io.github.evokelektrique.tunnelforge

import android.util.Log
import androidx.annotation.Keep

/**
 * پل لاگ موتور C به Logcat. نام کلاس و امضای متد با vpn_jni/util.c هم‌خوان است.
 * (نسخهٔ سادهٔ UC X؛ بدون Flutter)
 */
@Keep
object VpnTunnelEvents {
    /** آخرین پیام موتور؛ در متن خطا به کاربر نشان داده می‌شود. */
    private val recent = ArrayDeque<String>()

    /** چند پیام آخر موتور، برای نمایش در متن خطا. */
    @Synchronized
    fun recentLogs(n: Int = 4): String = recent.takeLast(n).joinToString(" | ")

    @JvmStatic
    @Synchronized
    fun emitEngineLogFromNative(priority: Int, tag: String?, message: String?) {
        val line = message ?: ""
        Log.println(priority, "L2TP-${tag ?: "native"}", line)
        recent.addLast(line)
        if (recent.size > 12) recent.removeFirst()
    }
}
