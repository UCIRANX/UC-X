package ir.uciran.uctor.ui

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.uciran.uctor.TorController
import ir.uciran.uctor.UiState

/** رنگ هر روش اتصال؛ در کارت روش اتصال و کارت بریج‌ها مشترک است. */
internal fun typeColor(t: String) = when (t) {
    "auto" -> Color(0xFFFACC15)
    "webtunnel" -> Cyan
    "obfs4" -> Violet
    "snowflake" -> Green
    "meek_lite" -> Pink
    "direct" -> Color(0xFFF87171)
    else -> Color(0xFF94A3B8)
}

/** کارت فشرده‌ی بریج‌ها: یک ردیف سرتیتر + یک ردیف چیپ. */
@Composable
internal fun BridgeCard(state: UiState) {
    val total = state.bridgeCounts.sumOf { it.second }
    val u = state.bridgeUpdated.trim()
    val subColor = if (state.bridgeFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val sub = when {
        state.bridgeBusy -> "در حال دریافت…"
        state.bridgeFailed -> "دریافت ناموفق بود؛ لیست قبلی استفاده می‌شود"
        u.isBlank() -> "لیست داخلی برنامه"
        else -> "به‌روزرسانی ${u.replace('T', ' ').removeSuffix("Z").take(16)} UTC"
    }
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a",
    )
    GlassCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("بریج‌ها", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("$total", fontSize = 11.sp, color = Cyan, fontWeight = FontWeight.SemiBold)
                }
                Text(sub, fontSize = 10.sp, color = subColor)
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Cyan.copy(alpha = .14f))
                    .clickable(enabled = !state.bridgeBusy) { TorController.requestBridgeUpdate() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Refresh, "به‌روزرسانی", tint = Cyan, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (state.bridgeBusy) spin else 0f })
            }
        }
        if (state.bridgeCounts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.bridgeCounts.forEach { (t, n) -> BridgeChip(t, n, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BridgeChip(type: String, count: Int, modifier: Modifier) {
    val c = typeColor(type)
    val a = if (count > 0) 1f else .45f
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(c.copy(alpha = .12f * a)).border(1.dp, c.copy(alpha = .35f * a), shape).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$count", color = c.copy(alpha = a), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        Text(if (type == "meek_lite") "meek" else type, fontSize = 9.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
