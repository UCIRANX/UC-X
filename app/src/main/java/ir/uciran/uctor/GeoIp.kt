package ir.uciran.uctor

import com.multivpn.app.BuildConfig

import android.content.Context
import java.io.File

/**
 * فایل‌های GeoIP که Tor برای تشخیص کشور هر رله لازم دارد.
 *
 * بدون این فایل‌ها Tor عبارت `ExitNodes {de}` را نمی‌فهمد، مجموعه‌ی خروجی‌ها خالی می‌شود و
 * با `StrictNodes 1` هیچ مداری ساخته نمی‌شود؛ یعنی روی هر کشوری اتصال شکست می‌خورد و فقط «خودکار» کار می‌کند.
 *
 * فایل‌ها هنگام بیلد (تسک downloadGeoip در app/build.gradle.kts) داخل assets/geoip گذاشته می‌شوند.
 * اینجا از assets به filesDir کپی می‌شوند، چون Tor فقط مسیر فایل واقعی را می‌خواند.
 */
object GeoIp {
    data class Paths(val v4: File, val v6: File?)

    private const val ASSET_DIR = "geoip"
    private const val V4 = "geoip"
    private const val V6 = "geoip6"

    private fun dir(ctx: Context) = File(ctx.filesDir, "geoip")

    /** آیا این نسخه‌ی برنامه فایل GeoIP را داخل خودش دارد؟ */
    fun bundled(ctx: Context): Boolean =
        runCatching { ctx.assets.list(ASSET_DIR)?.contains(V4) == true }.getOrDefault(false)

    /**
     * فایل‌ها را (فقط در صورت نبودن یا عوض شدن نسخه‌ی برنامه) کپی می‌کند و مسیرشان را برمی‌گرداند.
     * اگر فایلی داخل برنامه نباشد null برمی‌گرداند.
     */
    fun install(ctx: Context): Paths? {
        if (!bundled(ctx)) return null
        val d = dir(ctx).apply { mkdirs() }
        val marker = File(d, "installed.version")
        val ver = BuildConfig.VERSION_CODE.toString()
        val v4 = File(d, V4)
        val v6 = File(d, V6)
        val fresh = marker.exists() && marker.readText().trim() == ver && v4.length() > 0
        if (!fresh) {
            marker.delete()
            if (!copyAsset(ctx, V4, v4)) return null
            copyAsset(ctx, V6, v6) // IPv6 اختیاری است
            marker.writeText(ver)
        }
        return Paths(v4, v6.takeIf { it.length() > 0 })
    }

    private fun copyAsset(ctx: Context, name: String, dest: File): Boolean = try {
        val tmp = File(dest.parentFile, "$name.part")
        ctx.assets.open("$ASSET_DIR/$name").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        dest.delete()
        tmp.renameTo(dest) && dest.length() > 0
    } catch (_: Exception) {
        false
    }
}
