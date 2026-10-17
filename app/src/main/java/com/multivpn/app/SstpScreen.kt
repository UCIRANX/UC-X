package com.multivpn.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TextMain = Color(0xFFEDEDF5)
private val TextSub = Color(0xFFB4B4C8)
private val Danger = Color(0xFFFF6B6B)

/** تب SSTP: سرورهای ساب‌لینک (بالا) و سرورهای دستی (پایین)، با اتصال دایره‌ای. */
@Composable
fun SstpScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scope = rememberCoroutineScope()

    var rootOn by remember { mutableStateOf(getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)) }
    var homeStatus by remember { mutableStateOf(getStringPrefValue(OscPrefKey.HOME_STATUS, prefs)) }
    var label by remember { mutableStateOf(prefs.getString("SSTP_SERVER_LABEL", "") ?: "") }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                OscPrefKey.ROOT_STATE.name -> rootOn = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
                OscPrefKey.HOME_STATUS.name -> homeStatus = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs)
                "SSTP_SERVER_LABEL" -> label = prefs.getString("SSTP_SERVER_LABEL", "") ?: ""
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var manual by remember { mutableStateOf(SstpStore.loadManual(context)) }
    var sub by remember { mutableStateOf(SstpStore.loadSub(context)) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var updated by remember { mutableStateOf(SstpStore.lastUpdated(context)) }

    val all = sub + manual
    val connectedNow = homeStatus.isNotBlank()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val s = all.firstOrNull { it.key == pendingKey }
        if (res.resultCode == Activity.RESULT_OK && s != null) connectSstpServer(context, s)
        else note = "اجازهٔ VPN داده نشد"
    }

    val accent = when {
        connectedNow -> Green
        rootOn -> Cyan
        else -> Violet
    }
    val statusText = when {
        connectedNow -> "متصل · $label"
        rootOn -> "در حال اتصال…"
        else -> "قطع"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.35f))),
                    CircleShape,
                )
                .clickable {
                    if (rootOn) {
                        sendSstp(context, ACTION_VPN_DISCONNECT)
                    } else {
                        val s = all.firstOrNull { it.key == selectedKey }
                        if (s == null) {
                            note = "ابتدا یک سرور انتخاب کنید"
                        } else {
                            pendingKey = s.key
                            val prep = VpnService.prepare(context)
                            if (prep != null) permissionLauncher.launch(prep) else connectSstpServer(context, s)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (rootOn) "قطع" else "اتصال",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(statusText, color = TextMain, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note.isNotBlank()) Text(note, color = TextSub, fontSize = 12.sp, textAlign = TextAlign.Center)

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
                            if (n == null) {
                                note = "بروزرسانی ناموفق یا زمان‌بر بود"
                            } else {
                                note = "$n سرور از ساب‌لینک دریافت شد"
                            }
                        } catch (e: Exception) {
                            note = "بروزرسانی ناموفق: ${e.message}"
                        } finally {
                            sub = SstpStore.loadSub(context)
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

        ServerSection(
            title = "سرورهای ساب‌لینک (${sub.size})",
            list = sub,
            selectedKey = selectedKey,
            onSelect = { selectedKey = it.key },
            onDelete = null,
        )
        ServerSection(
            title = "سرورهای دستی (${manual.size})",
            list = manual,
            selectedKey = selectedKey,
            onSelect = { selectedKey = it.key },
            onDelete = { s ->
                manual = manual.filter { it.key != s.key }
                SstpStore.saveManual(context, manual)
                if (selectedKey == s.key) selectedKey = null
            },
        )
    }

    if (showManual) {
        ManualServerDialog(
            onDismiss = { showManual = false },
            onSave = { s ->
                manual = manual.filter { it.key != s.key } + s
                SstpStore.saveManual(context, manual)
                selectedKey = s.key
                showManual = false
            },
        )
    }
}

@Composable
private fun ServerSection(
    title: String,
    list: List<SstpServer>,
    selectedKey: String?,
    onSelect: (SstpServer) -> Unit,
    onDelete: ((SstpServer) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = TextSub, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (list.isEmpty()) {
            Text("موردی نیست", color = TextSub, fontSize = 12.sp)
        }
        list.forEach { s ->
            val isSel = s.key == selectedKey
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) Violet.copy(alpha = 0.22f) else Surface1)
                    .clickable { onSelect(s) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.label,
                    color = TextMain,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "حذف",
                        tint = Danger,
                        modifier = Modifier.size(18.dp).clickable { onDelete(s) },
                    )
                }
            }
        }
    }
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
