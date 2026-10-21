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
    @Volatile
    var lastEngineLog: String = ""

    @JvmStatic
    fun emitEngineLogFromNative(priority: Int, tag: String?, message: String?) {
        val line = message ?: ""
        Log.println(priority, "L2TP-${tag ?: "native"}", line)
        lastEngineLog = line
    }
}
