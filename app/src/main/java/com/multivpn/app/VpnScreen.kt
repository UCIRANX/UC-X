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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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

/** صفحهٔ Sing-Box: در عمودی یک ستون، در افقی دو ستون (دکمه‌ها چپ، فهرست راست). */
@Composable
fun VpnScreen(licenseValid: Boolean) {
    val context = LocalContext.current
    val ctl = SingBoxController
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp

    LaunchedEffect(Unit) { ctl.ensureLoaded(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) ctl.start(context)
        else ctl.reportStatus("اجازهٔ VPN داده نشد؛ برای اتصال باید اجازه بدهید")
    }
    val requestPermission: (Intent) -> Unit = { permissionLauncher.launch(it) }

    val awgTabState = rememberSaveable { mutableStateOf(false) }
    val allList = ctl.displayed.filter { it.coreType != "AmneziaWG" }
    val awgList = ctl.displayed.filter { it.coreType == "AmneziaWG" }
    val shownList = if (awgTabState.value) awgList else allList
    val canTap = licenseValid && (!ctl.busy || ctl.connecting || ctl.connected)

    if (landscape) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ConnectCircle(canTap, 120, requestPermission)
                StatusText()
                SelectedCard()
                ActionButtons()
                LastUpdateText()
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TabsRow(awgTabState, allList.size, awgList.size)
                ServerList(shownList, Modifier.weight(1f))
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            ConnectCircle(canTap, 156, requestPermission)
            StatusText()
            SelectedCard()
            TabsRow(awgTabState, allList.size, awgList.size)
            ServerList(shownList, Modifier.weight(1f))
            ActionButtons()
            LastUpdateText()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ConnectCircle(canTap: Boolean, sizeDp: Int, onRequestPermission: (Intent) -> Unit) {
    val context = LocalContext.current
    val ctl = SingBoxController
    val accent = when {
        ctl.connected -> Green
        ctl.connecting -> Cyan
        else -> Violet
    }
    val label = when {
        ctl.connecting -> "لغو"
        ctl.connected -> "قطع"
        else -> "اتصال"
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .background(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.35f))),
                CircleShape,
            )
            .clickable(enabled = canTap) {
                if (ctl.connecting || ctl.connected) {
                    val wasConnecting = ctl.connecting
                    ctl.stop(context, if (wasConnecting) "اتصال لغو شد" else "اتصال قطع شد")
                } else {
                    val prep = VpnService.prepare(context)
                    if (prep != null) onRequestPermission(prep) else ctl.start(context)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusText() {
    Text(
        SingBoxController.status,
        color = TextMain,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        style = ContentStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SelectedCard() {
    val selected = SingBoxController.selected
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
}

@Composable
private fun TabsRow(awgTabState: androidx.compose.runtime.MutableState<Boolean>, allCount: Int, awgCount: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "همه سرورها ($allCount)", true to "AmneziaWG ($awgCount)").forEach { (isAwg, label) ->
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
}

@Composable
private fun ServerList(list: List<VpnConfig>, modifier: Modifier) {
    val context = LocalContext.current
    val ctl = SingBoxController
    Column(
        modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(18.dp))
            .padding(6.dp),
    ) {
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لیست سرورها خالی است", color = TextSub, fontSize = 14.sp, style = ContentStyle)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { cfg ->
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
}

@Composable
private fun ActionButtons() {
    val context = LocalContext.current
    val ctl = SingBoxController
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
}

@Composable
private fun LastUpdateText() {
    val lastUpdate = SingBoxController.lastUpdate
    Text(
        if (lastUpdate.isBlank()) "هنوز سرورها بروزرسانی نشده؛ هر ۲۴ ساعت خودکار بروز می‌شوند"
        else "آخرین بروزرسانی: $lastUpdate · هر ۲۴ ساعت خودکار",
        color = TextSub,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        style = ContentStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}
