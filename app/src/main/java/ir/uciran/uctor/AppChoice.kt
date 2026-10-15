package ir.uciran.uctor

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** برنامه‌های قابل انتخاب: همه‌ی برنامه‌هایی که آیکون لانچر دارند (نصبی + سیستمی). */
data class AppChoice(val packageName: String, val label: String, val info: ApplicationInfo, val system: Boolean)

object AppChoices {
    // آیکون‌ها فقط وقتی ردیف دیده شود بارگذاری می‌شوند و در حافظه‌ی کوچکی نگه داشته می‌شوند.
    private val icons = LruCache<String, ImageBitmap>(250)

    fun list(ctx: Context): List<AppChoice> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != ctx.packageName }
            .distinctBy { it.packageName }
            .map { AppChoice(it.packageName, pm.getApplicationLabel(it).toString().trim(), it, (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
            .filter { it.label.isNotBlank() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    fun icon(ctx: Context, app: AppChoice): ImageBitmap? {
        icons.get(app.packageName)?.let { return it }
        return runCatching {
            ctx.packageManager.getApplicationIcon(app.info).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()?.also { icons.put(app.packageName, it) }
    }
}
