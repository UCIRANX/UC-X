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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

// رنگ‌های ثابت متن و نشانه
private val TextMain = Color(0xFFEDEDF5)
private val TextSub = Color(0xFFB4B4C8)
private val HeartRed = Color(0xFFFF5C7A)
private val TrashRed = Color(0xFFFF6B6B)
private val AmberPing = Color(0xFFE0A100)

// رنگ برچسب هر پروتکل
private val ChipVless = Color(0xFF9B7CFF)
private val ChipSs = Color(0xFF2EC4B6)
private val ChipHy2 = Color(0xFFFF7A6B)
private val ChipAwg = Color(0xFF4DA3FF)

/** رنگ برچسب پروتکل؛ ترتیب بررسی مهم است چون VLESS شامل SS است. */
private fun protoColor(c: VpnConfig): Color {
    val p = c.protocol.uppercase()
    return when {
        c.coreType == "AmneziaWG" -> ChipAwg
        p.contains("VLESS") -> ChipVless
        p.contains("HY") -> ChipHy2
        p.contains("SS") -> ChipSs
        else -> Cyan
    }
}

/** رنگ پینگ: سبز برای کم، کهربایی برای متوسط، قرمز برای زیاد؛ بدون پینگ خاکستری. */
private fun pingColor(ping: Long): Color = when {
    ping <= 0L -> TextSub
    ping < 300L -> Green
    ping < 1000L -> AmberPing
    else -> TrashRed
}

private fun pingLabel(c: VpnConfig): String = when {
    c.pingMs > 0 -> "${c.pingMs} ms"
    c.pingMs == -2L -> "بی‌پاسخ"
    else -> "—"
}

/** صفحهٔ Sing-Box: دکمهٔ اتصال پایین صفحه، سرور فعال کوچک بالا و فهرست سه‌ستونه. */
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
    val requestPermission: (Intent) -> Unit = { permissionLauncher.launch(it) }

    val awgTabState = rememberSaveable { mutableStateOf(false) }
    val showPersonal = rememberSaveable { mutableStateOf(false) }
    val allList = ctl.displayed.filter { it.coreType != "AmneziaWG" }
    val awgList = ctl.displayed.filter { it.coreType == "AmneziaWG" }
    val shownList = if (awgTabState.value) awgList else allList
    val canTap = licenseValid && (!ctl.busy || ctl.connecting || ctl.connected)
    val allTitle = allList.map { it.protocol.uppercase() }.distinct().joinToString(", ").ifBlank { "همه" } + " (${allList.size})"

    // ترتیب از بالا: سرور فعال، تب‌ها، فهرست، دکمه‌های ابزار، تاریخ بروزرسانی، دکمهٔ اتصال
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectedCard()
        TabsRow(awgTabState, allTitle, awgList.size)
        ServerGrid(shownList, Modifier.weight(1f))
        ActionButtons(showPersonal)
        LastUpdateText()
        ConnectButton(canTap, requestPermission)
    }

    if (showPersonal.value) {
        PersonalSubsDialog(onDismiss = { showPersonal.value = false })
    }
}

/** دکمهٔ اتصال: تمام متن‌های مربوط به اتصال داخل خود دکمه نمایش داده می‌شوند. */
@Composable
private fun ConnectButton(canTap: Boolean, onRequestPermission: (Intent) -> Unit) {
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
    // زیرمتن داخل دکمه: وضعیت فعلی، یا «آماده اتصال» وقتی وضعیتی نیست
    val sub = ctl.status.ifBlank { if (canTap) "آماده اتصال" else "" }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(accent, RoundedCornerShape(14.dp))
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ContentStyle,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

/** کارت کوچک سرور فعال با نمودار کندلی پینگ (فقط نمونه‌های واقعیِ همین نشست). */
@Composable
private fun SelectedCard() {
    val selected = SingBoxController.selected
    // نمونه‌های پینگ هر سرور؛ با تغییر سرور فهرست جدید ساخته می‌شود
    val samples = remember(selected?.id) { mutableStateListOf<Long>() }
    LaunchedEffect(selected?.id, selected?.pingMs) {
        val p = selected?.pingMs ?: 0L
        if (p > 0L) {
            samples.add(p)
            if (samples.size > 12) samples.removeAt(0)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (selected == null) {
            Text("سروری انتخاب نشده", color = TextSub, fontSize = 12.sp, style = ContentStyle)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected.name,
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ContentStyle,
                    modifier = Modifier.weight(1f),
                )
                Text(pingLabel(selected), color = pingColor(selected.pingMs), fontSize = 11.sp, style = LtrStyle)
            }
            Spacer(Modifier.height(4.dp))
            CandleBars(samples)
        }
    }
}

/** نمودار کندلی پینگ: هر میله یک اندازه‌گیری واقعی است. */
@Composable
private fun CandleBars(samples: List<Long>) {
    val maxV = (samples.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (samples.isEmpty()) {
            Text("هنوز نمونه‌ای ثبت نشده", color = TextSub, fontSize = 10.sp, style = ContentStyle)
        } else {
            samples.forEach { v ->
                val h = 6.dp + 16.dp * (v.toFloat() / maxV.toFloat())
                Box(Modifier.width(6.dp).height(h).background(pingColor(v), RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** فهرست سرورها به‌صورت شبکهٔ سه‌ستونه و جمع‌وجور. */
@Composable
private fun ServerGrid(list: List<VpnConfig>, modifier: Modifier) {
    val context = LocalContext.current
    val ctl = SingBoxController
    Box(
        modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(14.dp))
            .padding(6.dp),
    ) {
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لیست سرورها خالی است", color = TextSub, fontSize = 13.sp, style = ContentStyle)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list, key = { it.id }) { cfg ->
                    ServerTile(
                        cfg,
                        isSel = ctl.selectedId == cfg.id,
                        isFav = cfg.id in ctl.favKeys,
                        isClip = cfg.id in ctl.clipKeys,
                        onSelect = { ctl.select(context, cfg) },
                        onFav = { ctl.toggleFavorite(context, cfg) },
                        onDelete = { ctl.deleteClip(context, cfg.id) },
                    )
                }
            }
        }
    }
}

/** هر سرور: برچسب پروتکل، قلب، سطل (فقط کانفیگ دستی)، نام و پینگ. */
@Composable
private fun ServerTile(
    cfg: VpnConfig,
    isSel: Boolean,
    isFav: Boolean,
    isClip: Boolean,
    onSelect: () -> Unit,
    onFav: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSel) Violet.copy(alpha = 0.22f) else Color.Transparent)
            .border(0.5.dp, if (isSel) Violet else TextSub.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable { onSelect() }
            .padding(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                cfg.protocol.uppercase(),
                color = protoColor(cfg),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = LtrStyle,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "قلب",
                tint = if (isFav) HeartRed else TextSub,
                modifier = Modifier.size(14.dp).clickable { onFav() },
            )
            if (isClip) {
                Spacer(Modifier.size(3.dp))
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "حذف",
                    tint = TrashRed,
                    modifier = Modifier.size(14.dp).clickable { onDelete() },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            cfg.name,
            color = TextMain,
            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ContentStyle,
        )
        Text(pingLabel(cfg), color = pingColor(cfg.pingMs), fontSize = 10.sp, style = LtrStyle)
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
