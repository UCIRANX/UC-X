package com.multivpn.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import ir.uciran.uctor.ui.Cyan
import ir.uciran.uctor.ui.Green
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Violet
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TextMain = Color(0xFFEDEDF5)
private val TextSub = Color(0xFFB4B4C8)
private val HeartRed = Color(0xFFFF5C7A)
private val Danger = Color(0xFFFF6B6B)

/** تب SSTP: دو بخش جدا (ساب‌لینک و دستی) که یکی را انتخاب می‌کنیم؛ اتصال دایره‌ای بالای صفحه. */
@Composable
fun SstpScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp

    var rootOn by remember { mutableStateOf(getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)) }
    var homeStatus by remember { mutableStateOf(getStringPrefValue(OscPrefKey.HOME_STATUS, prefs)) }
    var paused by remember { mutableStateOf(prefs.getBoolean("SSTP_PAUSED", false)) }
    var label by remember { mutableStateOf(prefs.getString("SSTP_SERVER_LABEL", "") ?: "") }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                OscPrefKey.ROOT_STATE.name -> rootOn = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
                OscPrefKey.HOME_STATUS.name -> homeStatus = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs)
                "SSTP_PAUSED" -> paused = prefs.getBoolean("SSTP_PAUSED", false)
                "SSTP_SERVER_LABEL" -> label = prefs.getString("SSTP_SERVER_LABEL", "") ?: ""
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var manual by remember { mutableStateOf(SstpStore.loadManual(context)) }
    var sub by remember { mutableStateOf(SstpStore.loadSub(context)) }
    var favs by remember { mutableStateOf(SstpStore.loadFav(context)) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = ساب‌لینک، 1 = دستی
    var note by rememberSaveable { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var updated by remember { mutableStateOf(SstpStore.lastUpdated(context)) }

    val favKeys = favs.map { it.key }.toSet()
    val subDisplay = favs + sub.filter { it.key !in favKeys }
    val all = subDisplay + manual
    val connectedNow = homeStatus.isNotBlank()

    fun reloadLists() {
        manual = SstpStore.loadManual(context)
        sub = SstpStore.loadSub(context)
        favs = SstpStore.loadFav(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val s = all.firstOrNull { it.key == pendingKey }
        if (res.resultCode == Activity.RESULT_OK && s != null) connectSstpServer(context, s)
        else note = "اجازهٔ VPN داده نشد"
    }

    fun requestConnect(s: SstpServer) {
        pendingKey = s.key
        val prep = VpnService.prepare(context)
        if (prep != null) permissionLauncher.launch(prep) else connectSstpServer(context, s)
    }

    /** اگر وصل یا در حال اتصال بودیم، اول کامل قطع می‌شود؛ بعد سرور جدید وصل می‌شود. */
    fun switchTo(s: SstpServer) {
        selectedKey = s.key
        if (!rootOn && !paused) {
            requestConnect(s)
            return
        }
        scope.launch {
            if (rootOn) sendSstp(context, ACTION_VPN_DISCONNECT)
            val end = System.currentTimeMillis() + 10_000L
            while (getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs) && System.currentTimeMillis() < end) {
                delay(200L)
            }
            prefs.edit().putBoolean("SSTP_PAUSED", false).apply()
            requestConnect(s)
        }
    }

    val accent = when {
        connectedNow -> Green
        rootOn || paused -> Cyan
        else -> Violet
    }
    val statusText = when {
        connectedNow -> "متصل · $label"
        rootOn -> "در حال اتصال…"
        paused -> "متوقف موقت"
        else -> "قطع"
    }
    val circleText = when {
        rootOn -> "قطع"
        paused -> "ادامه"
        else -> "اتصال"
    }

    val onCircle: () -> Unit = {
        when {
            rootOn -> sendSstp(context, ACTION_VPN_DISCONNECT)
            else -> {
                val s = all.firstOrNull { it.key == selectedKey }
                if (s == null) note = "ابتدا یک سرور انتخاب کنید"
                else requestConnect(s)
            }
        }
    }

    val header: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(if (landscape) 96.dp else 112.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.35f))),
                    CircleShape,
                )
                .clickable { onCircle() },
            contentAlignment = Alignment.Center,
        ) {
            Text(circleText, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(statusText, color = TextMain, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note.isNotBlank()) Text(note, color = TextSub, fontSize = 11.sp, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showManual = true }, modifier = Modifier.weight(1f)) {
                Text("سرور دستی", fontSize = 12.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    busy = true
                    note = "در حال بروزرسانی…"
                    scope.launch {
                        try {
                            val n = withTimeoutOrNull(60_000L) { SstpSub.refresh(context) }
                            note = if (n == null) "بروزرسانی ناموفق یا زمان‌بر بود" else "$n سرور دریافت شد"
                        } catch (e: Exception) {
                            note = "بروزرسانی ناموفق: ${e.message}"
                        } finally {
                            reloadLists()
                            updated = SstpStore.lastUpdated(context)
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (busy) "در حال بروزرسانی…" else "بروزرسانی", fontSize = 12.sp, maxLines = 1)
            }
        }
        Text(
            if (updated <= 0L) "هنوز بروزرسانی نشده · هر ۱۲ ساعت خودکار"
            else "آخرین بروزرسانی: ${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(updated))} · هر ۱۲ ساعت",
            color = TextSub,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
    }

    val tabs: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "سرورهای ساب‌لینک (${subDisplay.size})", 1 to "سرورهای دستی (${manual.size})").forEach { (idx, title) ->
                val on = tab == idx
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Violet else Surface1)
                        .border(1.dp, if (on) Violet else Violet.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .clickable { tab = idx }
                        .padding(vertical = 9.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        color = if (on) Color.White else TextMain,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }

    val list: @Composable (Modifier) -> Unit = { mod ->
        val shown = if (tab == 0) subDisplay else manual
        Column(
            mod.fillMaxWidth().background(Surface1, RoundedCornerShape(16.dp)).padding(4.dp),
        ) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (tab == 0) "سروری از ساب‌لینک نیست؛ بروزرسانی کنید" else "سرور دستی ندارید",
                        color = TextSub, fontSize = 12.sp, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.key }) { s ->
                        val isSel = s.key == selectedKey
                        val isFav = s.key in favKeys
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) Violet.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { switchTo(s) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tab == 0) {
                                Icon(
                                    imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = "قلب",
                                    tint = if (isFav) HeartRed else TextSub,
                                    modifier = Modifier.size(18.dp).clickable {
                                        SstpStore.toggleFav(context, s)
                                        favs = SstpStore.loadFav(context)
                                    },
                                )
                                Spacer8()
                            }
                            Text(
                                s.label,
                                color = TextMain,
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (tab == 1) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "حذف",
                                    tint = Danger,
                                    modifier = Modifier.size(18.dp).clickable {
                                        SstpStore.saveManual(context, manual.filter { it.key != s.key })
                                        if (selectedKey == s.key) selectedKey = null
                                        reloadLists()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (landscape) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { header() }
            Column(Modifier.weight(1.2f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs()
                list(Modifier.weight(1f))
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            header()
            tabs()
            list(Modifier.weight(1f))
        }
    }

    if (showManual) {
        ManualServerDialog(
            onDismiss = { showManual = false },
            onSave = { s ->
                SstpStore.saveManual(context, manual.filter { it.key != s.key } + s)
                reloadLists()
                selectedKey = s.key
                tab = 1
                showManual = false
            },
        )
    }
}

@Composable
private fun Spacer8() {
    Box(Modifier.size(8.dp))
}

@Composable
private fun ManualServerDialog(onDismiss: () -> Unit, onSave: (SstpServer) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("سرور SSTP دستی", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("آدرس (هاست یا IP)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, singleLine = true, label = { Text("پورت (پیش‌فرض 443)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("نام کاربری") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("رمز") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(country, { country = it }, singleLine = true, label = { Text("کد کشور (اختیاری، مثل IR)") }, modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (host.isBlank()) {
                    error = "آدرس سرور را وارد کنید"
                    return@TextButton
                }
                onSave(SstpServer(host.trim(), port.toIntOrNull() ?: 443, user.trim(), pass, country.trim().uppercase()))
            }) { Text("ذخیره") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        },
    )
}
