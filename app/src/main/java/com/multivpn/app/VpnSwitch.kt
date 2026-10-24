package com.multivpn.app

import android.content.Context
import com.multivpn.app.oc.OcVpnService
import io.github.evokelektrique.tunnelforge.TunnelVpnService
import ir.uciran.uctor.TorController

/**
 * همهٔ بخش‌ها (گارد، تور، سینگ‌باکس، پلاس، SSTP) از یک اسلات VPN سیستمی استفاده می‌کنند.
 * قبل از شروع هر بخش، بقیه قطع می‌شوند تا نوتیفیکیشن و اتصال تکراری باقی نماند.
 */
object VpnSwitch {
    /** قطع همهٔ بخش‌ها به‌جز [keep] (VPN، GUARD، SSTP، PLUS، TOR). */
    fun stopOthers(ctx: Context, keep: String) {
        if (keep != "VPN") SingBoxController.stop(ctx, "سینگ باکس برای اتصال «$keep» قطع شد")
        if (keep != "GUARD") stopGuardIfActive(ctx)
        if (keep != "SSTP") stopSstpIfActive(ctx)
        if (keep != "PLUS") {
            stopIkev2IfActive(ctx)
            if (TunnelVpnService.state.value.first != TunnelVpnService.STATE_IDLE) {
                ctx.startService(TunnelVpnService.disconnectIntent(ctx))
            }
            if (OcVpnService.state.value.first != OcVpnService.STATE_IDLE) {
                ctx.startService(OcVpnService.disconnectIntent(ctx))
            }
        }
        if (keep != "TOR" && TorController.state.value.phase.toString() != "IDLE") TorController.disconnect()
    }
}
