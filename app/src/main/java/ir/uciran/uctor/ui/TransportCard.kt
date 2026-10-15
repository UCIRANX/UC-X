package ir.uciran.uctor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.uciran.uctor.ExitCountries
import ir.uciran.uctor.ExitCountry
import ir.uciran.uctor.PtSupport
import ir.uciran.uctor.Transport

/** دایره‌ی رنگی با حرف اول نام روش. */
@Composable
private fun TransportBadge(t: Transport, size: Int) {
    val c = typeColor(t.id)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(c.copy(alpha = .18f)).border(1.dp, c.copy(alpha = .5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(t.id.first().uppercase(), color = c, fontWeight = FontWeight.ExtraBold, fontSize = (size * 0.42f).sp) }
}

/** دایره‌ی پرچم کشور؛ برای «خودکار» یک کره‌ی زمین. */
@Composable
private fun FlagBadge(c: ExitCountry?, size: Int) {
    val tint = if (c != null) Cyan else Color(0xFF94A3B8)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = .16f)).border(1.dp, tint.copy(alpha = .45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(c?.flag ?: "🌐", fontSize = (size * 0.5f).sp) }
}

/** یک کاشی‌ی فشرده: عنوان کوچک + مقدار انتخاب‌شده؛ با کلیک پنل مربوطه باز می‌شود. */
@Composable
private fun SelectorTile(
    modifier: Modifier,
    caption: String,
    value: String,
    tint: Color,
    enabled: Boolean,
    open: Boolean,
    badge: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.alpha(if (enabled) 1f else .55f).clip(shape)
            .background(tint.copy(alpha = .10f))
            .border(1.dp, tint.copy(alpha = .35f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badge()
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(caption, fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = arrow })
    }
}

/**
 * «روش اتصال» و «کشور خروجی» کنار هم در یک کارت فشرده؛ لیست گزینه‌ها زیر کاشی‌ها باز می‌شود.
 * country = کد کشور (مثل "de") یا خالی برای خودکار. تغییرها از اتصال بعدی اعمال می‌شوند.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionOptionsCard(
    transport: Transport,
    country: String,
    enabled: Boolean,
    onTransport: (Transport) -> Unit,
    onCountry: (String) -> Unit,
) {
    var panel by rememberSaveable { mutableStateOf(0) } // 0 = بسته، 1 = روش اتصال، 2 = کشور
    val open = if (enabled) panel else 0
    val selectedCountry = ExitCountries.find(country)
    GlassCard(padding = 10.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectorTile(
                Modifier.weight(1f), "روش اتصال", transport.label.substringBefore(" ("),
                typeColor(transport.id), enabled, open == 1, { TransportBadge(transport, 28) },
            ) { panel = if (panel == 1) 0 else 1 }
            SelectorTile(
                Modifier.weight(1f), "کشور خروجی", selectedCountry?.name ?: "خودکار",
                if (selectedCountry != null) Cyan else Color(0xFF94A3B8), enabled, open == 2, { FlagBadge(selectedCountry, 28) },
            ) { panel = if (panel == 2) 0 else 2 }
        }

        AnimatedVisibility(visible = open == 1) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Transport.entries.forEach { t ->
                    val ok = PtSupport.available(t.id)
                    val on = t == transport
                    val c = typeColor(t.id)
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().alpha(if (ok) 1f else .4f).clip(shape)
                            .background(if (on) c.copy(alpha = .12f) else Color.Transparent)
                            .clickable(enabled = ok) { panel = 0; onTransport(t) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransportBadge(t, 26)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (ok) t.label else "${t.label} (در این نسخه موجود نیست)", fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                            Text(t.desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (on) Icon(Icons.Filled.Check, null, tint = c, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        AnimatedVisibility(visible = open == 2) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CountryChip(null, selectedCountry == null) { panel = 0; onCountry("") }
                    ExitCountries.all.forEach { c ->
                        CountryChip(c, c.code == selectedCountry?.code) { panel = 0; onCountry(c.code) }
                    }
                }
                Text(
                    "با انتخاب کشور، فقط از خروجی‌های همان کشور استفاده می‌شود؛ اگر در آن لحظه خروجی‌ای پیدا نشود، اتصال با ذکر دلیل شکست می‌خورد. تغییر از اتصال بعدی اعمال می‌شود.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountryChip(c: ExitCountry?, on: Boolean, onClick: () -> Unit) {
    val tint = if (c != null) Cyan else Color(0xFF94A3B8)
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape)
            .background(if (on) tint.copy(alpha = .22f) else Surface2.copy(alpha = .6f))
            .border(1.dp, if (on) tint else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c?.flag ?: "🌐", fontSize = 14.sp)
        Spacer(Modifier.width(5.dp))
        Text(c?.name ?: "خودکار", fontSize = 12.sp, maxLines = 1, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}
