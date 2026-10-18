package com.multivpn.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import ir.uciran.uctor.ui.Green
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Violet

private val TextMain = Color(0xFFEDEDF5)
private val TextSub = Color(0xFFB4B4C8)
private val HeartRed = Color(0xFFFF5C7A)
private val TrashRed = Color(0xFFFF6B6B)

private fun pingLabel(c: VpnConfig): String = when {
    c.pingMs > 0 -> "${c.pingMs} ms"
    c.pingMs == -2L -> "بی‌پاسخ"
    else -> "—"
}

/** صفحهٔ Sing-Box: عمودی یک ستون، افقی دو ستون. هر سرور قلب، و کلیپ‌برد سطل زباله دارد. */
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
    val showPersonal = rememberSaveable { mutableStateOf(false) }
    val allList = ctl.displayed.filter { it.coreType != "AmneziaWG" }
    val awgList = ctl.displayed.filter { it.coreType == "AmneziaWG" }
    val shownList = if (awgTabState.value) awgList else allList
    val canTap = licenseValid && (!ctl.busy || ctl.connecting || ctl.connected)
    val allTitle = allList.map { it.protocol.uppercase() }.distinct().joinToString(", ").ifBlank { "همه" } + " (${allList.size})"

    if (landscape) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ConnectCircle(canTap, 100, requestPermission)
                StatusText()
                SelectedCard()
                ActionButtons(showPersonal)
                LastUpdateText()
            }
            Column(
                Modifier.weight(1.2f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TabsRow(awgTabState, allTitle, awgList.size)
                ServerList(shownList, Modifier.weight(1f))
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConnectCircle(canTap, 104, requestPermission)
            StatusText()
            SelectedCard()
            TabsRow(awgTabState, allTitle, awgList.size)
            ServerList(shownList, Modifier.weight(1f))
            ActionButtons(showPersonal)
            LastUpdateText()
        }
    }

    if (showPersonal.value) {
        PersonalSubsDialog(onDismiss = { showPersonal.value = false })
    }
}

@Composable
private fun ConnectCircle(canTap: Boolean, sizeDp: Int, onRequestPermission: (Intent) -> Unit) {
    val context = LocalContext.current
    val ctl = SingBoxController
    val accent = when {
        ctl.paused -> Color(0xFFFFB300)
        ctl.connected -> Green
        ctl.connecting -> Cyan
        else -> Violet
    }
    val label = when {
        ctl.paused -> "ادامه"
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
                when {
                    ctl.paused -> ctl.resume(context)
                    ctl.connecting || ctl.connected -> {
                        val wasConnecting = ctl.connecting
                        ctl.stop(context, if (wasConnecting) "اتصال لغو شد" else "اتصال قطع شد")
                    }
                    else -> {
                        val prep = VpnService.prepare(context)
                        if (prep != null) onRequestPermission(prep) else ctl.start(context)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusText() {
    Text(
        SingBoxController.status,
        color = TextMain,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        style = ContentStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SelectedCard() {
    val selected = SingBoxController.selected
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (selected == null) {
            Text("سروری انتخاب نشده", color = TextSub, fontSize = 13.sp, style = ContentStyle)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected.name,
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ContentStyle,
                    modifier = Modifier.weight(1f),
                )
                Text(pingLabel(selected), color = TextSub, fontSize = 12.sp, style = LtrStyle)
            }
        }
    }
}

@Composable
private fun TabsRow(awgTabState: androidx.compose.runtime.MutableState<Boolean>, allTitle: String, awgCount: Int) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(false to allTitle, true to "AmneziaWG ($awgCount)").forEach { (isAwg, label) ->
            val on = awgTabState.value == isAwg
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Violet else Surface1, RoundedCornerShape(50))
                    .border(1.dp, if (on) Violet else Violet.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .clickable { awgTabState.value = isAwg; SingBoxController.selectTab(ctx, isAwg) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) Color.White else TextMain,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    style = ContentStyle,
                )
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
            .background(Surface1, RoundedCornerShape(16.dp))
            .padding(4.dp),
    ) {
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لیست سرورها خالی است", color = TextSub, fontSize = 13.sp, style = ContentStyle)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { cfg ->
                    ServerRow(cfg, isSel = ctl.selectedId == cfg.id, isFav = cfg.id in ctl.favKeys,
                        isClip = cfg.id in ctl.clipKeys, onSelect = { ctl.select(context, cfg) },
                        onFav = { ctl.toggleFavorite(context, cfg) },
                        onDelete = { ctl.deleteClip(context, cfg.id) })
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    cfg: VpnConfig,
    isSel: Boolean,
    isFav: Boolean,
    isClip: Boolean,
    onSelect: () -> Unit,
    onFav: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSel) Violet.copy(alpha = 0.22f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = "قلب",
            tint = if (isFav) HeartRed else TextSub,
            modifier = Modifier.size(18.dp).clickable { onFav() },
        )
        Spacer(Modifier.size(6.dp))
        Text(cfg.protocol.uppercase(), color = Cyan, fontSize = 10.sp, style = LtrStyle)
        Spacer(Modifier.size(6.dp))
        Text(
            cfg.name,
            color = TextMain,
            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ContentStyle,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            pingLabel(cfg),
            color = if (cfg.pingMs > 0) Green else TextSub,
            fontSize = 11.sp,
            style = LtrStyle,
        )
        if (isClip) {
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "حذف",
                tint = TrashRed,
                modifier = Modifier.size(18.dp).clickable { onDelete() },
            )
        }
    }
}

@Composable
private fun ActionButtons(showPersonal: androidx.compose.runtime.MutableState<Boolean>) {
    val context = LocalContext.current
    val ctl = SingBoxController
    val small = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
    val teal = Color(0xFF0E9AA7)
    val amber = Color(0xFFE0A100)
    val green = Color(0xFF2E9E5B)
    val pink = Color(0xFFC2437D)
    val dark = Color(0xFF111111)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = { ctl.updateSubscriptions(context) },
            enabled = !ctl.busy,
            colors = ButtonDefaults.buttonColors(containerColor = teal, contentColor = Color.White,
                disabledContainerColor = teal.copy(alpha = 0.45f), disabledContentColor = Color.White.copy(alpha = 0.7f)),
            contentPadding = small,
            modifier = Modifier.weight(1f).height(36.dp),
        ) { Text("بروزرسانی", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        Button(
            onClick = { if (ctl.pinging) ctl.stopPing() else ctl.pingAll(context) },
            enabled = ctl.pinging || !ctl.busy,
            colors = ButtonDefaults.buttonColors(containerColor = amber, contentColor = dark,
                disabledContainerColor = amber.copy(alpha = 0.45f), disabledContentColor = dark.copy(alpha = 0.7f)),
            contentPadding = small,
            modifier = Modifier.weight(1f).height(36.dp),
        ) { Text(if (ctl.pinging) "متوقف" else "تست پینگ", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        Button(
            onClick = { ctl.addFromClipboard(context) },
            colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White),
            contentPadding = small,
            modifier = Modifier.weight(1f).height(36.dp),
        ) { Text("کلیپ‌برد", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        Button(
            onClick = { showPersonal.value = true },
            colors = ButtonDefaults.buttonColors(containerColor = pink, contentColor = Color.White),
            contentPadding = small,
            modifier = Modifier.weight(1f).height(36.dp),
        ) { Text("ساب‌لینک", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

@Composable
private fun LastUpdateText() {
    val lastUpdate = SingBoxController.lastUpdate
    Text(
        if (lastUpdate.isBlank()) "هنوز بروزرسانی نشده · هر ۲۴ ساعت خودکار"
        else "آخرین بروزرسانی: $lastUpdate · هر ۲۴ ساعت",
        color = TextSub,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        style = ContentStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}
