package com.multivpn.app

import android.app.Activity
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
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
import com.multivpn.app.sub.VpnConfig
import ir.uciran.uctor.ui.Cyan
import ir.uciran.uctor.ui.GlassCard
import ir.uciran.uctor.ui.Green
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Violet

private val TextMain = Color(0xFFEDEDF5)
private val TextSub = Color(0xFFB4B4C8)

private fun pingLabel(c: VpnConfig): String = when {
    c.pingMs > 0 -> "${c.pingMs} ms"
    c.pingMs == -2L -> "بی‌پاسخ"
    else -> "—"
}

/** صفحهٔ Sing-Box: فقط سرورها، اتصال و تست پینگ. وضعیت از SingBoxController می‌آید. */
@Composable
fun VpnScreen(licenseValid: Boolean) {
    val context = LocalContext.current
    val ctl = SingBoxController

    LaunchedEffect(Unit) { ctl.ensureLoaded(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) ctl.start(context)
        else ctl.reportStatus("اجازهٔ VPN داده نشد؛ برای اتصال باید اجازه بدهید")
    }

    val accent = when {
        ctl.paused -> Color(0xFFFFB300)
        ctl.connected -> Green
        ctl.connecting -> Cyan
        else -> Violet
    }
    val circleText = when {
        ctl.paused -> "ادامه"
        ctl.connecting -> "لغو"
        ctl.connected -> "قطع"
        else -> "اتصال"
    }
    val canTap = licenseValid && (!ctl.busy || ctl.connecting || ctl.connected)
    val allList = ctl.displayed.filter { it.coreType != "AmneziaWG" }
    val awgList = ctl.displayed.filter { it.coreType == "AmneziaWG" }
    val awgTabState = rememberSaveable { mutableStateOf(false) }
    val sorted = if (awgTabState.value) awgList else allList
    val selected = ctl.selected

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
                .clickable(enabled = canTap) {
                    if (ctl.paused) {
                        ctl.resume(context)
                    } else if (ctl.connecting || ctl.connected) {
                        val wasConnecting = ctl.connecting
                        ctl.stop(context, if (wasConnecting) "اتصال لغو شد" else "اتصال قطع شد")
                    } else {
                        val prep = VpnService.prepare(context)
                        if (prep != null) permissionLauncher.launch(prep) else ctl.start(context)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(circleText, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Text(
            ctl.status,
            color = TextMain,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            style = ContentStyle,
            modifier = Modifier.fillMaxWidth(),
        )

        GlassCard(padding = 12.dp) {
            if (selected == null) {
                Text("سروری انتخاب نشده", color = TextSub, fontSize = 14.sp, style = ContentStyle)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            selected.name,
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = ContentStyle,
                        )
                        Text(selected.protocol.uppercase(), color = Cyan, fontSize = 12.sp, style = LtrStyle)
                    }
                    Text(pingLabel(selected), color = TextSub, fontSize = 13.sp, style = LtrStyle)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "همه سرورها (${allList.size})", true to "AmneziaWG (${awgList.size})").forEach { (isAwg, label) ->
                val on = awgTabState.value == isAwg
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (on) Violet else Surface1, RoundedCornerShape(50))
                        .clickable { awgTabState.value = isAwg }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) Color.White else TextSub, fontSize = 13.sp, maxLines = 1, style = ContentStyle)
                }
            }
        }
        // فهرست سرورها: سرورهای پاسخ‌دهنده بالا و به ترتیب رسیدن نتیجه نمایش داده می‌شوند
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Surface1, RoundedCornerShape(18.dp))
                .padding(6.dp),
        ) {
            if (sorted.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لیست سرورها خالی است", color = TextSub, fontSize = 14.sp, style = ContentStyle)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sorted, key = { it.id }) { cfg ->
                        val isSel = ctl.selectedId == cfg.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) Violet.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { ctl.select(context, cfg) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cfg.protocol.uppercase(), color = Cyan, fontSize = 11.sp, style = LtrStyle)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                cfg.name,
                                color = TextMain,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = ContentStyle,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                pingLabel(cfg),
                                color = if (cfg.pingMs > 0) Green else TextSub,
                                fontSize = 12.sp,
                                style = LtrStyle,
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { ctl.updateSubscriptions(context) },
                enabled = !ctl.busy,
                modifier = Modifier.weight(1f),
            ) { Text("بروزرسانی سرورها", maxLines = 1) }
            OutlinedButton(
                onClick = { if (ctl.pinging) ctl.stopPing() else ctl.pingAll(context) },
                enabled = ctl.pinging || !ctl.busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (ctl.pinging) "متوقف کردن" else "تست پینگ", maxLines = 1) }
        }
        Text(
            if (ctl.lastUpdate.isBlank()) "هنوز سرورها بروزرسانی نشده؛ هر ۲۴ ساعت خودکار بروز می‌شوند"
            else "آخرین بروزرسانی: ${ctl.lastUpdate} · هر ۲۴ ساعت خودکار",
            color = TextSub,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            style = ContentStyle,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
    }
}
