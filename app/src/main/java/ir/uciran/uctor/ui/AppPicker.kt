package ir.uciran.uctor.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.uciran.uctor.AppChoice
import ir.uciran.uctor.AppChoices
import ir.uciran.uctor.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FILTERS = listOf("همه", "نصب‌شده", "سیستمی", "انتخاب‌شده")

/** انتخاب برنامه‌ها: جستجو، فیلتر، آیکون، و لیست تنبل (LazyColumn) تا سبک و روان بماند. */
@Composable
internal fun AppPicker(prefs: Prefs, onChange: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    // ارتفاع فهرست با ارتفاع پنجره تنظیم می‌شود (در حالت افقی کوتاه‌تر).
    val listH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceIn(170.dp, 340.dp)
    var apps by remember { mutableStateOf<List<AppChoice>?>(null) }
    var selected by remember { mutableStateOf(prefs.selectedApps) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { AppChoices.list(ctx) } }

    val all = apps
    val shown = remember(all, query, filter, selected) {
        all.orEmpty().filter { a ->
            (query.isBlank() || a.label.contains(query, true) || a.packageName.contains(query, true)) &&
                when (filter) { 1 -> !a.system; 2 -> a.system; 3 -> a.packageName in selected; else -> true }
        }
    }
    fun toggle(pkg: String) {
        selected = if (pkg in selected) selected - pkg else selected + pkg
        prefs.selectedApps = selected
        onChange(selected)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("جستجوی برنامه…", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FILTERS.forEachIndexed { i, name ->
                val on = filter == i
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.weight(1f).clip(shape)
                        .background(if (on) Violet.copy(alpha = .25f) else Surface2.copy(alpha = .6f))
                        .border(1.dp, if (on) Violet else Color.Transparent, shape)
                        .clickable { filter = i }.padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(name, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${selected.size} برنامه انتخاب شده · ${shown.size} نتیجه", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet(); prefs.selectedApps = selected; onChange(selected) }) {
                Text("حذف همه", fontSize = 12.sp)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(listH).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = .18f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                all == null -> CircularProgressIndicator(Modifier.size(28.dp), color = Cyan, strokeWidth = 3.dp)
                shown.isEmpty() -> Text("برنامه‌ای پیدا نشد", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(shown, key = { it.packageName }) { app -> AppRow(app, app.packageName in selected) { toggle(app.packageName) } }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppChoice, checked: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) { AppChoices.icon(ctx, app) }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = icon
        if (bmp != null) Image(bmp, null, Modifier.size(38.dp))
        else Box(Modifier.size(38.dp).clip(CircleShape).background(Surface2))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
