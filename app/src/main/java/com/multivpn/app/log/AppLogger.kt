package com.multivpn.app.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * لاگ داخل برنامه: همهٔ پیام‌های موتورها و خطاها در فایل filesDir/logs/vpn.log ذخیره می‌شود.
 * کاربر می‌تواند از داخل برنامه آن را ببیند، کپی یا ارسال کند؛ نیازی به logcat نیست.
 */
object AppLogger {
    private const val MAX_BYTES = 300_000L

    @Volatile
    private var file: File? = null
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            if (file == null) {
                val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
                file = File(dir, "vpn.log")
            }
        }
    }

    fun log(tag: String, message: String) {
        val f = file ?: return
        val line = "${timeFormat.format(Date())} [$tag] $message\n"
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                    f.writeText(keep)
                }
                f.appendText(line)
            }
        }
    }

    fun read(): String = synchronized(lock) {
        runCatching { file?.readText().orEmpty() }.getOrDefault("")
    }

    fun clear() {
        synchronized(lock) { runCatching { file?.writeText("") } }
    }
}
