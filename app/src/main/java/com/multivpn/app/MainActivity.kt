package com.multivpn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.multivpn.app.security.DeviceIdProvider
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import com.multivpn.app.security.TimeGuard
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SinglePageVpnScreen()
                }
            }
        }
    }
}

@Composable
fun SinglePageVpnScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val formattedDeviceId = remember { DeviceIdProvider.getFormattedDeviceId(context) }
    var licenseStatus by remember { mutableStateOf(LicenseManager.getCurrentLicenseStatus(context)) }
    var showLicenseDialog by remember { mutableStateOf(licenseStatus !is LicenseStatus.Valid) }
    var inputCode by remember { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf("") }

    var selectedCore by remember { mutableStateOf("Auto (Smart)") }
    var isConnected by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("آماده اتصال") }
    var expandCoreMenu by remember { mutableStateOf(false) }

    val cores = listOf(
        "Auto (Smart)",
        "Sing-box (Xray)",
        "AmneziaWG",
        "OpenVPN",
        "Psiphon",
        "Tor"
    )

    LaunchedEffect(Unit) {
        TimeGuard.syncNetworkTime(context)
        while (isActive) {
            val current = LicenseManager.getCurrentLicenseStatus(context)
            licenseStatus = current

            if (current !is LicenseStatus.Valid) {
                if (isConnected) {
                    isConnected = false
                    context.stopService(Intent(context, MultiCoreVpnService::class.java))
                    statusText = "قطع خودکار: مهلت ۵ روزه به پایان رسید!"
                }
                showLicenseDialog = true
            }
            delay(1000L)
        }
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = {
                if (licenseStatus is LicenseStatus.Valid) {
                    showLicenseDialog = false
                }
            },
            title = {
                Text(
                    text = if (licenseStatus is LicenseStatus.Expired) "پایان مهلت ۵ روزه!" else "فعال‌سازی برنامه (کد ۵ روزه)",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val reasonText = when (val s = licenseStatus) {
                        is LicenseStatus.Expired -> s.message
                        is LicenseStatus.Invalid -> s.reason
                        is LicenseStatus.Missing -> "برای استفاده از فیلترشکن، شناسه دستگاه زیر را به ربات تلگرام بفرستید و کد ۵ روزه دریافتی را وارد کنید."
                        is LicenseStatus.Valid -> "کد شما فعال است. می‌توانید کد ۵ روزه جدیدی جایگزین کنید."
                    }
                    Text(text = reasonText)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("شناسه دستگاه شما:")
                                Text(text = formattedDeviceId, fontWeight = FontWeight.Bold)
                            }
                            Button(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("DeviceID", formattedDeviceId))
                                Toast.makeText(context, "شناسه دستگاه کپی شد", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("کپی")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { inputCode = it },
                        label = { Text("کد دریافتی از ربات (MV2-...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (dialogMessage.isNotBlank()) {
                        Text(text = dialogMessage, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        TimeGuard.syncNetworkTime(context)
                        val result = LicenseManager.verifyAndSaveToken(context, inputCode)
                        licenseStatus = result
                        when (result) {
                            is LicenseStatus.Valid -> {
                                dialogMessage = ""
                                inputCode = ""
                                showLicenseDialog = false
                                statusText = "کد ۵ روزه تایید شد! آماده اتصال"
                            }
                            is LicenseStatus.Expired -> dialogMessage = result.message
                            is LicenseStatus.Invalid -> dialogMessage = result.reason
                            LicenseStatus.Missing -> dialogMessage = "لطفاً کد را وارد کنید."
                        }
                    }
                }) {
                    Text("بررسی و فعال‌سازی")
                }
            },
            dismissButton = {
                if (licenseStatus is LicenseStatus.Valid) {
                    TextButton(onClick = { showLicenseDialog = false }) {
                        Text("بستن")
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "شناسه گوشی: $formattedDeviceId", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showLicenseDialog = true }) {
                        Text("وارد کردن کد جدید")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                when (val s = licenseStatus) {
                    is LicenseStatus.Valid -> {
                        Text(text = "مهلت باقی‌مانده: ${LicenseManager.formatRemainingTime(s.remainingSeconds)}")
                    }
                    is LicenseStatus.Expired -> {
                        Text(text = "وضعیت: مهلت ۵ روزه تمام شده است!", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        Text(text = "وضعیت: قفل (کد ۵ روزه وارد نشده)", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val isLicenseValid = licenseStatus is LicenseStatus.Valid
            Button(
                onClick = {
                    val currentCheck = LicenseManager.getCurrentLicenseStatus(context)
                    if (currentCheck !is LicenseStatus.Valid) {
                        showLicenseDialog = true
                        return@Button
                    }
                    isConnected = !isConnected
                    if (isConnected) {
                        context.startService(Intent(context, MultiCoreVpnService::class.java))
                        statusText = "متصل به $selectedCore"
                    } else {
                        context.stopService(Intent(context, MultiCoreVpnService::class.java))
                        statusText = "اتصال قطع شد"
                    }
                },
                enabled = isLicenseValid,
                modifier = Modifier.size(160.dp)
            ) {
                Text(if (isConnected) "قطع اتصال" else if (isLicenseValid) "اتصال" else "قفل است")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = statusText)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expandCoreMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("هسته فعال: $selectedCore")
                }
                DropdownMenu(expanded = expandCoreMenu, onDismissRequest = { expandCoreMenu = false }) {
                    cores.forEach { core ->
                        DropdownMenuItem(
                            text = { Text(core) },
                            onClick = {
                                selectedCore = core
                                expandCoreMenu = false
                            }
                        )
                    }
                }
            }

            OutlinedButton(onClick = { statusText = "در حال تست پینگ کانفیگ‌ها (فاز ۳)..." }, modifier = Modifier.fillMaxWidth()) {
                Text("تست پینگ")
            }

            OutlinedButton(onClick = { statusText = "در حال بروزرسانی ساب‌لینک‌ها (فاز ۳)..." }, modifier = Modifier.fillMaxWidth()) {
                Text("بروزرسانی ساب‌لینک‌ها")
            }
        }
    }
}

class MultiCoreVpnService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startExpirationWatchdog()
        return START_STICKY
    }

    private fun startExpirationWatchdog() {
        serviceScope.coroutineContext.cancelChildren()
        serviceScope.launch {
            while (isActive) {
                val status = LicenseManager.getCurrentLicenseStatus(applicationContext)
                if (status !is LicenseStatus.Valid) {
                    sendExpirationNotification()
                    stopSelf()
                    break
                }
                delay(5000L)
            }
        }
    }

    private fun sendExpirationNotification() {
        val channelId = "vpn_expiry_alert"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "هشدار انقضای کد ۵ روزه", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("مهلت ۵ روزه به پایان رسید")
            .setContentText("اتصال فیلترشکن قطع شد. لطفاً کد جدید را از ربات تلگرام دریافت و وارد کنید.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        serviceScope.cancel()
        super.onRevoke()
        stopSelf()
    }
}
