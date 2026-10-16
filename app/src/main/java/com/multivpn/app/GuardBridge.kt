package com.multivpn.app

import android.content.Context
import ir.uciranx.ucg.core.StateBus
import ir.uciranx.ucg.core.UcgVpnService
import ir.uciranx.ucg.core.VpnState

/** اگر گارد وصل یا در حال اتصال است، قطعش می‌کند (هر سه حالت VPN سراسری لازم دارند). */
fun stopGuardIfActive(ctx: Context) {
    if (StateBus.state.value != VpnState.Idle) {
        UcgVpnService.send(ctx, UcgVpnService.ACTION_DISCONNECT)
    }
}
