package com.multivpn.app.core

import android.content.Context
import com.multivpn.app.sub.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * مدیریت اجرای هسته‌های Xray و Hysteria2
 * این آبجکت پروسه هسته را راه‌اندازی، متوقف و وضعیت آن را بررسی می‌کند.
 */
object CoreEngineRunner {
    // پروسه فعال هسته (در صورت اجرا نبودن null است)
    @Volatile
    private var coreProcess: Process? = null

    // آخرین پیام خطای هسته برای نمایش به کاربر
    @Volatile
    var lastErrorLog: String = ""

    // کلاینت مخصوص تبدیل دامنه به IP از طریق DNS over HTTPS
    private val dohResolveClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /**
     * راه‌اندازی هسته برای کانفیگ داده‌شده
     * در صورت موفقیت true و در غیر این صورت false برمی‌گرداند.
     */
    suspend fun startCore(context: Context, config: VpnConfig): Boolean = withContext(Dispatchers.IO) {
        stopCore()
        lastErrorLog = ""

        try {
            val resolvedIp = resolveHostToIp(config.host.removeSurrounding("[", "]"))
            val nativeDir = context.applicationInfo.nativeLibraryDir

            val isHysteria2 = config.protocol.equals("hy2", ignoreCase = true) ||
                config.rawConfig.startsWith("hy2://", ignoreCase = true) ||
                config.rawConfig.startsWith("hysteria2://", ignoreCase = true)

            val pb = if (isHysteria2) {
                val hyBinary = File(nativeDir, "libhysteria.so")
                if (!hyBinary.exists()) {
                    lastErrorLog = "فایل هسته libhysteria.so یافت نشد!"
                    return@withContext false
                }
                hyBinary.setExecutable(true)
                val configFile = File(context.filesDir, "active_hy2.json")
                configFile.writeText(CoreConfigGenerator.buildHysteria2Json(config, resolvedIp), Charsets.UTF_8)
                ProcessBuilder(hyBinary.absolutePath, "client", "-c", configFile.absolutePath)
            } else {
                val xrayBinary = File(nativeDir, "libxray.so")
                if (!xrayBinary.exists()) {
                    lastErrorLog = "فایل هسته libxray.so یافت نشد!"
                    return@withContext false
                }
                xrayBinary.setExecutable(true)
                val configFile = File(context.filesDir, "active_xray.json")
                configFile.writeText(CoreConfigGenerator.buildXrayJson(config, resolvedIp), Charsets.UTF_8)
                ProcessBuilder(xrayBinary.absolutePath, "run", "-c", configFile.absolutePath).apply {
                    environment()["XRAY_LOCATION_ASSET"] = nativeDir
                }
            }

            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            coreProcess = pb.start()

            // منتظر باز شدن پورت SOCKS محلی می‌مانیم (حداکثر ۴ ثانیه)
            for (i in 0 until 20) {
                delay(200L)
                if (isLocalPortListening(CoreConfigGenerator.LOCAL_SOCKS_PORT)) {
                    delay(400L) // فرصت کوتاه برای تکمیل راه‌اندازی پورت HTTP
                    return@withContext true
                }
                val exitVal = try { coreProcess?.exitValue() } catch (_: Exception) { null }
                if (exitVal != null) break
            }

            // اگر پروسه بسته شده باشد، خروجی خطا را برای کاربر استخراج می‌کنیم
            val exitVal = try { coreProcess?.exitValue() } catch (_: Exception) { null }
            if (exitVal != null) {
                val fullOut = coreProcess?.inputStream?.bufferedReader()?.readText()?.trim() ?: ""
                val cleanErr = fullOut.lines().filterNot {
                    it.contains("Xray ") || it.contains("unified platform") || it.contains("Reading config:")
                }.joinToString(" ").takeLast(220)
                lastErrorLog = "خطای هسته: ${cleanErr.ifBlank { fullOut.takeLast(200) }}"
                return@withContext false
            }
            true
        } catch (e: Exception) {
            lastErrorLog = "خطا در آماده‌سازی کانفیگ: ${e.message}"
            false
        }
    }

    /**
     * تبدیل دامنه به IP
     * ابتدا DNS سیستم، سپس DoH کلودفلر؛ در صورت شکست خود host برمی‌گردد.
     * (قبلاً private بود و PingTester نمی‌توانست از آن استفاده کند؛ اکنون public است)
     */
    fun resolveHostToIp(host: String): String {
        // اگر از قبل IP (نسخه ۴ یا ۶) باشد، بدون تغییر برمی‌گردد
        if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) || host.contains(":")) return host
        try {
            val ip = InetAddress.getByName(host).hostAddress
            if (!ip.isNullOrBlank() && !ip.startsWith("10.") && !ip.startsWith("127.")) return ip
        } catch (_: Exception) {}
        // روش جایگزین: DNS over HTTPS
        try {
            val req = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$host&type=A")
                .header("Accept", "application/dns-json")
                .build()
            dohResolveClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return host
                val answers = JSONObject(body).optJSONArray("Answer") ?: return host
                for (i in 0 until answers.length()) {
                    val item = answers.getJSONObject(i)
                    if (item.optInt("type") == 1) {
                        val data = item.optString("data", "")
                        if (data.isNotBlank()) return data
                    }
                }
            }
        } catch (_: Exception) {}
        return host
    }

    /** توقف هسته و آزادسازی پروسه */
    fun stopCore() {
        try {
            coreProcess?.destroy()
            coreProcess?.destroyForcibly()
        } catch (_: Exception) {
        } finally {
            coreProcess = null
        }
    }

    /** بررسی اینکه آیا پروسه هسته هنوز در حال اجراست */
    fun isCoreRunning(): Boolean {
        val proc = coreProcess ?: return false
        return try {
            proc.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    /**
     * بررسی گوش‌دادن یک پورت روی 127.0.0.1
     * (قبلاً private بود؛ اکنون برای PingTester هم public است)
     */
    fun isLocalPortListening(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 250)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * اندازه‌گیری تأخیر واقعی تونل با درخواست HTTP از طریق پروکسی محلی
     *
     * @param httpPort پورت HTTP پروکسی محلی (پیش‌فرض: پورت اصلی اتصال فعال)
     * @param timeoutSec مهلت اتصال و خواندن بر حسب ثانیه (پیش‌فرض: ۸)
     * @return تأخیر به میلی‌ثانیه، یا -1 در صورت ناموفق بودن
     *
     * پارامترها پیش‌فرض دارند تا فراخوانی‌های قبلی بدون آرگومان همچنان کار کنند.
     */
    suspend fun measureRealTunnelDelay(
        httpPort: Int = CoreConfigGenerator.LOCAL_HTTP_PORT,
        timeoutSec: Int = 8
    ): Long = withContext(Dispatchers.IO) {
        val testUrls = listOf(
            "http://www.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204"
        )
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()

        for (url in testUrls) {
            try {
                val start = System.currentTimeMillis()
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 204 || resp.isSuccessful) {
                        val elapsed = System.currentTimeMillis() - start
                        return@withContext if (elapsed <= 0L) 1L else elapsed
                    }
                }
            } catch (_: Exception) {
            }
        }
        -1L
    }
}
