package ir.uciran.uctor.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multivpn.app.BuildConfig
import ir.uciran.uctor.Phase
import ir.uciran.uctor.Prefs
import ir.uciran.uctor.PtSupport
import com.multivpn.app.R
import ir.uciran.uctor.TorController
import ir.uciran.uctor.Transport
import ir.uciran.uctor.UiState
import com.multivpn.app.MainActivity
import ir.uciran.uctor.AppChoices
import ir.uciran.uctor.AppChoice

private const val TG_CHANNEL = "UCIRANIR"
private const val TG_CHANNEL_URL = "https://t.me/UCIRANIR"

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val state by TorController.state.collectAsStateWithLifecycle()
    val prefs = remember { Prefs(ctx.applicationContext) }
    var transport by remember { mutableStateOf(Transport.fromId(prefs.transport)) }
    var country by remember { mutableStateOf(prefs.exitCountry) }
    // وضعیت باز/بسته‌ی کارت‌ها و موقعیت اسکرول همین‌جا (بالاتر از هر شاخه‌ی چیدمان) نگه داشته می‌شود
    // تا با تغییر چیدمان یا چرخش صفحه (rememberSaveable) پاک نشود.
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    val mainScroll = rememberScrollState()
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.CONNECTED) (ctx as? MainActivity)?.startFullVpnIfReady()
    }
    val onConnect: () -> Unit = {
        when (state.phase) {
            Phase.CONNECTED, Phase.CONNECTING -> TorController.disconnect()
            Phase.STOPPING -> Unit
            else -> TorController.connect()
        }
    }
    // کارت‌ها یک‌بار تعریف می‌شوند و در هر سه چیدمان استفاده می‌شوند. movableContentOf باعث می‌شود
    // اگر چیدمان عوض شد، کارت‌ها «جابه‌جا» شوند و هیچ‌کدام از stateهایشان (متن، پنل باز، فوکوس) از نو ساخته نشود.
    val cards = remember {
        movableContentOf {
            val idle = state.phase == Phase.IDLE || state.phase == Phase.ERROR
            ConnectionOptionsCard(
                transport = transport, country = country, enabled = idle,
                onTransport = { t -> transport = t; prefs.transport = t.id },
                onCountry = { c -> country = c; prefs.exitCountry = c },
            )
            BridgeCard(state)
            ChannelCard()
            AdvancedCard(prefs, state, advancedOpen) { advancedOpen = it }
            LogCard(state, logOpen) { logOpen = it }
            Text("UC-X v${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(Modifier.fillMaxSize(), color = Bg, contentColor = MaterialTheme.colorScheme.onBackground) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF1C1238), Bg, Bg))
                )
            ) {
                // لایه‌ی بیرونی: نوار وضعیت + نوار ناوبری + برش صفحه، «بدون کیبورد».
                // انتخاب چیدمان فقط از اندازه‌ی همین لایه انجام می‌شود؛ پس باز شدن کیبورد هرگز چیدمان را عوض نمی‌کند.
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val w = maxWidth
                    val h = maxHeight
                    val wide = w >= 600.dp && w > h * 1.15f
                    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    // لایه‌ی داخلی: فقط فضای باقی‌مانده بعد از کیبورد (محتوا کوچک می‌شود، ولی ساختارش ثابت می‌ماند).
                    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
                        val innerH = maxHeight
                        if (wide) {
                            // افقی / تبلت: دکمه و وضعیت یک طرف (ثابت و وسط‌چین)، کارت‌ها طرف دیگر (اسکرول).
                            Row(
                                Modifier.widthIn(max = 1000.dp).fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Box(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(leftScroll)) {
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(min = innerH),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Header()
                                        Spacer(Modifier.height(6.dp))
                                        ConnectButton(state, (h * 0.5f).coerceIn(110.dp, 180.dp), onConnect)
                                        Spacer(Modifier.height(4.dp))
                                        StatusBlock(state)
                                    }
                                }
                                Column(
                                    Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rightScroll).padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) { cards() }
                            }
                        } else if (h >= 600.dp) {
                            // عمودی: هدر و دکمه بالا و ثابت؛ بقیه اسکرول می‌شود. عرض روی تبلت/فولد محدود و وسط‌چین است.
                            Column(Modifier.widthIn(max = 560.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Header()
                                Spacer(Modifier.height(4.dp))
                                // وقتی کیبورد باز است دکمه کوچک می‌شود (همان composable، فقط اندازه‌اش) تا جا برای فیلدها بماند.
                                ConnectButton(state, if (imeOpen) 84.dp else (h * 0.24f).coerceIn(120.dp, 164.dp), onConnect)
                                Spacer(Modifier.height(4.dp))
                                Column(
                                    Modifier.weight(1f).fillMaxWidth().verticalScroll(mainScroll).padding(bottom = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    StatusBlock(state)
                                    cards()
                                }
                            }
                        } else {
                            // ارتفاع کم (چندپنجره‌ای، فولد…): همه‌چیز با هم اسکرول می‌شود تا جایی بریده نشود.
                            Column(
                                Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(mainScroll).padding(bottom = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Header()
                                ConnectButton(state, 120.dp, onConnect)
                                StatusBlock(state)
                                cards()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(Violet, Color(0xFF4F46E5), Cyan))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_uctor_mono), null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("UC Tor", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
            Text(
                "اتصال امن و ساده به شبکه‌ی Tor",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** اندازه‌ی دکمه با پارامتر size تعیین می‌شود (۱۸۴dp = اندازه‌ی اصلی)؛ بقیه‌ی اجزا متناسب کوچک/بزرگ می‌شوند. */
@Composable
private fun ConnectButton(state: UiState, size: Dp, onClick: () -> Unit) {
    val connecting = state.phase == Phase.CONNECTING || state.phase == Phase.STOPPING
    val connected = state.phase == Phase.CONNECTED
    val inf = rememberInfiniteTransition(label = "ring")
    val angle by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "angle",
    )
    val pulse by inf.animateFloat(
        initialValue = 0.97f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val ring = when {
        connected -> listOf(Green, Cyan, Green)
        state.phase == Phase.ERROR -> listOf(Color(0xFFF87171), Pink, Color(0xFFF87171))
        else -> listOf(Violet, Cyan, Pink, Violet)
    }
    val k = size / 184.dp
    // با بزرگ‌ترشدن فونت سیستم، متن داخل دایره از ۱.۱۵ برابر بزرگ‌تر نمی‌شود تا بریده نشود.
    val fs = LocalDensity.current.fontScale
    val cap = if (fs > 1.15f) 1.15f / fs else 1f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val s = if (connecting) pulse else 1f
                scaleX = s
                scaleY = s
            },
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(ring[0].copy(alpha = 0.35f), Color.Transparent)))
        )
        Box(
            Modifier
                .size(size * (154f / 184f))
                .graphicsLayer { rotationZ = if (connecting) angle else 0f }
                .clip(CircleShape)
                .background(Brush.sweepGradient(ring))
        )
        Box(
            Modifier
                .size(size * (142f / 184f))
                .clip(CircleShape)
                .background(Surface1)
                .clickable(enabled = state.phase != Phase.STOPPING, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(R.drawable.ic_uctor_mono), null,
                    tint = if (connected) Green else Color.White,
                    modifier = Modifier.size(46.dp * k),
                )
                Spacer(Modifier.height(6.dp * k))
                Text(
                    when (state.phase) {
                        Phase.CONNECTED -> "قطع اتصال"
                        Phase.CONNECTING -> "لغو"
                        Phase.STOPPING -> "صبر کنید…"
                        else -> "اتصال"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = (16f * k.coerceIn(0.8f, 1f) * cap).sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun StatusBlock(state: UiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val color = when (state.phase) {
            Phase.CONNECTED -> Green
            Phase.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onBackground
        }
        Text(state.status, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
        if (state.phase == Phase.CONNECTED && !state.vpnReady && state.vpnError.isBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("در حال فعال‌سازی VPN کل گوشی…", fontSize = 12.sp, color = Cyan)
        }
        if (state.vpnError.isNotBlank() && state.phase == Phase.CONNECTED) {
            Spacer(Modifier.height(4.dp))
            Text("VPN: ${state.vpnError}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (state.detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                state.detail, fontSize = 12.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.phase == Phase.CONNECTING) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = Cyan,
                trackColor = Surface2,
            )
        }
    }
}

@Composable
internal fun GlassCard(padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1.copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
            .padding(padding),
        content = content,
    )
}



@Composable
private fun LtrLine(label: String, value: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, color = Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
            Text(value, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ChannelCard() {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF229ED9), Color(0xFF6D28D9))))
            .clickable { openUrl(ctx, TG_CHANNEL_URL) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_telegram), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("کانال تلگرام ما", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "@$TG_CHANNEL",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                style = TextStyle(textDirection = TextDirection.Ltr),
            )
        }
        Icon(Icons.Filled.KeyboardArrowLeft, null, tint = Color.White)
    }
}

@Composable
private fun AdvancedCard(prefs: Prefs, state: UiState, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    var custom by remember { mutableStateOf(prefs.customBridges) }
    var exNodes by remember { mutableStateOf(prefs.excludeNodes) }
    var exExit by remember { mutableStateOf(prefs.excludeExitNodes) }
    var exitNodes by remember { mutableStateOf(prefs.exitNodes) }
    GlassCard(padding = 10.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpenChange(!open) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, null, tint = Violet)
            Spacer(Modifier.width(8.dp))
            Text("تنظیمات پیشرفته و بریج‌ها", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                Text("مسیر عبور ترافیک", fontWeight = FontWeight.Bold)
                TunnelPicker(prefs)
                Field("بریج‌های دستی (هر خط یک بریج)", custom, minLines = 3) {
                    custom = it
                    prefs.customBridges = it
                }
                Field("Exclude nodes   مثال: {ru},{cn}", exNodes) {
                    exNodes = it
                    prefs.excludeNodes = it
                }
                Field("Exclude exit nodes", exExit) {
                    exExit = it
                    prefs.excludeExitNodes = it
                }
                Field("Exit nodes   مثال: {de},{nl}  (با انتخاب کشور نادیده گرفته می‌شود)", exitNodes) {
                    exitNodes = it
                    prefs.exitNodes = it
                }
                Text(
                    "تغییرات از اتصال بعدی اعمال می‌شوند.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
private fun Field(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(textDirection = TextDirection.Ltr, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun LogCard(state: UiState, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    GlassCard(padding = 10.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpenChange(!open) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Info, null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text("گزارش اتصال (لاگ)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(top = 10.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                if (state.log.isEmpty()) "No log yet." else state.log.joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    clip.setText(AnnotatedString(state.log.joinToString("\n")))
                    toast(ctx, "لاگ کپی شد")
                }) { Text("کپی لاگ") }
            }
        }
    }
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        toast(ctx, if (url.startsWith("tg:")) "تلگرام نصب نیست" else "برنامه‌ای برای باز کردن لینک پیدا نشد")
    }
}

private fun toast(ctx: Context, s: String) {
    Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
}
