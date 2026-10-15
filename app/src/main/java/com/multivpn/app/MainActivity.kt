package com.multivpn.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.multivpn.app.core.TorEngineRunner
import com.multivpn.app.core.Tun2SocksBridge
import com.multivpn.app.security.DeviceIdProvider
import com.multivpn.app.security.LicenseManager
import com.multivpn.app.security.LicenseStatus
import com.multivpn.app.security.TimeGuard
import com.multivpn.app.sub.PingTester
import com.multivpn.app.sub.SubManager
import com.multivpn.app.sub.VpnConfig
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

    var configs by remember { mutableStateOf(SubManager.loadCachedConfigs(context)) }
    var selectedCore by remember { mutableStateOf("Sing-box (Xray)") }
    var selectedConfig by remember { mutableStateOf<VpnConfig?>(configs.firstOrNull()) }

    // تنظیمات پل و کشور (مخصوص Tor و مسیریابی)
    var selectedBridge by remember { mutableStateOf(TorEngineRunner.bridgeOptions.first()) }
    var selectedCountry by remember { mutableStateOf(TorEngineRunner.countryOptions.first()) }
    var expandCoreMenu by remember { mutableStateOf(false) }
    var expandBridgeMenu by remember { mutableStateOf(false) }
    var expandCountryMenu by remember { mutableStateOf(false) }

    var isConnected by remember { mutableStateOf(CoreEngineRunner.isCoreRunning() || TorEngineRunner.isTorRunning()) }
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf(
            if (configs.isEmpty()) "ابتدا «بروزرسانی ساب‌لینک‌ها» را بزنید یا هسته Tor را انتخاب کنید"
            else "آماده اتصال (${configs.size} کانفیگ)"
        )
    }

    val cores = listOf("Sing-box (Xray)", "Tor", "AmneziaWG", "OpenVPN", "Psiphon")
    val filteredConfigs = remember(configs, selectedCore) {
        configs.filter { it.coreType == selectedCore }
    }

    LaunchedEffect(filteredConfigs) {
        if (selectedConfig == null || !filteredConfigs.any { it.id == selectedConfig?.id }) {
            selectedConfig = filteredConfigs.firstOrNull()
        }
    }

    fun stopAllConnections() {
        isConnected = false
        CoreEngineRunner.stopCore()
        TorEngineRunner.stopTor()
        context.stopService(Intent(context, MultiCoreVpnService::class.java))
    }

    fun startActiveConnection() {
        scope.launch {
            isBusy = true
            if (selectedCore == "Tor") {
                CoreEngineRunner.stopCore()
                val ok = TorEngineRunner.startTor(
                    context = context,
                    bridgeType = selectedBridge.id,
                    countryCode = selectedCountry.code,
                    onStatus = { msg -> statusText = msg }
                )
                if (ok) {
                    val srvIntent = Intent(context, MultiCoreVpnService::class.java).apply {
                        putExtra("CORE_NAME", "Tor (${selectedBridge.id})")
                        putExtra("SERVER_NAME", "${selectedCountry.flag} ${selectedCountry.name}")
                        putExtra("SOCKS_PORT", TorEngineRunner.TOR_SOCKS_PORT)
                        putExtra("SERVER_PING", -1L)
                    }
                    context.startService(srvIntent)
                    isConnected = true
                    statusText = "✅ متصل به Tor (پل: ${selectedBridge.id} | کشور: ${selectedCountry.flag} ${selectedCountry.name})"
                } else {
                    isConnected = false
                    statusText = TorEngineRunner.lastError.ifBlank { "خطا در اتصال به شبکه Tor" }
                }
            } else {
                TorEngineRunner.stopTor()
                val target = selectedConfig ?: filteredConfigs.firstOrNull()
                if (target == null) {
                    statusText = "کانفیگی وجود ندارد! ابتدا ساب‌لینک را بروزرسانی کنید."
                    isBusy = false
                    return@launch
                }
                statusText = "در حال اتصال و تست Real HTTP روی ${target.name}..."
                val coreStarted = CoreEngineRunner.startCore(context, target)
                if (coreStarted) {
                    val realPing = CoreEngineRunner.measureRealTunnelDelay()
                    if (realPing > 0) {
                        configs = configs.map { if (it.id == target.id) it.copy(pingMs = realPing) else it }
                        selectedConfig = target.copy(pingMs = realPing)

                        val srvIntent = Intent(context, MultiCoreVpnService::class.java).apply {
                            putExtra("CORE_NAME", target.coreType)
                            putExtra("SERVER_NAME", target.name)
                            putExtra("SOCKS_PORT", CoreConfigGenerator.LOCAL_SOCKS_PORT)
                            putExtra("SERVER_PING", realPing)
                        }
                        context.startService(srvIntent)
                        isConnected = true
                        statusText = "✅ متصل با Full TUN (پینگ Real HTTP: $realPing ms)"
                    } else {
                        configs = configs.map { if (it.id == target.id) it.copy(pingMs = -2L) else it }
                        CoreEngineRunner.stopCore()
                        isConnected = false
                        statusText = "❌ این کانفیگ اینترنت واقعی ندارد؛ کانفیگ بعدی را بزنید"
                    }
                } else {
                    configs = configs.map { if (it.id == target.id) it.copy(pingMs = -2L) else it }
                    isConnected = false
                    statusText = CoreEngineRunner.lastErrorLog.ifBlank { "خطا در راه‌اندازی کانفیگ!" }
                }
            }
            isBusy = false
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) startActiveConnection()
        else statusText = "مجوز ساخت تونل VPN داده نشد!"
    }

    LaunchedEffect(Unit) {
        TimeGuard.syncNetworkTime(context)
        while (isActive) {
            val current = LicenseManager.getCurrentLicenseStatus(context)
            licenseStatus = current
            if (current !is LicenseStatus.Valid) {
                if (isConnected) {
                    stopAllConnections()
                    statusText = "قطع خودکار: مهلت ۵ روزه به پایان رسید!"
                }
                showLicenseDialog = true
            }
            delay(1000L)
        }
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { if (licenseStatus is LicenseStatus.Valid) showLicenseDialog = false },
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
                        is LicenseStatus.Missing -> "شناسه دستگاه زیر را به ربات تلگرام بفرستید و کد ۵ روزه را وارد کنید."
                        is LicenseStatus.Valid -> "کد شما فعال است. می‌توانید کد جدیدی جایگزین کنید."
                    }
                    Text(text = reasonText)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("شناسه دستگاه شما:")
                                Text(text = formattedDeviceId, fontWeight = FontWeight.Bold)
                            }
                            Button(onClick = {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("DeviceID", formattedDeviceId))
                                Toast.makeText(context, "شناسه کپی شد", Toast.LENGTH_SHORT).show()
                            }) { Text("کپی") }
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
                }) { Text("بررسی و فعال‌سازی") }
            },
            dismissButton = {
                if (licenseStatus is LicenseStatus.Valid) {
                    TextButton(onClick = { showLicenseDialog = false }) { Text("بستن") }
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "شناسه گوشی: $formattedDeviceId", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showLicenseDialog = true }) { Text("کد لایسنس") }
                }
                when (val s = licenseStatus) {
                    is LicenseStatus.Valid -> Text("مهلت باقی‌مانده: ${LicenseManager.formatRemainingTime(s.remainingSeconds)}")
                    is LicenseStatus.Expired -> Text("وضعیت: مهلت ۵ روزه تمام شده است!", color = MaterialTheme.colorScheme.error)
                    else -> Text("وضعیت: قفل (کد ۵ روزه وارد نشده)", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val isLicenseValid = licenseStatus is LicenseStatus.Valid
            Button(
                onClick = {
                    if (LicenseManager.getCurrentLicenseStatus(context) !is LicenseStatus.Valid) {
                        showLicenseDialog = true
                        return@Button
                    }
                    if (isConnected) {
                        stopAllConnections()
                        statusText = "اتصال قطع شد"
                    } else {
                        val prep = VpnService.prepare(context)
                        if (prep != null) vpnPermissionLauncher.launch(prep)
                        else startActiveConnection()
                    }
                },
                enabled = isLicenseValid && !isBusy,
                modifier = Modifier.size(125.dp)
            ) {
                Text(if (isConnected) "قطع اتصال" else if (isLicenseValid) "اتصال" else "قفل است")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = statusText, fontWeight = FontWeight.Medium)
            if (selectedCore != "Tor") {
                selectedConfig?.let { best ->
                    val pingStr = when {
                        best.pingMs > 0 -> "${best.pingMs} ms"
                        best.pingMs == -2L -> "بدون اینترنت"
                        else -> "تست نشده"
                    }
                    Text(text = "کانفیگ: [${best.protocol.uppercase()}] ${best.name} ($pingStr)")
                }
            } else {
                Text(text = "تنظیمات Tor: ${selectedBridge.label} | ${selectedCountry.flag} ${selectedCountry.name}")
            }
        }

        // ردیف انتخاب نوع پل و انتخاب کشور (با حالت خودکار)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { expandBridgeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "پل: ${selectedBridge.id.uppercase()}", maxLines = 1)
                }
                DropdownMenu(expanded = expandBridgeMenu, onDismissRequest = { expandBridgeMenu = false }) {
                    TorEngineRunner.bridgeOptions.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b.label) },
                            onClick = {
                                selectedBridge = b
                                expandBridgeMenu = false
                            }
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { expandCountryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "کشور: ${selectedCountry.flag} ${selectedCountry.name}", maxLines = 1)
                }
                DropdownMenu(expanded = expandCountryMenu, onDismissRequest = { expandCountryMenu = false }) {
                    TorEngineRunner.countryOptions.forEach { c ->
                        DropdownMenuItem(
                            text = { Text("${c.flag} ${c.name}") },
                            onClick = {
                                selectedCountry = c
                                expandCountryMenu = false
                            }
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            if (selectedCore == "Tor") {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    Text("هسته Tor فعال است.\nنوع پل (${selectedBridge.label}) و کشور خروجی (${selectedCountry.flag} ${selectedCountry.name}) را از بالا انتخاب کنید و دکمه «اتصال» را بزنید.")
                }
            } else if (filteredConfigs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لیست خالی است؛ دکمه «بروزرسانی ساب‌لینک‌ها» را بزنید")
                }
            } else {
                LazyColumn(modifier = Modifier.padding(6.dp)) {
                    items(filteredConfigs) { cfg ->
                        val pingLabel = when {
                            cfg.pingMs > 0 -> "${cfg.pingMs} ms"
                            cfg.pingMs == -2L -> "بدون اینترنت"
                            else -> "---"
                        }
                        val isSel = selectedConfig?.id == cfg.id
                        TextButton(
                            onClick = {
                                selectedConfig = cfg
                                if (isConnected) startActiveConnection()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = (if (isSel) "✅ " else "") + "[${cfg.protocol.uppercase()}] ${cfg.name}",
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = pingLabel)
                            }
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expandCoreMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    val countStr = if (selectedCore == "Tor") "پل + کشور" else "${filteredConfigs.size} کانفیگ"
                    Text("هسته فعال: $selectedCore ($countStr)")
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

            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (selectedCore == "Tor") {
                            isBusy = true
                            statusText = "در حال دریافت پل‌های جدید Tor از مخازن آنلاین..."
                            val count = TorEngineRunner.fetchFreshBridges()
                            statusText = if (count > 0) "✅ $count پل جدید Tor دریافت شد!" else "استفاده از پل‌های داخلی Tor"
                            isBusy = false
                            return@launch
                        }
                        if (filteredConfigs.isEmpty()) {
                            statusText = "ابتدا ساب‌لینک را بروزرسانی کنید!"
                            return@launch
                        }
                        isBusy = true
                        if (isConnected) stopAllConnections()
                        val tested = PingTester.pingAllConfigsRealHttp(context, configs) { msg -> statusText = msg }
                        configs = tested
                        val working = tested.count { it.pingMs > 0 && it.coreType == selectedCore }
                        val best = tested.firstOrNull { it.pingMs > 0 && it.coreType == selectedCore }
                        if (best != null) {
                            selectedConfig = best
                            statusText = "✅ $working کانفیگ دارای اینترنت واقعی پیدا شد! بهترین: ${best.name} (${best.pingMs} ms)"
                        } else {
                            statusText = "❌ هیچ کانفیگ دارای اینترنت واقعی در این لحظه پاسخ نداد."
                        }
                        isBusy = false
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedCore == "Tor") "بروزرسانی پل‌های آنلاین Tor" else "تست پینگ واقعی کل ساب‌لینک (Real HTTP)")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        isBusy = true
                        statusText = "در حال دریافت کانفیگ‌های ساب‌لینک..."
                        try {
                            val fetched = SubManager.fetchAndParseSub(context)
                            configs = fetched
                            selectedConfig = fetched.firstOrNull { it.coreType == selectedCore }
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
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coreName = intent?.getStringExtra("CORE_NAME") ?: "Sing-box (Xray)"
        val serverName = intent?.getStringExtra("SERVER_NAME") ?: "Server"
        val socksPort = intent?.getIntExtra("SOCKS_PORT", CoreConfigGenerator.LOCAL_SOCKS_PORT) ?: CoreConfigGenerator.LOCAL_SOCKS_PORT
        val serverPing = intent?.getLongExtra("SERVER_PING", -1L) ?: -1L

        startVpnForegroundNotification(coreName, serverName, serverPing)
        serviceScope.launch {
            establishFullTunWithMapDns(serverName, socksPort)
        }
        startExpirationWatchdog()
        return START_STICKY
    }

    // پیاده‌سازی دقیق Full TUN + MapDNS مطابق پروژه UC Tor برای باز شدن تلگرام و کل اپ‌ها
    private fun establishFullTunWithMapDns(serverName: String, socksPort: Int) {
        try {
            stopTunnelOnly()
            val builder = Builder()
                .setSession("MultiCoreVPN - $serverName")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fc00::1", 128)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            val pfd = builder.establish() ?: return
            vpnInterface = pfd

            val hevConf = File(filesDir, "hev-tun.conf")
            val hevLog = File(filesDir, "hev.log").apply { delete() }
            hevConf.writeText(
                listOf(
                    "tunnel:",
                    "  mtu: 1500",
                    "  ipv4: 198.18.0.1",
                    "  ipv6: 'fc00::1'",
                    "socks5:",
                    "  address: 127.0.0.1",
                    "  port: $socksPort",
                    "mapdns:",
                    "  address: 198.18.0.2",
                    "  port: 53",
                    "  network: 100.64.0.0",
                    "  netmask: 255.192.0.0",
                    "  cache-size: 10000",
                    "misc:",
                    "  task-stack-size: 81920",
                    "  connect-timeout: 15000",
                    "  read-write-timeout: 300000",
                    "  log-file: '${hevLog.absolutePath}'",
                    "  log-level: warn"
                ).joinToString("\n") + "\n",
                Charsets.UTF_8
            )

            Tun2SocksBridge.start(hevConf, pfd.fd)
        } catch (_: Throwable) {}
    }

    private fun startVpnForegroundNotification(coreName: String, serverName: String, ping: Long) {
        val channelId = "vpn_active_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "وضعیت اتصال فیلترشکن", NotificationManager.IMPORTANCE_LOW))
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pingText = if (ping > 0) " (${ping}ms)" else ""
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("متصل به $coreName (Full TUN)")
            .setContentText("سرور: $serverName$pingText")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private fun startExpirationWatchdog() {
        serviceScope.launch {
            while (isActive) {
                if (LicenseManager.getCurrentLicenseStatus(applicationContext) !is LicenseStatus.Valid) {
                    sendExpirationNotification()
                    stopTunnel()
                    stopSelf()
                    break
                }
                delay(5000L)
            }
        }
    }

    private fun stopTunnelOnly() {
        runCatching { Tun2SocksBridge.stop() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun stopTunnel() {
        runCatching { CoreEngineRunner.stopCore() }
        runCatching { TorEngineRunner.stopTor() }
        stopTunnelOnly()
    }

    private fun sendExpirationNotification() {
        val channelId = "vpn_expiry_alert"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "هشدار انقضای کد ۵ روزه", NotificationManager.IMPORTANCE_HIGH))
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
