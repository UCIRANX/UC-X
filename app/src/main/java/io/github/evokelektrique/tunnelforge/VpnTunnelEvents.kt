package io.github.evokelektrique.tunnelforge

import android.util.Log
import androidx.annotation.Keep

/**
 * پل لاگ موتور C به Logcat. نام کلاس و امضای متد با vpn_jni/util.c هم‌خوان است.
 * (نسخهٔ سادهٔ UC X؛ بدون Flutter)
 */
@Keep
object VpnTunnelEvents {
    @JvmStatic
    fun emitEngineLogFromNative(priority: Int, tag: String?, message: String?) {
        Log.println(priority, "L2TP-${tag ?: "native"}", message ?: "")
    }
}
