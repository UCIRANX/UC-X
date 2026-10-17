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
    if (TorController.state.value.phase != Phase.IDLE) TorController.disconnect()
}
