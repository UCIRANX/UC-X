package com.multivpn.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multivpn.app.security.DeviceIdProvider
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import com.multivpn.app.security.TimeGuard
import ir.uciran.uctor.Phase
import ir.uciran.uctor.TorController
import ir.uciran.uctor.ui.Bg
import ir.uciran.uctor.ui.MainScreen
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Violet
import ir.uciranx.ucg.core.StateBus
import ir.uciranx.ucg.core.VpnState
import ir.uciranx.ucg.ui.GuardRoot
import ir.uciranx.ucg.ui.UcgTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** دو حالت برنامه: Sing-Box (سرورهای ساب‌لینک) و تور (موتور UC-Tor). */
enum class AppMode(val label: String) { GUARD("گارد"), TOR("تور"), VPN("سینگ باکس"), SSTP("SSTP"), IKEV2("پلاس") }

private val TextSub = Color(0xFFB4B4C8)

/** اگر VPN فعالی هست، همان بخش باز شود؛ وگرنه گارد. */
private fun initialMode(ctx: Context): AppMode {
    if (SingBoxController.connected || SingBoxController.connecting) return AppMode.VPN
    if (isSstpActive(ctx)) return AppMode.SSTP
    val l2 = io.github.evokelektrique.tunnelforge.TunnelVpnService.state.value.first
    val oc = com.multivpn.app.oc.OcVpnService.state.value.first
    if (l2 == io.github.evokelektrique.tunnelforge.TunnelVpnService.STATE_CONNECTED ||
        l2 == io.github.evokelektrique.tunnelforge.TunnelVpnService.STATE_CONNECTING ||
        oc == com.multivpn.app.oc.OcVpnService.STATE_CONNECTED ||
        oc == com.multivpn.app.oc.OcVpnService.STATE_CONNECTING) return AppMode.IKEV2
    if (TorController.state.value.phase != Phase.IDLE) return AppMode.TOR
    if (StateBus.state.value != VpnState.Idle) return AppMode.GUARD
    return AppMode.GUARD
}

@Composable
fun AppRoot(onGuardConnect: () -> Unit = {}) {
    val context = LocalContext.current
    val torState by TorController.state.collectAsStateWithLifecycle()
    val torPhase = torState.phase
    var mode by rememberSaveable { mutableStateOf(initialMode(context)) }
    var license by remember { mutableStateOf(LicenseManager.getCurrentLicenseStatus(context)) }
    var showManage by remember { mutableStateOf(false) }
    val valid = license is LicenseStatus.Valid
    val deviceId = remember { DeviceIdProvider.getFormattedDeviceId(context) }

    LaunchedEffect(Unit) {
        SingBoxController.ensureLoaded(context)
        TimeGuard.syncNetworkTime(context)
        var wasValid = valid
        while (isActive) {
            SingBoxController.refreshFromStore(context)
            val now = LicenseManager.getCurrentLicenseStatus(context)
            license = now
            val nowValid = now is LicenseStatus.Valid
            if (wasValid && !nowValid) {
                SingBoxController.stop(context, "مهلت لایسنس تمام شد")
                stopGuardIfActive(context)
                stopSstpIfActive(context)
                stopIkev2IfActive(context)
                TorController.disconnect()
            }
            wasValid = nowValid
            delay(1000L)
        }
    }

    // تور و Sing-Box هر دو VPN سراسری لازم دارند؛ وقتی تور شروع می‌شود Sing-Box قطع می‌شود
    LaunchedEffect(ModeRequest.requested) {
        if (ModeRequest.requested == "SSTP") mode = AppMode.SSTP
        if (ModeRequest.requested == "PROTO") mode = AppMode.IKEV2
        ModeRequest.requested = null
    }

    LaunchedEffect(torPhase) {
        if (torPhase == Phase.CONNECTING) {
            SingBoxController.stop(context, "سینگ باکس برای اتصال تور قطع شد")
            stopGuardIfActive(context)
            stopSstpIfActive(context)
            stopIkev2IfActive(context)
        }
    }

    val remaining = (license as? LicenseStatus.Valid)
        ?.let { LicenseManager.formatRemainingTime(it.remainingSeconds) }
        ?: "لایسنس فعال نیست"

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF1C1238), Bg, Bg))
            )
        ) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            ) {
                ModeSwitch(mode = mode, onChange = { mode = it })
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable(enabled = valid) { showManage = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("شناسه ", color = TextSub, fontSize = 12.sp, style = ContentStyle)
                    SelectionContainer {
                        Text(deviceId, color = TextSub, fontSize = 12.sp, style = LtrStyle)
                    }
                    Text("  ·  ", color = TextSub, fontSize = 12.sp, style = ContentStyle)
                    Text(remaining, color = TextSub, fontSize = 12.sp, style = ContentStyle)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (mode) {
                        AppMode.VPN -> VpnScreen(licenseValid = valid)
                        AppMode.TOR -> MainScreen()
                        AppMode.GUARD -> UcgTheme { GuardRoot(onConnect = onGuardConnect) }
                        AppMode.SSTP -> SstpScreen()
                        AppMode.IKEV2 -> ProtocolScreen()
                    }
                }
            }
            if (!valid) LicenseGate(status = license, onActivated = { license = it })
            if (showManage && valid) LicenseManageDialog(
                status = license,
                onDismiss = { showManage = false },
                onChanged = { license = it },
            )
        }
    }
}

@Composable
private fun ModeSwitch(mode: AppMode, onChange: (AppMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .background(Surface1, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppMode.entries.forEach { m ->
            val on = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Violet else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onChange(m) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label,
                    color = if (on) Color.White else TextSub,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    style = ContentStyle,
                )
            }
        }
    }
}

/** درخواست باز کردن یک بخش از بیرون (مثلاً کلیک روی اعلان). */
object ModeRequest {
    var requested by mutableStateOf<String?>(null)
}
