package com.multivpn.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import ir.uciran.uctor.ui.Green
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT

private val SstpMain = androidx.compose.ui.graphics.Color(0xFFEDEDF5)
private val SstpSub = androidx.compose.ui.graphics.Color(0xFFB4B4C8)

/** تب SSTP: اتصال دستی به سرور SSTP (مثلاً VPN Gate). */
@Composable
fun SstpScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var host by rememberSaveable { mutableStateOf(getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)) }
    var port by rememberSaveable { mutableStateOf(getIntPrefValue(OscPrefKey.SSL_PORT, prefs).toString()) }
    var user by rememberSaveable { mutableStateOf(getStringPrefValue(OscPrefKey.HOME_USERNAME, prefs)) }
    var pass by rememberSaveable { mutableStateOf(getStringPrefValue(OscPrefKey.HOME_PASSWORD, prefs)) }
    // سرورهای VPN Gate معمولاً گواهی عمومی ندارند؛ پیش‌فرض خاموش است
    var verify by rememberSaveable { mutableStateOf(prefs.getBoolean(OscPrefKey.SSL_DO_VERIFY.name, false)) }
    var note by rememberSaveable { mutableStateOf("") }
    var connected by remember { mutableStateOf(getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OscPrefKey.ROOT_STATE.name) {
                connected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            connectNow(context, host, port, user, pass, verify)
        } else {
            note = "اجازهٔ VPN داده نشد"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (connected) "SSTP متصل است" else "SSTP قطع است",
            color = if (connected) Green else SstpMain,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        OutlinedTextField(
            value = host, onValueChange = { host = it }, singleLine = true,
            label = { Text("آدرس سرور") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, singleLine = true,
            label = { Text("پورت (معمولاً 443)") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it }, singleLine = true,
            label = { Text("نام کاربری") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true,
            label = { Text("رمز") }, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = verify, onCheckedChange = { verify = it })
            Text("بررسی گواهی سرور (برای سرورهای عمومی معمولاً خاموش)", color = SstpSub, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (host.isBlank() || user.isBlank()) {
                        note = "آدرس و نام کاربری را وارد کنید"
                        return@Button
                    }
                    val prep = VpnService.prepare(context)
                    if (prep != null) permissionLauncher.launch(prep)
                    else connectNow(context, host, port, user, pass, verify)
                },
                enabled = !connected,
                modifier = Modifier.weight(1f),
            ) { Text("اتصال") }
            OutlinedButton(
                onClick = { sendSstp(context, ACTION_VPN_DISCONNECT) },
                enabled = connected,
                modifier = Modifier.weight(1f),
            ) { Text("قطع") }
        }
        if (note.isNotBlank()) {
            Text(note, color = SstpSub, fontSize = 12.sp)
        }
    }
}

private fun connectNow(ctx: Context, host: String, port: String, user: String, pass: String, verify: Boolean) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    setStringPrefValue(host.trim(), OscPrefKey.HOME_HOSTNAME, prefs)
    setIntPrefValue(port.toIntOrNull() ?: 443, OscPrefKey.SSL_PORT, prefs)
    setStringPrefValue(user.trim(), OscPrefKey.HOME_USERNAME, prefs)
    setStringPrefValue(pass, OscPrefKey.HOME_PASSWORD, prefs)
    setBooleanPrefValue(verify, OscPrefKey.SSL_DO_VERIFY, prefs)
    stopOthersForSstp(ctx)
    sendSstp(ctx, ACTION_VPN_CONNECT)
}
