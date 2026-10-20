package com.multivpn.app

import android.app.Activity
import android.os.Build
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import ir.uciran.uctor.ui.Cyan
import ir.uciran.uctor.ui.Green
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Violet

private val IkTextMain = Color(0xFFEDEDF5)
private val IkTextSub = Color(0xFFB4B4C8)
private val IkDanger = Color(0xFFFF6B6B)

/** تب IKEv2: سرورهای دستی (نام کاربری و رمز) و اتصال دایره‌ای با پروفایل سیستم اندروید. */
@Composable
fun Ikev2Screen() {
    val context = LocalContext.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var list by remember { mutableStateOf(Ikev2Store.load(context)) }
    var selectedKey by rememberSaveable { mutableStateOf(Ikev2Store.selected(context)) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Ikev2Server?>(null) }

    fun setSelected(key: String?) {
        selectedKey = key
        Ikev2Store.setSelected(context, key)
    }

    DisposableEffect(supported) {
        if (!supported) {
            onDispose { }
        } else {
            val cb = watchVpn(context) { connected = it }
            onDispose { unwatchVpn(context, cb) }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val s = list.firstOrNull { it.key == pendingKey }
        if (res.resultCode == Activity.RESULT_OK) {
            note = startIkev2(context, s) ?: ""
        } else {
            note = "اجازهٔ VPN داده نشد"
        }
    }

    val accent = when {
        connected -> Green
        else -> Violet
    }
    val statusText = when {
        !supported -> "IKEv2 فقط روی اندروید ۱۲ به بالا کار می‌کند"
        connected -> {
            val s = list.firstOrNull { it.key == selectedKey }
            if (s != null) "متصل · ${s.label}" else "متصل"
        }
        else -> "قطع"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.35f))),
                    CircleShape,
                )
                .clickable(enabled = supported) {
                    if (connected) {
                        stopIkev2(context)
                        note = "قطع شد"
                    } else {
                        val s = list.firstOrNull { it.key == selectedKey }
                        if (s == null) {
                            note = "ابتدا یک سرور انتخاب کنید"
                        } else {
                            pendingKey = s.key
                            var failure: String? = null
                            val intent = try {
                                ikev2ConsentIntent(context, s)
                            } catch (e: Exception) {
                                failure = e.message ?: "خطای ناشناخته"
                                null
                            }
                            when {
                                failure != null -> note = "پروفایل ساخته نشد: $failure"
                                intent != null -> consentLauncher.launch(intent)
                                else -> note = startIkev2(context, s) ?: ""
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (connected) "قطع" else "اتصال",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(statusText, color = IkTextMain, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note.isNotBlank()) Text(note, color = IkTextSub, fontSize = 11.sp, textAlign = TextAlign.Center)

        OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
            Text("سرور دستی", fontSize = 12.sp, maxLines = 1)
        }

        Text("سرورهای ذخیره‌شده (${list.size})", color = IkTextSub, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surface1, RoundedCornerShape(16.dp))
                .padding(4.dp),
        ) {
            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("سرور دستی ندارید؛ با دکمهٔ «سرور دستی» اضافه کنید", color = IkTextSub, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            } else {
                list.forEach { s ->
                    val isSel = s.key == selectedKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) Violet.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { setSelected(s.key) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.label,
                                color = IkTextMain,
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(s.server, color = Cyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { editing = s }) {
                            Text("ویرایش", fontSize = 12.sp, color = Violet)
                        }
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "حذف",
                            tint = IkDanger,
                            modifier = Modifier.size(18.dp).clickable {
                                val rest = list.filter { it.key != s.key }
                                Ikev2Store.save(context, rest)
                                list = rest
                                if (selectedKey == s.key) setSelected(null)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showManual || editing != null) {
        Ikev2ManualDialog(
            initial = editing,
            onDismiss = {
                showManual = false
                editing = null
            },
            onSave = { s ->
                val old = editing
                val rest = list.filter { it.key != s.key && (old == null || it.key != old.key) } + s
                Ikev2Store.save(context, rest)
                list = rest
                setSelected(s.key)
                showManual = false
                editing = null
            },
        )
    }
}

@Composable
private fun Ikev2ManualDialog(initial: Ikev2Server?, onDismiss: () -> Unit, onSave: (Ikev2Server) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var server by remember { mutableStateOf(initial?.server ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var remoteId by remember { mutableStateOf(initial?.remoteId ?: "") }
    var psk by remember { mutableStateOf(initial?.psk ?: "") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (initial == null) "سرور IKEv2 دستی" else "ویرایش سرور IKEv2", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("نام پروفایل (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(server, { server = it }, singleLine = true, label = { Text("سرور") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, singleLine = true, label = { Text("نام کاربری") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("رمز (اختیاری)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(remoteId, { remoteId = it }, singleLine = true, label = { Text("Server identity (اختیاری)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(psk, { psk = it }, singleLine = true, label = { Text("PSK (اختیاری؛ اگر پر شود، به جای EAP استفاده می‌شود)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = IkDanger, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (server.isBlank() || username.isBlank()) {
                    error = "سرور و نام کاربری را وارد کنید"
                    return@TextButton
                }
                onSave(Ikev2Server(name.trim(), server.trim(), username.trim(), password, remoteId.trim(), psk))
            }) { Text("ذخیره") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        },
    )
}
