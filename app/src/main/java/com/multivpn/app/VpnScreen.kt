package com.multivpn.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.app.core.CoreConfigGenerator
import com.multivpn.app.core.CoreEngineRunner
import com.multivpn.app.sub.PingTester
import com.multivpn.app.sub.SubManager
import com.multivpn.app.sub.VpnConfig
import ir.uciran.uctor.ui.Cyan
import ir.uciran.uctor.ui.GlassCard
import ir.uciran.uctor.ui.Green
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Violet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val TextMain = Color(0xFFEDEDF5)
private val TextSub = Color(0xFFB4B4C8)

private fun pingLabel(c: VpnConfig): String = when {
    c.pingMs > 0 -> "${c.pingMs} ms"
    c.pingMs == -2L -> "بدون اینترنت"
    else -> "---"
}

/** حالت VPN: سرورهای ساب‌لینک، تست پینگ واقعی و اتصال با Full TUN. */
@Composable
fun VpnScreen(licenseValid: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var configs by remember { mutableStateOf(SubManager.loadCachedConfigs(context)) }
    var selected by remember { mutableStateOf(configs.firstOrNull()) }
    var isConnected by remember { mutableStateOf(CoreEngineRunner.isCoreRunning()) }
    var isConnecting by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (configs.isEmpty()) "ابتدا ساب‌لینک‌ها را بروزرسانی کنید" else "آماده اتصال")
    }
    val connectJob = remember { mutableStateOf<Job?>(null) }

    fun stopVpn() {
        connectJob.value?.cancel()
        connectJob.value = null
        isConnecting = false
        isConnected = false
        CoreEngineRunner.stopCore()
        context.stopService(Intent(context, MultiCoreVpnService::class.java))
    }

    fun startConnection() {
        val target = selected ?: configs.firstOrNull()
        if (target == null) {
            status = "سروری وجود ندارد"
            return
        }
        connectJob.value?.cancel()
        connectJob.value = scope.launch {
            isBusy = true
            isConnecting = true
            try {
                status = "در حال اتصال و تست روی ${target.name}..."
                val started = CoreEngineRunner.startCore(context, target)
                if (!isActive) { CoreEngineRunner.stopCore(); return@launch }
                if (!started) {
                    configs = configs.map { if (it.id == target.id) it.copy(pingMs = -2L) else it }
                    status = CoreEngineRunner.lastErrorLog.ifBlank { "خطا در راه‌اندازی سرور" }
                    return@launch
                }
                val ping = CoreEngineRunner.measureRealTunnelDelay()
                if (!isActive) { CoreEngineRunner.stopCore(); return@launch }
                if (ping <= 0) {
                    configs = configs.map { if (it.id == target.id) it.copy(pingMs = -2L) else it }
                    CoreEngineRunner.stopCore()
                    status = "این سرور اینترنت واقعی ندارد؛ سرور دیگری را انتخاب کنید"
                    return@launch
                }
                configs = configs.map { if (it.id == target.id) it.copy(pingMs = ping) else it }
                selected = target.copy(pingMs = ping)
                context.startService(Intent(context, MultiCoreVpnService::class.java).apply {
                    putExtra("CORE_NAME", target.coreType)
                    putExtra("SERVER_NAME", target.name)
                    putExtra("SOCKS_PORT", CoreConfigGenerator.LOCAL_SOCKS_PORT)
                    putExtra("SERVER_PING", ping)
                })
                isConnected = true
                status = "متصل · $ping ms"
            } finally {
                isBusy = false
                isConnecting = false
            }
        }
    }

    fun updateSubscriptions() {
        scope.launch {
            isBusy = true
            status = "در حال دریافت ساب‌لینک‌ها..."
            try {
                val fetched = SubManager.fetchAndParseSub(context)
                configs = fetched
                selected = fetched.firstOrNull()
                status = "${fetched.size} سرور دریافت شد"
            } catch (e: Exception) {
                status = "خطا در بروزرسانی: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    fun pingAll() {
        if (configs.isEmpty()) {
            status = "ابتدا ساب‌لینک‌ها را بروزرسانی کنید"
            return
        }
        scope.launch {
            if (isConnected) stopVpn()
            isBusy = true
            try {
                val tested = PingTester.pingAllConfigsRealHttp(context, configs) { msg -> status = msg }
                configs = tested
                val best = tested.firstOrNull { it.pingMs > 0 }
                if (best != null) {
                    selected = best
                    status = "✅ بهترین سرور: ${best.name} (${best.pingMs} ms)"
                } else {
                    status = "❌ هیچ سروری در این لحظه پاسخ نداد"
                }
            } finally {
                isBusy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) startConnection() else status = "مجوز VPN داده نشد"
    }

    // اگر هسته بیرون از برنامه بسته شود، وضعیت را درست نشان می‌دهیم
    LaunchedEffect(Unit) {
        while (isActive) {
            if (isConnected && !CoreEngineRunner.isCoreRunning()) {
                isConnected = false
                status = "اتصال قطع شد (هسته متوقف شد)"
            }
            delay(1000L)
        }
    }
    LaunchedEffect(licenseValid) {
        if (!licenseValid && isConnected) stopVpn()
    }

    val accent = when {
        isConnected -> Green
        isConnecting -> Cyan
        else -> Violet
    }
    val buttonText = when {
        isConnecting -> "لغو"
        isConnected -> "قطع"
        else -> "اتصال"
    }
    val canTapConnect = licenseValid && (!isBusy || isConnecting || isConnected)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .size(156.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.35f))),
                    CircleShape,
                )
                .clickable(enabled = canTapConnect) {
                    if (isConnecting || isConnected) {
                        val wasConnecting = isConnecting
                        stopVpn()
                        status = if (wasConnecting) "اتصال لغو شد" else "اتصال قطع شد"
                    } else {
                        val prep = VpnService.prepare(context)
                        if (prep != null) permissionLauncher.launch(prep) else startConnection()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(buttonText, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Text(
            status,
            color = TextMain,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        GlassCard(padding = 12.dp) {
            val s = selected
            if (s == null) {
                Text("سروری انتخاب نشده", color = TextSub, fontSize = 14.sp)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name,
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(s.protocol.uppercase(), color = Cyan, fontSize = 12.sp)
                    }
                    Text(pingLabel(s), color = TextSub, fontSize = 13.sp)
                }
            }
        }

        // فهرست سرورها: فضای باقی‌مانده را می‌گیرد و اسکرول می‌شود
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Surface1, RoundedCornerShape(18.dp))
                .padding(6.dp),
        ) {
            if (configs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لیست خالی است", color = TextSub, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(configs, key = { it.id }) { cfg ->
                        val isSel = selected?.id == cfg.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) Violet.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable {
                                    selected = cfg
                                    if (isConnected) {
                                        stopVpn()
                                        startConnection()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${cfg.protocol.uppercase()}  ${cfg.name}",
                                color = TextMain,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(pingLabel(cfg), color = TextSub, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { updateSubscriptions() },
                enabled = !isBusy && !isConnecting,
                modifier = Modifier.weight(1f),
            ) { Text("بروزرسانی سرورها", maxLines = 1) }
            OutlinedButton(
                onClick = { pingAll() },
                enabled = !isBusy && !isConnecting,
                modifier = Modifier.weight(1f),
            ) { Text("تست پینگ", maxLines = 1) }
        }
        Spacer(Modifier.height(4.dp))
    }
}
