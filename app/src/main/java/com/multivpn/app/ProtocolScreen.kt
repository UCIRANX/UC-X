package com.multivpn.app

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.uciran.uctor.ui.Surface1
import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.multivpn.app.log.AppLogger
import androidx.compose.runtime.collectAsState
import io.github.evokelektrique.tunnelforge.TunnelVpnService
import com.multivpn.app.oc.OcParams
import com.multivpn.app.oc.OcVpnService

private val PTextMain = Color(0xFFEDEDF5)
private val PTextSub = Color(0xFFB4B4C8)
private val PDanger = Color(0xFFFF6B6B)
private val PGreen = Color(0xFF3DDC97)
private val PIkev2 = Color(0xFF7C5CFF)
private val PL2tp = Color(0xFF00B4D8)
private val POc = Color(0xFFFF8A3D)
private val PSub = Color(0xFFE85D9A)

/** بخش‌های صفحه: IKEv2، L2TP/IPsec، OpenConnect و لیست ساب‌لینک (در آینده). */
private val SECTION_TITLES = listOf("IKEv2", "L2TP/IPsec", "OpenConnect", "ساب‌لینک")
private val SECTION_COLORS = listOf(PIkev2, PL2tp, POc, PSub)

private data class Field(val key: String, val label: String, val secret: Boolean = false, val optional: Boolean = false, val numeric: Boolean = false)

private val IKEV2_FIELDS = listOf(
    Field("name", "نام پروفایل", optional = true),
    Field("server", "سرور"),
    Field("username", "نام کاربری"),
    Field("password", "رمز", secret = true, optional = true),
    Field("remoteId", "Server identity", optional = true),
    Field("psk", "PSK (اگر پر شود، به جای EAP)", secret = true, optional = true),
)
private val L2TP_FIELDS = listOf(
    Field("name", "نام پروفایل", optional = true),
    Field("server", "سرور"),
    Field("psk", "IPSec pre-shared key (PSK)", secret = true),
    Field("username", "User ID"),
    Field("password", "رمز", secret = true, optional = true),
)
private val OC_FIELDS = listOf(
    Field("name", "نام", optional = true),
    Field("server", "سرور (host یا URL)"),
    Field("port", "پورت", numeric = true),
    Field("username", "نام کاربری"),
    Field("password", "رمز", secret = true),
    Field("authGroup", "Auth group / portal", optional = true),
    Field("certPin", "Server cert pin (sha256:...)", optional = true),
)

/** صفحهٔ اتصال‌های پیشرفته: یک بخش یکپارچه با سه پروتکل و لیست ساب‌لینک. */
@Composable
fun ProtocolScreen() {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    AppLogger.init(context)
    val clipboard = LocalClipboardManager.current
    var showLog by rememberSaveable { mutableStateOf(false) }
    val landscape = config.screenWidthDp > config.screenHeightDp
    val ikev2Supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var section by rememberSaveable { mutableStateOf(0) }
    var ikev2List by remember { mutableStateOf(Ikev2Store.load(context)) }
    var l2tpList by remember { mutableStateOf(ProtocolStore.loadL2tp(context)) }
    var ocList by remember { mutableStateOf(ProtocolStore.loadOc(context)) }
    var selIkev2 by rememberSaveable { mutableStateOf(Ikev2Store.selected(context)) }
    var selL2tp by rememberSaveable { mutableStateOf(ProtocolStore.selectedL2tp(context)) }
    var selOc by rememberSaveable { mutableStateOf(ProtocolStore.selectedOc(context)) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var editKey by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(ikev2Supported) {
        if (!ikev2Supported) {
            onDispose { }
        } else {
            val cb = watchVpn(context) { connected = it }
            onDispose { unwatchVpn(context, cb) }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val s = ikev2List.firstOrNull { it.key == pendingKey }
        note = if (res.resultCode == Activity.RESULT_OK) {
            startIkev2(context, s) ?: ""
        } else {
            "اجازهٔ VPN داده نشد"
        }
    }

    val l2tpState by TunnelVpnService.state.collectAsState()
    var pendingL2tp by rememberSaveable { mutableStateOf<String?>(null) }
    val l2tpConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val entry = l2tpList.firstOrNull { it.key == pendingL2tp }
        if (res.resultCode == Activity.RESULT_OK && entry != null) {
            startL2tp(context, entry)
            note = ""
        } else {
            note = "اجازهٔ VPN داده نشد"
        }
    }
    val l2tpConnected = l2tpState.first == TunnelVpnService.STATE_CONNECTED
    val ocState by OcVpnService.state.collectAsState()
    var pendingOc by rememberSaveable { mutableStateOf<String?>(null) }
    val ocConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val entry = ocList.firstOrNull { it.key == pendingOc }
        if (res.resultCode == Activity.RESULT_OK && entry != null) {
            startOc(context, entry)
            note = ""
        } else {
            note = "اجازهٔ VPN داده نشد"
        }
    }
    val ocConnected = ocState.first == OcVpnService.STATE_CONNECTED
    val ocBusy = ocState.first == OcVpnService.STATE_CONNECTING
    val l2tpBusy = l2tpState.first == TunnelVpnService.STATE_CONNECTING

    val color = SECTION_COLORS[section]
    val isIkev2 = section == 0

    val onCircle: () -> Unit = {
        when (section) {
            0 -> {
                if (!ikev2Supported) {
                    note = "IKEv2 فقط روی اندروید ۱۲ به بالا کار می‌کند"
                } else if (connected) {
                    stopIkev2(context)
                    note = "قطع شد"
                } else {
                    val s = ikev2List.firstOrNull { it.key == selIkev2 }
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
            }
            1 -> {
                if (l2tpConnected || l2tpBusy) {
                    context.startService(TunnelVpnService.disconnectIntent(context))
                    note = "قطع شد"
                } else {
                    val entry = l2tpList.firstOrNull { it.key == selL2tp }
                    if (entry == null) {
                        note = "ابتدا یک سرور L2TP انتخاب کنید"
                    } else {
                        pendingL2tp = entry.key
                        val consent = VpnService.prepare(context)
                        if (consent != null) l2tpConsentLauncher.launch(consent) else startL2tp(context, entry)
                    }
                }
            }
            2 -> {
                if (ocConnected || ocBusy) {
                    context.startService(OcVpnService.disconnectIntent(context))
                    note = "قطع شد"
                } else {
                    val entry = ocList.firstOrNull { it.key == selOc }
                    if (entry == null) {
                        note = "ابتدا یک سرور OpenConnect انتخاب کنید"
                    } else {
                        pendingOc = entry.key
                        val consent = VpnService.prepare(context)
                        if (consent != null) ocConsentLauncher.launch(consent) else startOc(context, entry)
                    }
                }
            }
            else -> note = "اتصال ${SECTION_TITLES[section]} هنوز در این نسخه فعال نیست؛ سرور را ذخیره کنید"
        }
    }

    val circleLabel = when (section) {
        0 -> if (connected) "قطع" else "اتصال"
        1 -> if (l2tpConnected || l2tpBusy) "قطع" else "اتصال"
        2 -> if (ocConnected || ocBusy) "قطع" else "اتصال"
        else -> "—"
    }

    val statusText = when (section) {
        0 -> when {
            connected -> "متصل · ${ikev2List.firstOrNull { it.key == selIkev2 }?.label ?: ""}"
            else -> "قطع"
        }
        1 -> when {
            l2tpConnected -> "متصل · ${l2tpList.firstOrNull { it.key == selL2tp }?.label ?: ""}"
            l2tpBusy -> "در حال اتصال…"
            l2tpState.first == TunnelVpnService.STATE_FAILED -> "خطا در اتصال"
            else -> "قطع"
        }
        2 -> when {
            ocConnected -> "متصل · ${ocList.firstOrNull { it.key == selOc }?.label ?: ""}"
            ocBusy -> "در حال اتصال…"
            ocState.first == OcVpnService.STATE_FAILED -> "خطا در اتصال"
            else -> "قطع"
        }
        else -> "${SECTION_TITLES[section]} · آماده‌ی ذخیره"
    }

    // ───── بخش‌های بالا: دایره، وضعیت، نکته ─────
    val errorDetail: String? = when (section) {
        1 -> if (l2tpState.first == TunnelVpnService.STATE_FAILED) l2tpState.second else null
        2 -> if (ocState.first == OcVpnService.STATE_FAILED) ocState.second else null
        else -> null
    }

    val header: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(if (landscape) 96.dp else 118.dp)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.3f))), CircleShape)
                .clickable { onCircle() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                circleLabel,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(statusText, color = PTextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note.isNotBlank()) Text(note, color = PTextSub, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (!errorDetail.isNullOrBlank()) {
            ErrorBlock(
                detail = errorDetail,
                onCopy = { clipboard.setText(AnnotatedString(errorDetail)) },
                onFullLog = { showLog = true },
                onDismiss = {
                    if (section == 1) TunnelVpnService.dismissError() else OcVpnService.dismissError()
                },
            )
        }
    }

    // ───── نوارِ بخش‌ها ─────
    val tabs: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SECTION_TITLES.forEachIndexed { idx, title ->
                val on = section == idx
                val c = SECTION_COLORS[idx]
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) Brush.linearGradient(listOf(c, c.copy(alpha = 0.6f))) else Brush.linearGradient(listOf(Surface1, Surface1)))
                        .border(1.dp, c.copy(alpha = if (on) 0f else 0.6f), RoundedCornerShape(12.dp))
                        .clickable { section = idx; note = "" }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        color = if (on) Color.White else c,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }

    // ───── فهرست بر اساس بخش ─────
    val list: @Composable (androidx.compose.ui.Modifier) -> Unit = { mod ->
        Column(mod.fillMaxWidth().background(Surface1, RoundedCornerShape(16.dp)).padding(6.dp)) {
            when (section) {
                0 -> ServerRows(
                    rows = ikev2List.map { Triple(it.key, it.label, it.server) },
                    selected = selIkev2,
                    color = color,
                    onSelect = { selIkev2 = it; Ikev2Store.setSelected(context, it) },
                    onEdit = { editKey = it; dialogOpen = true },
                    onDelete = { k ->
                        val rest = ikev2List.filter { it.key != k }
                        Ikev2Store.save(context, rest)
                        ikev2List = rest
                        if (selIkev2 == k) { selIkev2 = null; Ikev2Store.setSelected(context, null) }
                    },
                    emptyText = "سرور IKEv2 ندارید؛ با «افزودن» اضافه کنید",
                )
                1 -> ServerRows(
                    rows = l2tpList.map { Triple(it.key, it.label, it.server) },
                    selected = selL2tp,
                    color = color,
                    onSelect = { selL2tp = it; ProtocolStore.setSelectedL2tp(context, it) },
                    onEdit = { editKey = it; dialogOpen = true },
                    onDelete = { k ->
                        val rest = l2tpList.filter { it.key != k }
                        ProtocolStore.saveL2tp(context, rest)
                        l2tpList = rest
                        if (selL2tp == k) { selL2tp = null; ProtocolStore.setSelectedL2tp(context, null) }
                    },
                    emptyText = "سرور L2TP/IPsec ندارید؛ با «افزودن» اضافه کنید",
                )
                2 -> ServerRows(
                    rows = ocList.map { Triple(it.key, it.label, "${it.server}:${it.port} · AnyConnect") },
                    selected = selOc,
                    color = color,
                    onSelect = { selOc = it; ProtocolStore.setSelectedOc(context, it) },
                    onEdit = { editKey = it; dialogOpen = true },
                    onDelete = { k ->
                        val rest = ocList.filter { it.key != k }
                        ProtocolStore.saveOc(context, rest)
                        ocList = rest
                        if (selOc == k) { selOc = null; ProtocolStore.setSelectedOc(context, null) }
                    },
                    emptyText = "سرور OpenConnect (AnyConnect) ندارید؛ با «افزودن» اضافه کنید",
                )
                else -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "لیست سرورهای ساب‌لینک به‌زودی اضافه می‌شود",
                        color = PTextSub,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    val addRow: @Composable () -> Unit = {
        if (section != 3) {
            OutlinedButton(
                onClick = { editKey = null; dialogOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("افزودن سرور ${SECTION_TITLES[section]}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                header()
                addRow()
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            tabs()
            list(Modifier.weight(1f))
            addRow()
        }
    }

    if (showLog) {
        LogDialog(
            text = AppLogger.read(),
            onDismiss = { showLog = false },
            onCopy = { clipboard.setText(AnnotatedString(AppLogger.read())) },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, AppLogger.read())
                context.startActivity(Intent.createChooser(send, "ارسال لاگ"))
            },
            onClear = { AppLogger.clear() },
        )
    }

    if (dialogOpen) {
        val fields = when (section) {
            0 -> IKEV2_FIELDS
            1 -> L2TP_FIELDS
            else -> OC_FIELDS
        }
        val initial: Map<String, String> = when {
            editKey == null -> if (section == 2) mapOf("port" to "443") else emptyMap()
            section == 0 -> ikev2List.firstOrNull { it.key == editKey }?.let {
                mapOf("name" to it.name, "server" to it.server, "username" to it.username, "password" to it.password, "remoteId" to it.remoteId, "psk" to it.psk)
            } ?: emptyMap()
            section == 1 -> l2tpList.firstOrNull { it.key == editKey }?.let {
                mapOf("name" to it.name, "server" to it.server, "psk" to it.psk, "username" to it.username, "password" to it.password)
            } ?: emptyMap()
            else -> ocList.firstOrNull { it.key == editKey }?.let {
                mapOf("name" to it.name, "server" to it.server, "port" to it.port.toString(), "username" to it.username, "password" to it.password, "authGroup" to it.authGroup, "certPin" to it.certPin)
            } ?: emptyMap()
        }
        EntryDialog(
            title = (if (editKey == null) "افزودن " else "ویرایش ") + SECTION_TITLES[section],
            fields = fields,
            initial = initial,
            onDismiss = { dialogOpen = false },
            onSave = { v ->
                val server = v["server"].orEmpty().trim()
                val user = v["username"].orEmpty().trim()
                when (section) {
                    0 -> {
                        val item = Ikev2Server(v["name"].orEmpty().trim(), server, user, v["password"].orEmpty(), v["remoteId"].orEmpty().trim(), v["psk"].orEmpty())
                        val rest = ikev2List.filter { it.key != editKey && it.key != item.key } + item
                        Ikev2Store.save(context, rest)
                        ikev2List = rest
                        selIkev2 = item.key
                        Ikev2Store.setSelected(context, item.key)
                    }
                    1 -> {
                        val item = L2tpEntry(v["name"].orEmpty().trim(), server, v["psk"].orEmpty(), user, v["password"].orEmpty())
                        val rest = l2tpList.filter { it.key != editKey && it.key != item.key } + item
                        ProtocolStore.saveL2tp(context, rest)
                        l2tpList = rest
                        selL2tp = item.key
                        ProtocolStore.setSelectedL2tp(context, item.key)
                    }
                    else -> {
                        val port = v["port"].orEmpty().toIntOrNull() ?: 443
                        val item = OcEntry(v["name"].orEmpty().trim(), server, port, user, v["password"].orEmpty(), v["authGroup"].orEmpty().trim(), v["certPin"].orEmpty().trim())
                        val rest = ocList.filter { it.key != editKey && it.key != item.key } + item
                        ProtocolStore.saveOc(context, rest)
                        ocList = rest
                        selOc = item.key
                        ProtocolStore.setSelectedOc(context, item.key)
                    }
                }
                dialogOpen = false
                null
            },
        )
    }
}

@Composable
private fun ServerRows(
    rows: List<Triple<String, String, String>>,
    selected: String?,
    color: Color,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    emptyText: String,
) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = PTextSub, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.first }) { (key, label, sub) ->
            val isSel = key == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) color.copy(alpha = 0.18f) else Color.Transparent)
                    .border(1.dp, if (isSel) color else color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .clickable { onSelect(key) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        color = PTextMain,
                        fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(sub, color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onEdit(key) }) { Text("ویرایش", color = color, fontSize = 12.sp) }
                TextButton(onClick = { onDelete(key) }) { Text("حذف", color = PDanger, fontSize = 12.sp) }
            }
        }
    }
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        },
    )
}

/** شروع L2TP: اول VPNهای دیگر قطع می‌شوند، بعد سرویس با پارامترهای سرور روشن می‌شود. */
private fun startL2tp(context: Context, e: L2tpEntry) {
    stopOthersForIkev2(context)
    stopIkev2IfActive(context)
    ProtocolStore.setSelectedL2tp(context, e.key)
    context.startService(TunnelVpnService.connectIntent(context, e.server, e.username, e.password, e.psk))
}

/** شروع OpenConnect: اول VPNهای دیگر قطع می‌شوند، بعد سرویس AnyConnect روشن می‌شود. */
private fun startOc(context: Context, e: OcEntry) {
    stopOthersForIkev2(context)
    stopIkev2IfActive(context)
    ProtocolStore.setSelectedOc(context, e.key)
    context.startService(
        OcVpnService.connectIntent(
            context,
            OcParams(e.server, e.port, e.username, e.password, e.authGroup, e.certPin),
        ),
    )
}

/** کارت خطا: متن کامل قابل انتخاب، با دکمه‌های کپی، لاگ کامل و بستن. */
@Composable
private fun ErrorBlock(detail: String, onCopy: () -> Unit, onFullLog: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33FF6B6B))
            .border(1.dp, PDanger, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectionContainer {
            Text(detail, color = PTextMain, fontSize = 11.sp, textAlign = TextAlign.Start)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("کپی خطا", fontSize = 11.sp) }
            OutlinedButton(onClick = onFullLog, modifier = Modifier.weight(1f)) { Text("لاگ کامل", fontSize = 11.sp) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("بستن", fontSize = 11.sp, color = PDanger) }
        }
    }
}

/** نمایش لاگ کامل برنامه با کپی، ارسال و پاک‌کردن. */
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
                    Text(
                        text.ifBlank { "لاگی ثبت نشده است" },
                        color = PTextMain,
                        fontSize = 10.sp,
                    )
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        },
    )
}
