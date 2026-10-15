package com.multivpn.app

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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multivpn.app.core.CoreEngineRunner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** دو حالت برنامه: VPN (سرورهای ساب‌لینک) و تور (موتور UC-Tor). هر حالت فقط گزینه‌های خودش را دارد. */
enum class AppMode(val label: String) { VPN("VPN"), TOR("تور") }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val torState by TorController.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(AppMode.VPN) }
    var license by remember { mutableStateOf(LicenseManager.getCurrentLicenseStatus(context)) }
    val valid = license is LicenseStatus.Valid

    // بررسی لایسنس و قطع خودکار هر دو حالت وقتی مهلت تمام شود
    LaunchedEffect(Unit) {
        TimeGuard.syncNetworkTime(context)
        var wasValid = valid
        while (isActive) {
            val now = LicenseManager.getCurrentLicenseStatus(context)
            license = now
            val nowValid = now is LicenseStatus.Valid
            if (wasValid && !nowValid) {
                CoreEngineRunner.stopCore()
                context.stopService(Intent(context, MultiCoreVpnService::class.java))
                TorController.disconnect()
            }
            wasValid = nowValid
            delay(1000L)
        }
    }

    fun switchMode(target: AppMode) {
        if (target == mode) return
        if (target == AppMode.TOR) {
            // VPN سراسری یکی بیشتر نمی‌تواند فعال باشد
            CoreEngineRunner.stopCore()
            context.stopService(Intent(context, MultiCoreVpnService::class.java))
        } else if (torState.phase != Phase.IDLE) {
            TorController.disconnect()
        }
        mode = target
    }

    val licenseLine = when (val s = license) {
        is LicenseStatus.Valid -> "شناسه ${DeviceIdProvider.getFormattedDeviceId(context)}  ·  ${LicenseManager.formatRemainingTime(s.remainingSeconds)}"
        else -> "شناسه ${DeviceIdProvider.getFormattedDeviceId(context)}  ·  لایسنس فعال نیست"
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1C1238), Bg, Bg))
        )
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
        ) {
            ModeSwitch(mode = mode, onChange = { switchMode(it) })
            Text(
                licenseLine,
                color = Color(0xFFB4B4C8),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (mode) {
                    AppMode.VPN -> VpnScreen(licenseValid = valid)
                    AppMode.TOR -> MainScreen()
                }
            }
        }
        if (!valid) LicenseGate(status = license, onActivated = { license = it })
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
                    color = if (on) Color.White else Color(0xFFB4B4C8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
