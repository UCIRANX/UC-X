package com.multivpn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.multivpn.app.security.DeviceIdProvider
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import com.multivpn.app.security.TimeGuard
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Surface2
import kotlinx.coroutines.launch

/** پنجرهٔ فعال‌سازی؛ تا وقتی لایسنس معتبر نشود، برنامه قابل استفاده نیست. */
@Composable
fun LicenseGate(status: LicenseStatus, onActivated: (LicenseStatus) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember { DeviceIdProvider.getFormattedDeviceId(context) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val reason = when (status) {
        is LicenseStatus.Expired -> status.message
        is LicenseStatus.Invalid -> status.reason
        LicenseStatus.Missing -> "برای استفاده، کد لایسنس را وارد کنید."
        is LicenseStatus.Valid -> ""
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = Surface1,
        title = {
            Text(
                if (status is LicenseStatus.Expired) "مهلت تمام شد" else "فعال‌سازی UC X",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(reason, color = Color(0xFFB4B4C8), fontSize = 14.sp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Surface2, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("شناسه دستگاه", fontSize = 12.sp, color = Color(0xFFB4B4C8))
                        SelectionContainer {
                            Text(deviceId, fontWeight = FontWeight.Bold, fontSize = 18.sp, style = LtrStyle)
                        }
                    }
                    TextButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("DeviceID", deviceId))
                        Toast.makeText(context, "شناسه کپی شد", Toast.LENGTH_SHORT).show()
                    }) { Text("کپی") }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("کد لایسنس") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("کد را از ربات @uciranxbot دریافت کنید.", color = Color(0xFFB4B4C8), fontSize = 13.sp)
                TextButton(onClick = { openLicenseBot(context) }) { Text("ورود به ربات") }
                if (message.isNotBlank()) {
                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        TimeGuard.syncNetworkTime(context)
                        val r = LicenseManager.verifyAndSaveToken(context, input)
                        busy = false
                        when (r) {
                            is LicenseStatus.Valid -> {
                                input = ""
                                message = ""
                                onActivated(r)
                            }
                            is LicenseStatus.Expired -> message = r.message
                            is LicenseStatus.Invalid -> message = r.reason
                            LicenseStatus.Missing -> message = "کد را وارد کنید."
                        }
                    }
                },
            ) { Text("بررسی و فعال‌سازی") }
        },
    )
}
