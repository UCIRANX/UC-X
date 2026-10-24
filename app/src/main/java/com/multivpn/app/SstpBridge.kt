package com.multivpn.app

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import ir.uciran.uctor.Phase
import ir.uciran.uctor.TorController
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService

/** آیا تونل SSTP فعال است (همان کلید ROOT_STATE موتور kittoku). */
fun isSstpActive(ctx: Context): Boolean =
    getBooleanPrefValue(OscPrefKey.ROOT_STATE, PreferenceManager.getDefaultSharedPreferences(ctx))

/** اگر SSTP فعال است، قطعش می‌کند. */
fun stopSstpIfActive(ctx: Context) {
    if (isSstpActive(ctx)) sendSstp(ctx, ACTION_VPN_DISCONNECT)
}

/** ارسال فرمان به سرویس SSTP؛ اتصال به‌صورت foreground شروع می‌شود. */
fun sendSstp(ctx: Context, action: String) {
    val intent = Intent(ctx, SstpVpnService::class.java).setAction(action)
    if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(intent)
    } else {
        ctx.startService(intent)
    }
}

/** هنگام اتصال SSTP، همه VPNهای دیگر قطع می‌شوند (فقط یک VPN سراسری می‌تواند فعال باشد). */
fun stopOthersForSstp(ctx: Context) {
    SingBoxController.stop(ctx, "سینگ باکس برای اتصال SSTP قطع شد")
    stopGuardIfActive(ctx)
    stopIkev2IfActive(ctx)
    if (TorController.state.value.phase != Phase.IDLE) TorController.disconnect()
}

/** اتصال به یک سرور SSTP: نوشتن تنظیمات موتور، قطع VPNهای دیگر، و شروع سرویس. */
fun connectSstpServer(ctx: Context, s: SstpServer) {
    VpnSwitch.stopOthers(ctx, "SSTP")
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    kittoku.osc.preference.accessor.setStringPrefValue(s.host, kittoku.osc.preference.OscPrefKey.HOME_HOSTNAME, prefs)
    kittoku.osc.preference.accessor.setIntPrefValue(s.port, kittoku.osc.preference.OscPrefKey.SSL_PORT, prefs)
    kittoku.osc.preference.accessor.setStringPrefValue(s.user, kittoku.osc.preference.OscPrefKey.HOME_USERNAME, prefs)
    kittoku.osc.preference.accessor.setStringPrefValue(s.pass, kittoku.osc.preference.OscPrefKey.HOME_PASSWORD, prefs)
    kittoku.osc.preference.accessor.setBooleanPrefValue(false, kittoku.osc.preference.OscPrefKey.SSL_DO_VERIFY, prefs)
    prefs.edit().putString("SSTP_SERVER_LABEL", s.label).apply()
    stopOthersForSstp(ctx)
    sendSstp(ctx, ACTION_VPN_CONNECT)
}
