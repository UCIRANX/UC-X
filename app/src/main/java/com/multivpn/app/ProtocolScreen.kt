package com.multivpn.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.evokelektrique.tunnelforge.TunnelVpnService
import ir.uciran.uctor.ui.Cyan
import ir.uciran.uctor.ui.Green
import ir.uciran.uctor.ui.Surface1
import ir.uciran.uctor.ui.Violet
import com.multivpn.app.log.AppLogger
import com.multivpn.app.oc.OcParams
import com.multivpn.app.oc.OcVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PTextMain = Color(0xFFEDEDF5)
private val PTextSub = Color(0xFFB4B4C8)
private val PDanger = Color(0xFFFF6B6B)
private val PIkev2 = Color(0xFF7C5CFF)
private val PL2tp = Color(0xFF00B4D8)
private val POc = Color(0xFFFF8A3D)

private fun typeColor(t: VpnType): Color = when (t) {
    VpnType.IKEV2 -> PIkev2
    VpnType.L2TP -> PL2tp
    VpnType.OPENCONNECT -> POc
}

private data class Field(val key: String, val label: String, val secret: Boolean = false, val optional: Boolean = false)

private fun fieldsFor(t: VpnType): List<Field> = when (t) {
    VpnType.IKEV2 -> listOf(
        Field("name", "نام پروفایل", optional = true),
        Field("server", "سرور"),
        Field("username", "نام کاربری"),
        Field("password", "رمز", secret = true, optional = true),
        Field("remoteId", "Server identity", optional = true),
        Field("psk", "PSK (اگر پر شود، به جای EAP)", secret = true, optional = true),
    )
    VpnType.L2TP -> listOf(
        Field("name", "نام پروفایل", optional = true),
        Field("server", "سرور"),
        Field("psk", "IPSec pre-shared key (PSK)", secret = true),
        Field("username", "User ID"),
        Field("password", "رمز", secret = true, optional = true),
    )
    VpnType.OPENCONNECT -> listOf(
        Field("name", "نام", optional = true),
        Field("server", "سرور (host یا URL)"),
        Field("port", "پورت", optional = true),
        Field("username", "نام کاربری"),
        Field("password", "رمز", secret = true),
        Field("authGroup", "Auth group / portal", optional = true),
        Field("certPin", "Server cert pin (pin-sha256:...)", optional = true),
    )
}

private fun initialFor(s: ProtoServer?, t: VpnType): Map<String, String> {
    if (s == null) return emptyMap()
    return when (t) {
        VpnType.IKEV2 -> mapOf("name" to s.name, "server" to s.host, "username" to s.user, "password" to s.password, "remoteId" to s.remoteId, "psk" to s.psk)
        VpnType.L2TP -> mapOf("name" to s.name, "server" to s.host, "psk" to s.psk, "username" to s.user, "password" to s.password)
        VpnType.OPENCONNECT -> mapOf("name" to s.name, "server" to s.host, "port" to (if (s.port > 0) s.port.toString() else ""), "username" to s.user, "password" to s.password, "authGroup" to s.authGroup, "certPin" to s.certPin)
    }
}

private fun ProtoServer.toIkev2() = Ikev2Server(name, host, user, password, remoteId, psk)

/** شروع L2TP یا OpenConnect؛ VPNهای دیگر قبلاً قطع می‌شوند. */
private fun startServer(context: Context, s: ProtoServer) {
    stopOthersForIkev2(context)
    stopIkev2IfActive(context)
    AppLogger.init(context)
    // خطای قدیمی پروتکل‌های دیگر را پاک می‌کنیم تا با اتصال جدید اشتباه نشان داده نشود
    TunnelVpnService.dismissError()
    OcVpnService.dismissError()
    when (s.type) {
        VpnType.L2TP -> {
            AppLogger.log("UI", "شروع L2TP به ${s.host}")
            context.startService(TunnelVpnService.connectIntent(context, s.host, s.user, s.password, s.psk))
        }
        VpnType.OPENCONNECT -> {
            AppLogger.log("UI", "شروع OpenConnect به ${s.host}:${s.port}")
            context.startService(
                OcVpnService.connectIntent(context, OcParams(s.host, s.port, s.user, s.password, s.authGroup, s.certPin)),
            )
        }
        VpnType.IKEV2 -> Unit
    }
}

/** بخش «پروتکل‌ها»: دو لیست (دستی و ساب‌لینک)، اتصال دایره‌ای و لاگ یکجا. */
@Composable
fun ProtocolScreen() {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val ikev2Supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    AppLogger.init(context)

    var listTab by rememberSaveable { mutableStateOf(0) } // 0 = ساب‌لینک (پیش‌فرض)، 1 = دستی
    var manualList by remember { mutableStateOf(ProtoLists.manual(context)) }
    var subList by remember { mutableStateOf(ProtoLists.loadSub(context)) }
    var favMan by remember { mutableStateOf(ProtoLists.favManKeys(context)) }
    var favSub by remember { mutableStateOf(ProtoLists.favSub(context)) }
    var selectedKey by rememberSaveable { mutableStateOf(ProtoLists.selected(context)) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showChooser by rememberSaveable { mutableStateOf(false) }
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var dialogType by rememberSaveable { mutableStateOf(VpnType.IKEV2) }
    var editing by remember { mutableStateOf<ProtoServer?>(null) }
    var updating by remember { mutableStateOf(false) }

    // ───── وضعیت واقعی هر پروتکل ─────
    var ikev2Connected by remember { mutableStateOf(false) }
    DisposableEffect(ikev2Supported) {
        if (!ikev2Supported) {
            onDispose { }
        } else {
            val cb = watchVpn(context) { ikev2Connected = it }
            onDispose { unwatchVpn(context, cb) }
        }
    }
    val l2tpState by TunnelVpnService.state.collectAsState()
    val ocState by OcVpnService.state.collectAsState()
    val l2tpConnected = l2tpState.first == TunnelVpnService.STATE_CONNECTED
    val l2tpBusy = l2tpState.first == TunnelVpnService.STATE_CONNECTING
    val ocConnected = ocState.first == OcVpnService.STATE_CONNECTED
    val ocBusy = ocState.first == OcVpnService.STATE_CONNECTING
    val connectedAny = ikev2Connected || l2tpConnected || ocConnected
    val ikev2Status by Ikev2Events.status.collectAsState()
    val ikev2Connecting = ikev2Status.first == Ikev2Events.CONNECTING
    val ikev2Failed = ikev2Status.first == Ikev2Events.FAILED
    val busyAny = l2tpBusy || ocBusy || ikev2Connecting
    val anyActive = connectedAny || busyAny

    // پیام‌های موقت (مثل «قطع شد») با تغییر واقعی وضعیت پاک می‌شوند
    LaunchedEffect(ikev2Connected, l2tpState.first, ocState.first) { note = "" }

    // بار اول که هنوز ساب‌لینکی گرفته نشده، بروزرسانی خودکار
    LaunchedEffect(Unit) {
        if (ProtoLists.lastUpdated(context) == 0L || subList.any { it.source.isBlank() }) {
            note = ProtoLists.refreshSub(context)
            subList = ProtoLists.loadSub(context)
            favSub = ProtoLists.favSub(context)
        }
    }

    fun findServer(key: String?): ProtoServer? =
        (manualList + subList + favSub).firstOrNull { it.key == key }

    fun reloadManual() {
        manualList = ProtoLists.manual(context)
        favMan = ProtoLists.favManKeys(context)
    }

    fun setSelected(key: String?) {
        selectedKey = key
        ProtoLists.setSelected(context, key)
    }

    val ikev2Launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val s = findServer(pendingKey)
        AppLogger.log("IKEv2", "نتیجهٔ تأیید سیستم: ${if (res.resultCode == Activity.RESULT_OK) "تأیید شد" else "رد شد یا لغو شد"}")
        note = if (res.resultCode == Activity.RESULT_OK && s != null) {
            startIkev2(context, s.toIkev2()) ?: ""
        } else {
            "اجازهٔ VPN داده نشد"
        }
    }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val s = findServer(pendingKey)
        if (res.resultCode == Activity.RESULT_OK && s != null) {
            startServer(context, s)
        } else {
            note = "اجازهٔ VPN داده نشد"
        }
    }

    fun connectSelected() {
        val s = findServer(selectedKey)
        if (s == null) {
            note = "ابتدا یک سرور انتخاب کنید"
            return
        }
        pendingKey = s.key
        AppLogger.log("UI", "درخواست اتصال به ${s.label} (${s.type.label})")
        if (s.type == VpnType.IKEV2) {
            if (!ikev2Supported) {
                note = "IKEv2 فقط روی اندروید ۱۲ به بالا کار می‌کند"
                return
            }
            var failure: String? = null
            val intent = try {
                ikev2ConsentIntent(context, s.toIkev2())
            } catch (e: Exception) {
                failure = e.message ?: "خطای ناشناخته"
                null
            }
            when {
                failure != null -> note = "پروفایل ساخته نشد: $failure"
                intent != null -> ikev2Launcher.launch(intent)
                else -> note = startIkev2(context, s.toIkev2()) ?: ""
            }
        } else {
            val consent = VpnService.prepare(context)
            if (consent != null) vpnLauncher.launch(consent) else startServer(context, s)
        }
    }

    fun disconnectAll() {
        if (ikev2Connected || ikev2Connecting) stopIkev2(context)
        if (TunnelVpnService.state.value.first != TunnelVpnService.STATE_IDLE) {
            context.startService(TunnelVpnService.disconnectIntent(context))
        }
        if (OcVpnService.state.value.first != OcVpnService.STATE_IDLE) {
            context.startService(OcVpnService.disconnectIntent(context))
        }
    }

    /** وضعیت زنده (نه مقدار قدیمی ترکیب) برای استفاده داخل کوروتین. */
    fun liveActive(): Boolean {
        val l = TunnelVpnService.state.value.first
        val o = OcVpnService.state.value.first
        return ikev2Connected || Ikev2Events.status.value.first == Ikev2Events.CONNECTING ||
            l == TunnelVpnService.STATE_CONNECTED || l == TunnelVpnService.STATE_CONNECTING ||
            o == OcVpnService.STATE_CONNECTED || o == OcVpnService.STATE_CONNECTING
    }

    /**
     * انتخاب کانفیگ دیگر وقتی یکی فعال است: اول اتصال کامل قطع، بعد کانفیگ انتخابی وصل می‌شود.
     */
    fun switchTo(s: ProtoServer) {
        if (s.key == selectedKey && anyActive) return
        setSelected(s.key)
        if (!liveActive()) return
        scope.launch {
            disconnectAll()
            val end = System.currentTimeMillis() + 15_000L
            while (liveActive() && System.currentTimeMillis() < end) delay(200L)
            // فرصت برای پایان نخ‌های بومی قبل از اتصال جدید
            delay(1_000L)
            connectSelected()
        }
    }

    // ───── کارت خطا: خلاصه‌ی کوتاه؛ جزئیات در پنجرهٔ لاگ ─────
    // خطای قدیمی یک پروتکل وقتی اتصالی فعال است نشان داده نمی‌شود
    val errorDetail: String? = if (anyActive) null else when {
        ikev2Failed -> "IKEv2: ${ikev2Status.second}"
        l2tpState.first == TunnelVpnService.STATE_FAILED -> "L2TP: ${l2tpState.second}"
        ocState.first == OcVpnService.STATE_FAILED -> "OpenConnect: ${ocState.second}"
        else -> null
    }

    val statusText = when {
        ikev2Connected -> "متصل · IKEv2"
        ikev2Connecting -> "در حال اتصال… · IKEv2"
        l2tpConnected -> "متصل · L2TP/IPsec"
        ocConnected -> "متصل · OpenConnect"
        l2tpState.first == TunnelVpnService.STATE_PAUSED || ocState.first == OcVpnService.STATE_PAUSED -> "متوقف موقت"
        l2tpBusy -> "در حال اتصال… · " + (if (l2tpState.second.isBlank()) "L2TP/IPsec" else l2tpState.second)
        ocBusy -> "در حال اتصال… · OpenConnect"
        else -> "قطع"
    }
    val accent = when {
        connectedAny -> Green
        busyAny -> Cyan
        else -> Violet
    }

    val header: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(if (landscape) 84.dp else 96.dp)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.3f))), CircleShape)
                .clickable { if (anyActive) disconnectAll() else connectSelected() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (anyActive) "قطع" else "اتصال",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            statusText,
            color = if (connectedAny) Green else PTextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (note.isNotBlank()) Text(note, color = PTextSub, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (errorDetail != null) {
            Text("متصل نشد؛ لاگ برنامه را ببینید", color = PDanger, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }

    // ───── دو لیست: ساب‌لینک (راست) و دستی (چپ) ─────
    val tabs: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "سرورهای ساب‌لینک (${subList.size})", 1 to "سرورهای دستی (${manualList.size})").forEach { (idx, title) ->
                val on = listTab == idx
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) Violet else Surface1)
                        .border(1.dp, if (on) Violet else Violet.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .clickable { listTab = idx }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        color = if (on) Color.White else PTextMain,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    val list: @Composable (Modifier) -> Unit = { mod ->
        Column(mod.fillMaxWidth().background(Surface1, RoundedCornerShape(16.dp)).padding(6.dp)) {
            if (listTab == 0) {
                val favKeys = favSub.map { it.key }.toSet()
                val rows = favSub + subList.sortedBy { if (it.source == ProtoLists.PLUS_URL) 0 else 1 }.filter { it.key !in favKeys }
                ServerList(
                    rows = rows,
                    emptyText = if (ProtoLists.SUB_URLS.isEmpty()) "لینک ساب‌لینک این بخش هنوز تنظیم نشده است" else "سروری از ساب‌لینک نیست؛ بروزرسانی کنید",
                    selectedKey = selectedKey,
                    isFav = { it.key in favKeys },
                    onSelect = { switchTo(it) },
                    onFav = {
                        ProtoLists.toggleSubFav(context, it)
                        favSub = ProtoLists.favSub(context)
                    },
                    onEdit = null,
                    onDelete = null,
                )
            } else {
                val favKeys = favMan.toSet()
                val rows = manualList.sortedBy { if (it.key in favKeys) 0 else 1 }
                ServerList(
                    rows = rows,
                    emptyText = "سرور دستی ندارید؛ با دکمهٔ «افزودن سرور دستی» اضافه کنید",
                    selectedKey = selectedKey,
                    isFav = { it.key in favKeys },
                    onSelect = { switchTo(it) },
                    onFav = {
                        ProtoLists.toggleManFav(context, it.key)
                        favMan = ProtoLists.favManKeys(context)
                    },
                    onEdit = { editing = it; dialogType = it.type; dialogOpen = true },
                    onDelete = {
                        ProtoLists.deleteManual(context, it)
                        if (selectedKey == it.key) setSelected(null)
                        reloadManual()
                    },
                )
            }
        }
    }

    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    updating = true
                    scope.launch {
                        note = ProtoLists.refreshSub(context)
                        subList = ProtoLists.loadSub(context)
                        favSub = ProtoLists.favSub(context)
                        updating = false
                    }
                },
                enabled = !updating,
                modifier = Modifier.weight(1f),
            ) {
                Text("بروزرسانی", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(
                onClick = { showChooser = true },
                modifier = Modifier.weight(1f),
            ) {
                Text("افزودن سرور دستی", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        OutlinedButton(onClick = { showLog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("لاگ برنامه", color = PTextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (landscape) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                header()
                actions()
            }
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs()
                list(Modifier.weight(1f))
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            header()
            tabs()
            list(Modifier.weight(1f))
            actions()
        }
    }

    if (showLog) {
        LogDialog(
            text = AppLogger.read(),
            onDismiss = { showLog = false },
            onCopy = { clipboard.setText(AnnotatedString(AppLogger.read())) },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLogger.read())
                context.startActivity(Intent.createChooser(send, "ارسال لاگ"))
            },
            onClear = { AppLogger.clear() },
        )
    }

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            containerColor = Surface1,
            title = { Text("نوع سرور", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VpnType.values().forEach { t ->
                        OutlinedButton(
                            onClick = {
                                showChooser = false
                                editing = null
                                dialogType = t
                                dialogOpen = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(t.label, color = typeColor(t), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChooser = false }) { Text("انصراف") } },
        )
    }

    if (dialogOpen) {
        EntryDialog(
            title = (if (editing == null) "افزودن " else "ویرایش ") + dialogType.label,
            fields = fieldsFor(dialogType),
            initial = initialFor(editing, dialogType),
            onDismiss = { dialogOpen = false },
            onSave = { v ->
                val rawPort = v["port"].orEmpty().trim()
                val portOk = rawPort.isEmpty() || rawPort.toIntOrNull() != null
                val port = if (dialogType == VpnType.OPENCONNECT) rawPort.toIntOrNull() ?: 0 else 0
                if (dialogType == VpnType.OPENCONNECT && !portOk) {
                    "پورت باید عدد باشد"
                } else {
                    val item = ProtoServer(
                        type = dialogType,
                        name = v["name"].orEmpty().trim(),
                        host = v["server"].orEmpty().trim(),
                        port = port,
                        user = v["username"].orEmpty().trim(),
                        password = v["password"].orEmpty(),
                        psk = v["psk"].orEmpty(),
                        remoteId = v["remoteId"].orEmpty().trim(),
                        authGroup = v["authGroup"].orEmpty().trim(),
                        certPin = v["certPin"].orEmpty().trim(),
                        rawKey = "",
                    )
                    ProtoLists.saveManual(context, item, editing?.rawKey)
                    reloadManual()
                    setSelected(item.key)
                    listTab = 1
                    editing = null
                    dialogOpen = false
                    null
                }
            },
        )
    }
}

/** فهرست یک بخش: فشرده (۱۰ تا ۱۴ ردیف در صفحه)؛ قلب، نام، نوع پروتکل، ویرایش و سطل آشغال. */
@Composable
private fun ServerList(
    rows: List<ProtoServer>,
    emptyText: String,
    selectedKey: String?,
    isFav: (ProtoServer) -> Boolean,
    onSelect: (ProtoServer) -> Unit,
    onFav: (ProtoServer) -> Unit,
    onEdit: ((ProtoServer) -> Unit)?,
    onDelete: ((ProtoServer) -> Unit)?,
) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = PTextSub, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { s ->
            val isSel = s.key == selectedKey
            val c = typeColor(s.type)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) c.copy(alpha = 0.18f) else Color.Transparent)
                    .border(1.dp, if (isSel) c else c.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .clickable { onSelect(s) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isFav(s)) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "قلب",
                    tint = if (isFav(s)) Color(0xFFFF5C7A) else PTextSub,
                    modifier = Modifier.size(16.dp).clickable { onFav(s) },
                )
                Spacer8()
                Column(Modifier.weight(1f)) {
                    Text(
                        s.label,
                        color = PTextMain,
                        fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${sstpCountryName(s.country).let { if (it.isBlank()) "" else "$it · " }}${s.type.label} · ${s.host}${if (s.type == VpnType.OPENCONNECT) ":${s.port}" else ""}",
                        color = c,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onEdit != null) {
                    TextButton(onClick = { onEdit(s) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                        Text("ویرایش", color = c, fontSize = 11.sp)
                    }
                }
                if (onDelete != null) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "حذف",
                        tint = PDanger,
                        modifier = Modifier.size(18.dp).clickable { onDelete(s) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Spacer8() {
    Box(Modifier.size(8.dp))
}

/** کارت خطای کوتاه: جزئیات کامل در پنجرهٔ لاگ است. */
@Composable
private fun ErrorCard(summary: String, onDetails: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33FF6B6B))
            .border(1.dp, PDanger, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(summary, color = PTextMain, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("جزئیات و لاگ", fontSize = 11.sp) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("بستن", fontSize = 11.sp, color = PDanger) }
        }
    }
}

/** پنجرهٔ لاگ کامل برنامه با کپی، ارسال و پاک‌کردن. */
@Composable
private fun LogDialog(
    text: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("لاگ برنامه", fontWeight = FontWeight.Bold) },
        text = {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().height(380.dp).verticalScroll(rememberScrollState())) {
                    Text(text.ifBlank { "لاگی ثبت نشده است" }, color = PTextMain, fontSize = 10.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) { Text("کپی") }
                TextButton(onClick = onShare) { Text("ارسال") }
                TextButton(onClick = onClear) { Text("پاک‌کردن", color = PDanger) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}

@Composable
private fun EntryDialog(
    title: String,
    fields: List<Field>,
    initial: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> String?,
) {
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, initial[it.key] ?: "") } } }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { f ->
                    OutlinedTextField(
                        value = values[f.key].orEmpty(),
                        onValueChange = { values[f.key] = it },
                        singleLine = true,
                        label = { Text(if (f.optional) "${f.label} (اختیاری)" else f.label) },
                        visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error.isNotBlank()) Text(error, color = PDanger, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val missing = fields.firstOrNull { !it.optional && values[it.key].orEmpty().isBlank() }
                if (missing != null) {
                    error = "«${missing.label}» را وارد کنید"
                    return@TextButton
                }
                val err = onSave(values.toMap())
                if (err != null) error = err
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}
