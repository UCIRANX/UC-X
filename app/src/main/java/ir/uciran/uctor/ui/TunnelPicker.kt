package ir.uciran.uctor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.uciran.uctor.Prefs

private data class Mode(val id: String, val title: String, val desc: String)

private val MODES = listOf(
    Mode("all", "کل گوشی", "همه‌ی برنامه‌ها از Tor رد می‌شوند"),
    Mode("selected", "فقط تیک‌خورده‌ها", "فقط برنامه‌های تیک‌خورده از Tor رد می‌شوند؛ بقیه مستقیم وصل می‌شوند"),
    Mode("bypass", "همه به‌جز تیک‌خورده‌ها", "برنامه‌های تیک‌خورده از Tor رد نمی‌شوند و مستقیم وصل می‌شوند؛ بقیه از Tor رد می‌شوند"),
)

/** سه حالت مسیر ترافیک + فهرست برنامه‌ها (برای دو حالت آخر). تغییر حالت از اتصال بعدی اعمال می‌شود. */
@Composable
internal fun TunnelPicker(prefs: Prefs) {
    var mode by remember { mutableStateOf(prefs.tunnelMode.takeIf { m -> MODES.any { it.id == m } } ?: "all") }
    var apps by remember { mutableStateOf(prefs.selectedApps) }
    val current = MODES.first { it.id == mode }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = .16f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MODES.forEach { m ->
                val on = m.id == mode
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.weight(1f).clip(shape)
                        .background(if (on) Violet.copy(alpha = .25f) else Surface2.copy(alpha = .6f))
                        .border(1.dp, if (on) Violet else Color.Transparent, shape)
                        .clickable { mode = m.id; prefs.tunnelMode = m.id }
                        .padding(horizontal = 4.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(m.title, fontSize = 11.sp, textAlign = TextAlign.Center, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Text(current.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mode == "selected" && apps.isEmpty()) {
            Text("هنوز برنامه‌ای تیک نزده‌اید؛ در این حالت VPN شروع نمی‌شود.", fontSize = 11.sp, color = Color(0xFFFACC15))
        }
        if (mode != "all") {
            Spacer(Modifier.height(2.dp))
            AppPicker(prefs) { apps = it }
        }
    }
}
