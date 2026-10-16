package com.multivpn.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import com.multivpn.app.security.TimeGuard
import ir.uciran.uctor.ui.Surface1
import kotlinx.coroutines.launch

private const val LICENSE_BOT_URL = "https://t.me/uciranxbot"
private val DangerRed = Color(0xFFFF6B6B)

/** باز کردن ربات صدور کد. */
fun openLicenseBot(context: Context) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_BOT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** مدیریت لایسنس: نمایش مهلت، ورود کد جدید و حذف کد (با تأیید). */
@Composable
fun LicenseManageDialog(status: LicenseStatus, onDismiss: () -> Unit, onChanged: (LicenseStatus) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showInput by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val remainingText = (status as? LicenseStatus.Valid)
        ?.let { LicenseManager.formatRemainingTime(it.remainingSeconds) } ?: "—"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("مدیریت لایسنس", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("مهلت باقی‌مانده: $remainingText", fontSize = 14.sp)
                if (showInput) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("کد لایسنس") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "کد را از ربات @uciranxbot دریافت کنید.",
                        color = Color(0xFFB4B4C8),
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = { openLicenseBot(context) }) { Text("ورود به ربات") }
                    if (message.isNotBlank()) {
                        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (showInput) {
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
                                    onChanged(r)
                                    onDismiss()
                                }
                                is LicenseStatus.Expired -> message = r.message
                                is LicenseStatus.Invalid -> message = r.reason
                                LicenseStatus.Missing -> message = "کد را وارد کنید."
                            }
                        }
                    },
                ) { Text("ثبت کد") }
            } else {
                Button(onClick = { showInput = true }) { Text("ورود کد جدید") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showDeleteConfirm = true }) { Text("حذف کد", color = DangerRed) }
                TextButton(onClick = onDismiss) { Text("بستن") }
            }
        },
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Surface1,
            title = { Text("آیا مطمئن هستید؟", fontWeight = FontWeight.Bold) },
            text = { Text("کد لایسنس فعلی از این گوشی حذف می‌شود و برای استفاده دوباره باید کد وارد کنید.") },
            confirmButton = {
                TextButton(onClick = {
                    LicenseManager.clearSavedToken(context)
                    showDeleteConfirm = false
                    onChanged(LicenseManager.getCurrentLicenseStatus(context))
                    onDismiss()
                }) { Text("بله، حذف کن", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("انصراف") }
            },
        )
    }
}
