package com.multivpn.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.multivpn.app.core.CoreConfigGenerator
import com.multivpn.app.core.CoreEngineRunner
import com.multivpn.app.security.DeviceIdProvider
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import com.multivpn.app.security.TimeGuard
import com.multivpn.app.sub.PingTester
import com.multivpn.app.sub.SubManager
import com.multivpn.app.sub.VpnConfig
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

    var configs by remember { mutableStateOf(SubManager.loadCachedOrSampleConfigs(context)) }
    var selectedCore by remember { mutableStateOf("Auto (Smart)") }
    var selectedConfig by remember { mutableStateOf<VpnConfig?>(configs.firstOrNull()) }

    var isConnected by remember { mutableStateOf(CoreEngineRunner.isCoreRunning()) }
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("آماده اتصال (${configs.size} کانفیگ آماده)") }
    var expandCoreMenu by remember { mutableStateOf(false) }

    val cores = listOf(
        "Auto (Smart)",
        "Sing-box (Xray)",
        "AmneziaWG",
        "OpenVPN",
        "Psiphon",
        "Tor"
    )

    val filteredConfigs = remember(configs, selectedCore) {
        if (selectedCore == "Auto (Smart)") configs
        else configs.filter { it.coreType == selectedCore }
    }

    LaunchedEffect(filteredConfigs) {
        if (selectedConfig == null || !filteredConfigs.any { it.id == selectedConfig?.id }) {
            selectedConfig = filteredConfigs.firstOrNull()
        }
    }

    fun connectToSelectedConfig() {
        val best = selectedConfig ?: filteredConfigs.firstOrNull()
        if (best == null) {
            statusText = "هیچ کانفیگی برای اتصال وجود ندارد!"
            return
        }
        scope.launch {
            isBusy = true
            statusText = "در حال روشن کردن هسته واقعی برای ${best.name}..."
            val coreStarted = CoreEngineRunner.startCore(context, best)
            if (coreStarted) {
                val serviceIntent = Intent(context, MultiCoreVpnService::class.java).apply {
                    putExtra("CORE_NAME", best.coreType)
                    putExtra("SERVER_NAME", best.name)
                    putExtra("SERVER_PING", best.pingMs)
                }
                context.startService(serviceIntent)
                isConnected = true
                statusText = "متصل شد (هسته فعال روی پورت ${CoreConfigGenerator.LOCAL_SOCKS_PORT})"
            } else {
                isConnected = false
                statusText = CoreEngineRunner.lastErrorLog.ifBlank { "خطا در راه‌اندازی هسته!" }
            }
            isBusy = false
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            connectToSelectedConfig()
        } else {
            statusText = "مجوز ساخت تونل VPN داده نشد!"
        }
    }

    LaunchedEffect(Unit) {
        TimeGuard.syncNetworkTime(context)
        while (isActive) {
            val current = LicenseManager.getCurrentLicenseStatus(context)
            licenseStatus = current

            if (current !is LicenseStatus.Valid) {
                if (isConnected) {
                    isConnected = false
                    CoreEngineRunner.stopCore()
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "شناسه گوشی: $formattedDeviceId", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showLicenseDialog = true }) {
                        Text("کد لایسنس")
                    }
                }
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
                    if (isConnected) {
                        isConnected = false
                        CoreEngineRunner.stopCore()
                        context.stopService(Intent(context, MultiCoreVpnService::class.java))
                        statusText = "اتصال و هسته کامل قطع شد"
                    } else {
                        val prepareIntent = VpnService.prepare(context)
                        if (prepareIntent != null) {
                            vpnPermissionLauncher.launch(prepareIntent)
                        } else {
                            connectToSelectedConfig()
                        }
                    }
                },
                enabled = isLicenseValid && !isBusy,
                modifier = Modifier.size(135.dp)
            ) {
                Text(if (isConnected) "قطع اتصال" else if (isLicenseValid) "اتصال" else "قفل است")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = statusText, fontWeight = FontWeight.Medium)
            selectedConfig?.let { best ->
                val pingStr = when {
                    best.pingMs > 0 -> "${best.pingMs} ms"
                    best.pingMs == -2L -> "Timeout"
                    else -> "تست نشده"
                }
                Text(text = "کانفیگ انتخابی: ${best.name} ($pingStr)")
            }
            if (isConnected) {
                TextButton(
                    onClick = {
                        scope.launch {
                            statusText = "در حال تست اینترنت واقعی از داخل تونل..."
                            val delayMs = CoreEngineRunner.measureRealTunnelDelay()
                            statusText = if (delayMs > 0) {
                                "✅ تونل واقعی متصل است! (پینگ خروجی: $delayMs ms)"
                            } else {
                                "⚠️ هسته روشن است اما این کانفیگ اینترنت ندارد (کانفیگ دیگری انتخاب کنید)"
                            }
                        }
                    }
                ) {
                    Text("تست اینترنت واقعی تونل")
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(filteredConfigs) { cfg ->
                    val pingLabel = when {
                        cfg.pingMs > 0 -> "${cfg.pingMs} ms"
                        cfg.pingMs == -2L -> "خطا"
                        else -> "---"
                    }
                    val isSelected = selectedConfig?.id == cfg.id
                    TextButton(
                        onClick = {
                            selectedConfig = cfg
                            if (isConnected) {
                                connectToSelectedConfig()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = (if (isSelected) "✅ " else "") + "[${cfg.coreType}] ${cfg.name}",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(text = pingLabel)
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expandCoreMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("هسته فعال: $selectedCore (${filteredConfigs.size} کانفیگ)")
                }
                DropdownMenu(
                    expanded = expandCoreMenu,
                    onDismissRequest = { expandCoreMenu = false }
                ) {
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

            OutlinedButton(
                onClick = {
                    scope.launch {
                        isBusy = true
                        statusText = "در حال تست پینگ موازی ${configs.size} کانفیگ..."
                        val tested = PingTester.pingAllConfigs(configs)
                        configs = tested
                        val bestValid = tested.firstOrNull {
                            it.pingMs > 0 && (selectedCore == "Auto (Smart)" || it.coreType == selectedCore)
                        }
                        if (bestValid != null) {
                            selectedConfig = bestValid
                            statusText = "سریع‌ترین کانفیگ: ${bestValid.name} (${bestValid.pingMs} ms)"
                        } else {
                            statusText = "تست پینگ تمام شد."
                        }
                        isBusy = false
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("تست پینگ")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        isBusy = true
                        statusText = "دریافت ساب‌لینک‌ها با DoH..."
                        try {
                            val fetched = SubManager.fetchAndParseSub(context)
                            configs = fetched
                            selectedConfig = fetched.firstOrNull()
                            statusText = "بروزرسانی موفق: ${fetched.size} کانفیگ دریافت شد!"
                        } catch (e: Exception) {
                            statusText = "خطا در آپدیت ساب‌لینک: ${e.message}"
                        }
                        isBusy = false
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("بروزرسانی ساب‌لینک‌ها")
            }
        }
    }
}

class MultiCoreVpnService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coreName = intent?.getStringExtra("CORE_NAME") ?: "Auto (Smart)"
        val serverName = intent?.getStringExtra("SERVER_NAME") ?: "Server"
        val serverPing = intent?.getLongExtra("SERVER_PING", -1L) ?: -1L

        startVpnForegroundNotification(coreName, serverName, serverPing)
        establishTunnel(serverName)
        startExpirationWatchdog()
        return START_STICKY
    }

    private fun establishTunnel(serverName: String) {
        try {
            vpnInterface?.close()
            val builder = Builder()
                .setSession("MultiCoreVPN - $serverName")
                .setMtu(1500)
                .addAddress("10.10.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            // جلوگیری از لوپ شدن ترافیک خود هسته Xray داخل تونل VPN
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            // تنظیم پروکسی سطح سیستم روی پورت لوکال هسته Xray (اندروید ۱۰ به بالا)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy("127.0.0.1", CoreConfigGenerator.LOCAL_HTTP_PORT)
                )
            }

            vpnInterface = builder.establish()
        } catch (_: Exception) {
        }
    }

    private fun startVpnForegroundNotification(coreName: String, serverName: String, ping: Long) {
        val channelId = "vpn_active_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "وضعیت اتصال فیلترشکن", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pingText = if (ping > 0) " (${ping}ms)" else ""
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("متصل به $coreName")
            .setContentText("سرور: $serverName$pingText")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private fun startExpirationWatchdog() {
        serviceScope.coroutineContext.cancelChildren()
        serviceScope.launch {
            while (isActive) {
                val status = LicenseManager.getCurrentLicenseStatus(applicationContext)
                if (status !is LicenseStatus.Valid) {
                    sendExpirationNotification()
                    stopTunnel()
                    stopSelf()
                    break
                }
                delay(5000L)
            }
        }
    }

    private fun stopTunnel() {
        try {
            CoreEngineRunner.stopCore()
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {
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
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        serviceScope.cancel()
        super.onRevoke()
        stopSelf()
    }
}
